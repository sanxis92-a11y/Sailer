package com.sailer.browser.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.sailer.browser.download.Downloader
import com.sailer.browser.download.HlsPlaylist
import com.sailer.browser.download.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Una opción de descarga ya analizada, lista para mostrarla de forma clara. */
data class MediaOption(
    val url: String,
    val kind: MediaKind,
    /** Lado corto del vídeo en píxeles (720, 1080...) o 0 si se desconoce. */
    val quality: Int,
    val format: String,
    val sizeBytes: Long,
    val sizeIsEstimate: Boolean,
    val durationSec: Double,
    val host: String,
    val drm: Boolean,
) {
    val isAudio get() = kind == MediaKind.AUDIO

    val qualityLabel: String?
        get() = when {
            quality >= 2000 -> "4K"
            quality >= 1400 -> "1440p"
            quality > 0 -> "${quality}p"
            else -> null
        }

    /** Vídeo muy corto o muy pequeño: suele ser una vista previa o un anuncio. */
    val looksLikePreview: Boolean
        get() = !isAudio && ((durationSec in 0.5..30.0) || (sizeBytes in 1 until 1_500_000 && !sizeIsEstimate))
}

/**
 * Analiza los vídeos/audios detectados en una página: calidades disponibles
 * (en HLS, cada resolución), duración, tamaño y formato.
 */
class MediaInspector(private val context: Context) {

    private val client: OkHttpClient by lazy {
        HttpClients.get(context).newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    }

    suspend fun inspect(
        media: List<DetectedMedia>,
        elements: List<MediaElementInfo>,
        referer: String?,
        userAgent: String,
    ): List<MediaOption> = withContext(Dispatchers.IO) {
        val headers = Headers.Builder().add("User-Agent", userAgent).add("Accept", "*/*").apply {
            referer?.takeIf { it.startsWith("https://") }?.let { add("Referer", it) }
        }.build()
        // Si el reproductor de la página usa streaming, su duración sirve para las listas HLS.
        val pageDuration = elements.filter { !it.isAudio }.maxOfOrNull { it.duration } ?: 0.0
        coroutineScope {
            media.map { item ->
                async {
                    runCatching { inspectOne(item, elements, headers, pageDuration) }
                        .getOrElse { listOf(fallback(item, elements)) }
                }
            }.awaitAll().flatten()
        }
            .distinctBy { it.url }
            .sortedWith(
                compareBy<MediaOption> { it.drm }
                    .thenBy { it.looksLikePreview }
                    .thenBy { it.isAudio }
                    .thenByDescending { it.quality }
                    .thenByDescending { it.sizeBytes }
            )
    }

    /** Descarga una miniatura pequeña para mostrarla en la lista. */
    suspend fun loadThumbnail(url: String, referer: String?, userAgent: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).apply {
                referer?.takeIf { it.startsWith("https://") }?.let { header("Referer", it) }
            }.build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: return@use null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()
    }

    private fun inspectOne(
        item: DetectedMedia,
        elements: List<MediaElementInfo>,
        headers: Headers,
        pageDuration: Double,
    ): List<MediaOption> {
        val host = hostOf(item.url)
        if (item.kind == MediaKind.HLS) {
            val text = fetchText(item.url, headers)
            if (HlsPlaylist.isMaster(text)) {
                val variants = HlsPlaylist.variants(item.url, text)
                if (variants.isEmpty()) throw IOException("Sin calidades")
                val best = variants.maxBy { it.bandwidth }
                val bestText = runCatching { fetchText(best.url, headers) }.getOrNull()
                val duration = bestText?.let { HlsPlaylist.durationSeconds(it) }?.takeIf { it > 0 } ?: pageDuration
                val drm = HlsPlaylist.hasDrm(text) || (bestText != null && HlsPlaylist.hasDrm(bestText))
                return variants.map { v ->
                    MediaOption(
                        url = v.url,
                        kind = MediaKind.HLS,
                        quality = shortSide(v.width, v.height),
                        format = "Streaming",
                        sizeBytes = if (duration > 0 && v.bandwidth > 0) (v.bandwidth * duration / 8).toLong() else -1,
                        sizeIsEstimate = true,
                        durationSec = duration,
                        host = host,
                        drm = drm,
                    )
                }
            }
            val duration = HlsPlaylist.durationSeconds(text).takeIf { it > 0 } ?: pageDuration
            return listOf(
                MediaOption(
                    url = item.url,
                    kind = MediaKind.HLS,
                    quality = qualityFromUrl(item.url),
                    format = "Streaming",
                    sizeBytes = -1,
                    sizeIsEstimate = true,
                    durationSec = duration,
                    host = host,
                    drm = HlsPlaylist.hasDrm(text),
                )
            )
        }

        val (size, mime) = probe(item.url, headers)
        val element = elements.firstOrNull { it.src == item.url }
        val ext = MediaSniffer.extensionOf(item.url)
        val kind = MediaSniffer.kindForMime(mime)?.takeIf { it == MediaKind.AUDIO || it == MediaKind.VIDEO }
            ?: if (element?.isAudio == true) MediaKind.AUDIO else item.kind
        return listOf(
            MediaOption(
                url = item.url,
                kind = kind,
                quality = element?.let { shortSide(it.width, it.height) }?.takeIf { it > 0 } ?: qualityFromUrl(item.url),
                format = formatName(ext, mime),
                sizeBytes = size,
                sizeIsEstimate = false,
                durationSec = element?.duration ?: 0.0,
                host = host,
                drm = false,
            )
        )
    }

    private fun fallback(item: DetectedMedia, elements: List<MediaElementInfo>): MediaOption {
        val element = elements.firstOrNull { it.src == item.url }
        return MediaOption(
            url = item.url,
            kind = item.kind,
            quality = element?.let { shortSide(it.width, it.height) }?.takeIf { it > 0 } ?: qualityFromUrl(item.url),
            format = if (item.kind == MediaKind.HLS) "Streaming" else formatName(MediaSniffer.extensionOf(item.url), null),
            sizeBytes = -1,
            sizeIsEstimate = false,
            durationSec = element?.duration ?: 0.0,
            host = hostOf(item.url),
            drm = false,
        )
    }

    /** Pregunta el tamaño y tipo sin descargar el archivo. */
    private fun probe(url: String, headers: Headers): Pair<Long, String?> {
        val secure = Downloader.secureUrl(url)
        runCatching {
            client.newCall(Request.Builder().url(secure).headers(headers).head().build()).execute().use { r ->
                val length = r.header("Content-Length")?.toLongOrNull() ?: -1
                if (r.isSuccessful && length > 0) return length to r.header("Content-Type")
            }
        }
        client.newCall(Request.Builder().url(secure).headers(headers).header("Range", "bytes=0-0").build()).execute().use { r ->
            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 }
                ?: -1
            return total to r.header("Content-Type")
        }
    }

    private fun fetchText(url: String, headers: Headers): String =
        client.newCall(Request.Builder().url(Downloader.secureUrl(url)).headers(headers).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("Vacío")
        }

    private fun shortSide(w: Int, h: Int): Int = if (w > 0 && h > 0) minOf(w, h) else maxOf(h, 0)

    private fun qualityFromUrl(url: String): Int {
        val match = Regex("""(?<![0-9])(2160|1440|1080|720|540|480|360|240)p?(?![0-9])""").find(url)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun formatName(ext: String?, mime: String?): String = when {
        ext != null && ext != "m3u8" -> ext.uppercase()
        mime != null && mime.contains('/') -> mime.substringAfter('/').substringBefore(';').uppercase()
        else -> "?"
    }

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: ""
}
