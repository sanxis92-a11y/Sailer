package com.sailer.browser.download

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Utilidades para leer listas HLS (.m3u8). */
object HlsPlaylist {

    data class Variant(val url: String, val bandwidth: Long, val width: Int, val height: Int)

    private val attrRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun attrs(line: String): Map<String, String> =
        attrRegex.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun resolve(base: String, relative: String): String =
        runCatching { base.toHttpUrl().resolve(relative)?.toString() }.getOrNull() ?: relative

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun variants(playlistUrl: String, text: String): List<Variant> {
        val result = mutableListOf<Variant>()
        var pending: Map<String, String>? = null
        text.lineSequence().map { it.trim() }.forEach { line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pending = attrs(line)
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                pending?.let { a ->
                    val resolution = a["RESOLUTION"]?.split('x')
                    result.add(
                        Variant(
                            url = resolve(playlistUrl, line),
                            bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                            width = resolution?.getOrNull(0)?.toIntOrNull() ?: 0,
                            height = resolution?.getOrNull(1)?.toIntOrNull() ?: 0,
                        )
                    )
                }
                pending = null
            }
        }
        return result
    }

    /** Duración total en segundos (suma de los #EXTINF). */
    fun durationSeconds(text: String): Double =
        text.lineSequence().filter { it.startsWith("#EXTINF:") }
            .sumOf { it.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0 }

    /** True si usa un cifrado de pago (DRM) que no se puede descargar. */
    fun hasDrm(text: String): Boolean =
        text.lineSequence().filter { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-SESSION-KEY") }.any { line ->
            val a = attrs(line)
            val method = a["METHOD"]
            val format = a["KEYFORMAT"]
            method != null && method != "NONE" && (method != "AES-128" || (format != null && format != "identity"))
        }
}
