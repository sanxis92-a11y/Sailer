package com.sailer.browser.download

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Descarga vídeos en streaming HLS (.m3u8): elige la mejor calidad, baja
 * todos los trozos, los descifra si usan AES-128 (no es DRM) y los une en un
 * solo archivo. Los vídeos con DRM (FairPlay, Widevine...) se rechazan.
 */
class HlsDownloader(private val client: OkHttpClient, private val headers: Headers) {

    class DrmException : IOException("Este vídeo está protegido con DRM y no se puede descargar.")

    private data class Range(val offset: Long, val length: Long)
    private data class Key(val uri: String, val iv: ByteArray?)
    private data class Segment(
        val url: String,
        val sequence: Long,
        val key: Key?,
        val mapUrl: String?,
        val mapRange: Range?,
        val range: Range?,
    )

    private val attrRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    /** Escribe el vídeo en [out] y devuelve su extensión ("ts" o "mp4"). */
    suspend fun download(url: String, out: OutputStream, onProgress: (Int) -> Unit): String {
        var playlistUrl = url
        var text = fetchText(playlistUrl)
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("No es una lista HLS válida")
        if (text.contains("#EXT-X-STREAM-INF")) {
            playlistUrl = bestVariant(playlistUrl, text)
            text = fetchText(playlistUrl)
        }
        val segments = parseSegments(playlistUrl, text)
        if (segments.isEmpty()) throw IOException("La lista HLS está vacía")

        val keys = HashMap<String, ByteArray>()
        var lastMap: String? = null
        var done = 0
        coroutineScope {
            segments.chunked(PARALLEL).forEach { chunk ->
                ensureActive()
                val parts = chunk.map { seg ->
                    async {
                        var data = fetchBytes(seg.url, seg.range)
                        seg.key?.let { key ->
                            val secret = synchronized(keys) { keys[key.uri] } ?: fetchBytes(key.uri, null).also {
                                synchronized(keys) { keys[key.uri] = it }
                            }
                            data = decryptAes128(data, secret, key.iv ?: ivFromSequence(seg.sequence))
                        }
                        data
                    }
                }.awaitAll()
                chunk.forEachIndexed { i, seg ->
                    if (seg.mapUrl != null && seg.mapUrl != lastMap) {
                        out.write(fetchBytes(seg.mapUrl, seg.mapRange))
                        lastMap = seg.mapUrl
                    }
                    out.write(parts[i])
                }
                done += chunk.size
                onProgress(done * 100 / segments.size)
            }
        }
        return if (lastMap != null) "mp4" else "ts"
    }

    private fun bestVariant(playlistUrl: String, text: String): String {
        var bestBandwidth = -1L
        var best: String? = null
        var pendingBandwidth: Long? = null
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") ->
                    pendingBandwidth = attrs(line)["BANDWIDTH"]?.toLongOrNull() ?: 0L
                line.isNotEmpty() && !line.startsWith("#") && pendingBandwidth != null -> {
                    val bandwidth = pendingBandwidth ?: 0L
                    if (bandwidth > bestBandwidth) {
                        bestBandwidth = bandwidth
                        best = resolve(playlistUrl, line)
                    }
                    pendingBandwidth = null
                }
            }
        }
        return best ?: throw IOException("No se encontró ninguna calidad de vídeo")
    }

    private fun parseSegments(playlistUrl: String, text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var sequence = 0L
        var key: Key? = null
        var mapUrl: String? = null
        var mapRange: Range? = null
        var range: Range? = null
        var nextOffset = 0L
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(line)
                    val format = a["KEYFORMAT"]
                    key = when {
                        a["METHOD"] == "NONE" -> null
                        a["METHOD"] == "AES-128" && (format == null || format == "identity") -> {
                            val uri = a["URI"] ?: throw IOException("Clave HLS sin dirección")
                            Key(resolve(playlistUrl, uri), a["IV"]?.let(::parseHexIv))
                        }
                        else -> throw DrmException()
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(line)
                    mapUrl = a["URI"]?.let { resolve(playlistUrl, it) }
                    mapRange = a["BYTERANGE"]?.let { parseRange(it, 0) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") ->
                    range = parseRange(line.substringAfter(':'), nextOffset)
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    segments.add(Segment(resolve(playlistUrl, line), sequence, key, mapUrl, mapRange, range))
                    nextOffset = range?.let { it.offset + it.length } ?: 0L
                    range = null
                    sequence++
                }
            }
        }
        return segments
    }

    private fun attrs(line: String): Map<String, String> =
        attrRegex.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun parseRange(value: String, defaultOffset: Long): Range {
        val parts = value.trim().trim('"').split('@')
        val length = parts[0].toLong()
        val offset = parts.getOrNull(1)?.toLong() ?: defaultOffset
        return Range(offset, length)
    }

    private fun parseHexIv(value: String): ByteArray {
        val hex = value.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun ivFromSequence(sequence: Long): ByteArray =
        ByteBuffer.allocate(16).putLong(0L).putLong(sequence).array()

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun resolve(base: String, relative: String): String =
        base.toHttpUrl().resolve(relative)?.toString() ?: relative

    private fun fetchText(url: String): String = String(fetchBytes(url, null), Charsets.UTF_8)

    private fun fetchBytes(url: String, range: Range?): ByteArray {
        val builder = Request.Builder().url(Downloader.secureUrl(url)).headers(headers)
        range?.let { builder.header("Range", "bytes=${it.offset}-${it.offset + it.length - 1}") }
        val request = builder.build()
        var lastError: IOException? = null
        repeat(3) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Error del servidor (${response.code})")
                    return response.body?.bytes() ?: throw IOException("Respuesta vacía")
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Error de descarga")
    }

    companion object {
        private const val PARALLEL = 4
    }
}
