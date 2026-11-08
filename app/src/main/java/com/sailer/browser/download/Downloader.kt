package com.sailer.browser.download

import com.sailer.browser.Lang
import android.content.Context
import android.util.Base64
import android.webkit.MimeTypeMap
import com.sailer.browser.media.MediaKind
import com.sailer.browser.media.MediaSniffer
import com.sailer.browser.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/** Lo que el usuario ha pedido descargar. */
data class DownloadRequest(
    val url: String,
    val title: String,
    val kind: MediaKind,
    val referer: String?,
    val userAgent: String,
    val mimeType: String? = null,
    val fileName: String? = null,
    /** Pista de audio separada (HLS), si se conoce. */
    val audioUrl: String? = null,
    /** Calidad de vídeo elegida en un manifiesto DASH. */
    val videoId: String? = null,
    /** Carpeta de la carpeta segura donde se guarda (p. ej. el nombre de la web). */
    val folder: String = "",
)

/**
 * Estado de una descarga que permite pausarla y reanudarla: los trozos ya
 * bajados se guardan cifrados en [workDir] hasta que termina.
 */
class DownloadState(val workDir: File) {
    @Volatile var plan: Plan? = null
    @Volatile var mime: String? = null
    val doneVideo = AtomicInteger(0)
    val doneAudio = AtomicInteger(0)
    val keys = ConcurrentHashMap<String, ByteArray>()

    fun clear() {
        workDir.deleteRecursively()
        plan = null
        doneVideo.set(0)
        doneAudio.set(0)
    }
}

/** Descarga un archivo (directo, HLS o DASH) y lo guarda cifrado en la carpeta segura. */
class Downloader(private val context: Context) {

    private val vault get() = Vault.get(context)
    private val client: OkHttpClient get() = HttpClients.get(context)

    suspend fun download(request: DownloadRequest, state: DownloadState, onProgress: (Int) -> Unit): Vault.Entry =
        withContext(Dispatchers.IO) {
            state.workDir.mkdirs()
            when {
                request.url.startsWith("data:") -> saveDataUrl(request)
                request.kind == MediaKind.HLS || request.kind == MediaKind.DASH -> segmented(request, state, onProgress)
                else -> direct(request, state, onProgress)
            }
        }

    // ---- Archivo directo (se reanuda con "Range") ----

    private suspend fun direct(request: DownloadRequest, state: DownloadState, onProgress: (Int) -> Unit): Vault.Entry {
        val crypto = vault.crypto
        val part = File(state.workDir, "file.part")
        var already = crypto.plainLength(part)
        val builder = Request.Builder().url(secureUrl(request.url)).headers(headersFor(request))
        if (already > 0) builder.header("Range", "bytes=$already-")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 416 && already > 0) {
                // Ya estaba completo.
            } else {
                if (!response.isSuccessful) throw IOException(Lang.t("Error del servidor (${response.code})", "Server error (${response.code})"))
                if (response.code != 206 && already > 0) {
                    // El servidor no permite reanudar: empezar de cero.
                    part.delete()
                    already = 0
                }
                val body = response.body ?: throw IOException(Lang.t("Respuesta vacía", "Empty response"))
                body.contentType()?.let { "${it.type}/${it.subtype}" }
                    ?.takeUnless { it == "application/octet-stream" }
                    ?.let { state.mime = it }
                val total = body.contentLength().takeIf { it > 0 }?.plus(already) ?: -1
                var copied = already
                var lastPercent = -1
                val buffer = ByteArray(128 * 1024)
                crypto.appendTo(part).use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            copied += n
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            }
        }
        val mime = state.mime ?: request.mimeType
        val urlExt = MediaSniffer.extensionOf(request.url)
        val kind = MediaSniffer.kindForMime(mime)?.takeIf { it != MediaKind.HLS && it != MediaKind.DASH }
            ?: MediaSniffer.kindForExtension(urlExt)?.takeIf { it != MediaKind.HLS && it != MediaKind.DASH }
            ?: request.kind.takeIf { it != MediaKind.HLS && it != MediaKind.DASH }
            ?: MediaKind.FILE
        val ext = urlExt?.takeIf { MediaSniffer.kindForExtension(it) != null }
            ?: mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: urlExt
            ?: "bin"
        return vault.adopt(part, fileName(request, ext), kind, mime, request.folder)
    }

    // ---- HLS y DASH (por trozos, se reanuda por el último trozo) ----

    private suspend fun segmented(request: DownloadRequest, state: DownloadState, onProgress: (Int) -> Unit): Vault.Entry {
        val headers = headersFor(request)
        val plan = state.plan ?: buildPlan(request, headers).also { state.plan = it }
        val total = plan.totalPieces
        val report = { onProgress(((state.doneVideo.get() + state.doneAudio.get()) * 100L / total).toInt()) }

        val videoPart = File(state.workDir, "video.part")
        val audioPart = File(state.workDir, "audio.part")
        downloadTrack(plan.video, videoPart, state.doneVideo, state, headers, report)
        plan.audio?.let { downloadTrack(it, audioPart, state.doneAudio, state, headers, report) }

        val audioOnly = plan.audio == null && plan.video.extension in AUDIO_EXTENSIONS
        val audio = plan.audio
        if (audio == null) {
            val ext = plan.video.extension
            return vault.adopt(videoPart, fileName(request, ext), if (audioOnly) MediaKind.AUDIO else MediaKind.VIDEO, mimeFor(ext), request.folder)
        }

        // Vídeo y audio separados: unirlos en un solo archivo.
        val webm = plan.video.extension == "webm"
        val ext = if (webm) "webm" else "mp4"
        val muxed = File(state.workDir, "muxed.tmp")
        return try {
            Muxer.mux(vault.crypto, videoPart, audioPart, muxed, webm)
            val writer = vault.newWriter()
            try {
                muxed.inputStream().use { it.copyTo(writer.output, 256 * 1024) }
                writer.commit(fileName(request, ext), MediaKind.VIDEO, mimeFor(ext), folder = request.folder)
            } catch (e: Throwable) {
                writer.abort()
                throw e
            }
        } catch (e: Exception) {
            // Si no se pueden unir (formato raro), se guardan las dos pistas por separado.
            muxed.delete()
            vault.adopt(audioPart, fileName(request, audio.extension, " (audio)"), MediaKind.AUDIO, mimeFor(audio.extension), request.folder)
            vault.adopt(videoPart, fileName(request, plan.video.extension, Lang.t(" (vídeo)", " (video)")), MediaKind.VIDEO, mimeFor(plan.video.extension), request.folder)
        } finally {
            muxed.delete()
        }
    }

    private fun buildPlan(request: DownloadRequest, headers: Headers): Plan = when (request.kind) {
        MediaKind.DASH -> DashManifest.parse(request.url, fetchText(request.url, headers)).plan(request.videoId)
        else -> HlsDownloader.plan(request.url, request.audioUrl) { fetchText(it, headers) }
    }

    private suspend fun downloadTrack(
        track: TrackPlan,
        part: File,
        done: AtomicInteger,
        state: DownloadState,
        headers: Headers,
        report: () -> Unit,
    ) {
        if (done.get() == 0) part.delete()
        if (done.get() >= track.pieces.size) return
        vault.crypto.appendTo(part).use { out ->
            coroutineScope {
                track.pieces.drop(done.get()).chunked(PARALLEL).forEach { chunk ->
                    ensureActive()
                    val parts = chunk.map { piece -> async { fetchPiece(piece, state, headers) } }.awaitAll()
                    parts.forEach { bytes ->
                        out.write(bytes)
                        done.incrementAndGet()
                    }
                    out.flush()
                    report()
                }
            }
        }
    }

    private fun fetchPiece(piece: Piece, state: DownloadState, headers: Headers): ByteArray {
        var data = fetchBytes(piece.url, headers, piece.rangeStart, piece.rangeEnd)
        val keyUrl = piece.keyUrl
        val iv = piece.iv
        if (keyUrl != null && iv != null) {
            val key = state.keys[keyUrl] ?: fetchBytes(keyUrl, headers, -1, -1).also { state.keys[keyUrl] = it }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            data = cipher.doFinal(data)
        }
        return data
    }

    private fun fetchText(url: String, headers: Headers): String = String(fetchBytes(url, headers, -1, -1), Charsets.UTF_8)

    private fun fetchBytes(url: String, headers: Headers, rangeStart: Long, rangeEnd: Long): ByteArray {
        val builder = Request.Builder().url(secureUrl(url)).headers(headers)
        if (rangeStart >= 0) builder.header("Range", "bytes=$rangeStart-${if (rangeEnd >= 0) rangeEnd else ""}")
        val request = builder.build()
        var lastError: IOException? = null
        repeat(3) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException(Lang.t("Error del servidor (${response.code})", "Server error (${response.code})"))
                    return response.body?.bytes() ?: throw IOException(Lang.t("Respuesta vacía", "Empty response"))
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException(Lang.t("Error de descarga", "Download error"))
    }

    // ---- Imágenes incrustadas (data:) ----

    private fun saveDataUrl(request: DownloadRequest): Vault.Entry {
        val header = request.url.substringBefore(',')
        val payload = request.url.substringAfter(',')
        val mime = header.removePrefix("data:").substringBefore(';').ifEmpty { "application/octet-stream" }
        val bytes = if (header.endsWith(";base64")) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
        }
        val writer = vault.newWriter()
        try {
            writer.output.write(bytes)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            return writer.commit(fileName(request, ext), MediaSniffer.kindForMime(mime) ?: MediaKind.FILE, mime, folder = request.folder)
        } catch (e: Throwable) {
            writer.abort()
            throw e
        }
    }

    private fun headersFor(request: DownloadRequest): Headers {
        val builder = Headers.Builder()
            .add("User-Agent", request.userAgent)
            .add("Accept", "*/*")
        request.referer?.takeIf { it.startsWith("https://") }?.let { builder.add("Referer", it) }
        return builder.build()
    }

    private fun fileName(request: DownloadRequest, ext: String, suffix: String = ""): String {
        request.fileName?.takeIf { it.isNotBlank() }?.let { return sanitize(it) }
        val base = sanitize(request.title).ifBlank { "descarga" }.take(80)
        return "$base$suffix.$ext"
    }

    companion object {
        private const val PARALLEL = 4
        private val AUDIO_EXTENSIONS = setOf("m4a", "weba", "aac", "mp3", "ac3", "ec3")

        fun mimeFor(ext: String): String = when (ext) {
            "ts" -> "video/mp2t"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "m4a" -> "audio/mp4"
            "weba" -> "audio/webm"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }

        fun sanitize(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("\\s+"), " ").trim()

        /** Las descargas también van siempre por HTTPS. */
        fun secureUrl(url: String): String =
            if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
    }
}
