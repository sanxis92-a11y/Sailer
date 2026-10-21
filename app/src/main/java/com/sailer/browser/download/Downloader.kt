package com.sailer.browser.download

import android.content.Context
import android.util.Base64
import android.webkit.MimeTypeMap
import com.sailer.browser.media.MediaKind
import com.sailer.browser.media.MediaSniffer
import com.sailer.browser.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import java.io.IOException
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
)

/** Descarga un archivo (directo o HLS) y lo guarda cifrado en la carpeta segura. */
class Downloader(private val context: Context) {

    suspend fun download(request: DownloadRequest, onProgress: (Int) -> Unit): Vault.Entry =
        withContext(Dispatchers.IO) {
            val vault = Vault.get(context)
            val writer = vault.newWriter()
            try {
                when {
                    request.url.startsWith("data:") -> saveDataUrl(request, writer)
                    request.kind == MediaKind.HLS -> {
                        val headers = headersFor(request)
                        val ext = HlsDownloader(HttpClients.get(context), headers)
                            .download(request.url, writer.output, onProgress)
                        val mime = if (ext == "mp4") "video/mp4" else "video/mp2t"
                        writer.commit(fileName(request, ext), MediaKind.VIDEO, mime)
                    }
                    else -> direct(request, writer, onProgress)
                }
            } catch (e: Throwable) {
                writer.abort()
                throw e
            }
        }

    private suspend fun direct(request: DownloadRequest, writer: Vault.Writer, onProgress: (Int) -> Unit): Vault.Entry {
        val call = HttpClients.get(context).newCall(
            Request.Builder().url(secureUrl(request.url)).headers(headersFor(request)).build()
        )
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Error del servidor (${response.code})")
            val body = response.body ?: throw IOException("Respuesta vacía")
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                ?.takeUnless { it == "application/octet-stream" } ?: request.mimeType
            val urlExt = MediaSniffer.extensionOf(request.url)
            val kind = MediaSniffer.kindForMime(mime)?.takeUnless { it == MediaKind.HLS }
                ?: MediaSniffer.kindForExtension(urlExt)?.takeUnless { it == MediaKind.HLS }
                ?: request.kind.takeUnless { it == MediaKind.HLS }
                ?: MediaKind.FILE
            val ext = urlExt?.takeIf { MediaSniffer.kindForExtension(it) != null }
                ?: mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: urlExt
                ?: "bin"
            val total = body.contentLength()
            var copied = 0L
            var lastPercent = -1
            val buffer = ByteArray(128 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    writer.output.write(buffer, 0, n)
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
            return writer.commit(fileName(request, ext), kind, mime)
        }
    }

    private fun saveDataUrl(request: DownloadRequest, writer: Vault.Writer): Vault.Entry {
        // data:image/png;base64,....
        val header = request.url.substringBefore(',')
        val payload = request.url.substringAfter(',')
        val mime = header.removePrefix("data:").substringBefore(';').ifEmpty { "application/octet-stream" }
        val bytes = if (header.endsWith(";base64")) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
        }
        writer.output.write(bytes)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        return writer.commit(fileName(request, ext), MediaSniffer.kindForMime(mime) ?: MediaKind.FILE, mime)
    }

    private fun headersFor(request: DownloadRequest): Headers {
        val builder = Headers.Builder()
            .add("User-Agent", request.userAgent)
            .add("Accept", "*/*")
        request.referer?.takeIf { it.startsWith("https://") }?.let { builder.add("Referer", it) }
        return builder.build()
    }

    private fun fileName(request: DownloadRequest, ext: String): String {
        request.fileName?.takeIf { it.isNotBlank() }?.let { return sanitize(it) }
        val base = sanitize(request.title).ifBlank { "descarga" }.take(80)
        return "$base.$ext"
    }

    companion object {
        fun sanitize(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("\\s+"), " ").trim()

        /** Las descargas también van siempre por HTTPS. */
        fun secureUrl(url: String): String =
            if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
    }
}
