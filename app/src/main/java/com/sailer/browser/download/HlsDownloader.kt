package com.sailer.browser.download

import com.sailer.browser.Lang
import java.io.IOException

/**
 * Prepara la descarga de un vídeo HLS (.m3u8): elige la mejor calidad (o la
 * pedida), localiza la pista de audio si va aparte y lista todos los trozos.
 * Los trozos con AES-128 (no es DRM) se descifran al bajarlos; los vídeos con
 * DRM (FairPlay, Widevine...) se rechazan.
 */
object HlsDownloader {

    class DrmException : IOException(Lang.t("Este vídeo está protegido con DRM y no se puede descargar.", "This video is DRM protected and can't be downloaded."))

    /**
     * @param url lista maestra o lista de una calidad concreta.
     * @param audioUrl lista de audio separada si ya se conoce.
     */
    fun plan(url: String, audioUrl: String?, fetchText: (String) -> String): Plan {
        val text = fetchText(url)
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException(Lang.t("No es una lista HLS válida", "Not a valid HLS playlist"))
        if (HlsPlaylist.isMaster(text)) {
            val variants = HlsPlaylist.variants(url, text)
            val best = variants.maxByOrNull { it.bandwidth } ?: throw IOException(Lang.t("No se encontró ninguna calidad de vídeo", "No video quality found"))
            val audio = best.audioGroup?.let { HlsPlaylist.audioRenditions(url, text)[it] }
            return plan(best.url, audio, fetchText)
        }
        val video = track(url, text)
        val audio = audioUrl?.takeIf { it != url }?.let { track(it, fetchText(it)) }
        return Plan(video, audio)
    }

    private fun track(playlistUrl: String, text: String): TrackPlan {
        val pieces = mutableListOf<Piece>()
        var sequence = 0L
        var keyUrl: String? = null
        var keyIv: ByteArray? = null
        var mapPiece: Piece? = null
        var lastMap: Piece? = null
        var rangeLength = -1L
        var rangeOffset = -1L
        var nextOffset = 0L
        var extension = "ts"
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = HlsPlaylist.attrs(line)
                    val format = a["KEYFORMAT"]
                    when {
                        a["METHOD"] == "NONE" -> { keyUrl = null; keyIv = null }
                        a["METHOD"] == "AES-128" && (format == null || format == "identity") -> {
                            keyUrl = HlsPlaylist.resolve(playlistUrl, a["URI"] ?: throw IOException(Lang.t("Clave HLS sin dirección", "HLS key without address")))
                            keyIv = a["IV"]?.let(::parseHexIv)
                        }
                        else -> throw DrmException()
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = HlsPlaylist.attrs(line)
                    val uri = a["URI"] ?: return@forEach
                    val range = a["BYTERANGE"]?.let { parseRange(it, 0) }
                    mapPiece = Piece(
                        url = HlsPlaylist.resolve(playlistUrl, uri),
                        rangeStart = range?.first ?: -1,
                        rangeEnd = range?.let { it.first + it.second - 1 } ?: -1,
                    )
                    extension = "mp4"
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val range = parseRange(line.substringAfter(':'), nextOffset)
                    rangeOffset = range.first
                    rangeLength = range.second
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val map = mapPiece
                    if (map != null && map != lastMap) {
                        pieces.add(map)
                        lastMap = map
                    }
                    val iv = keyIv ?: if (keyUrl != null) ivFromSequence(sequence) else null
                    pieces.add(
                        Piece(
                            url = HlsPlaylist.resolve(playlistUrl, line),
                            rangeStart = if (rangeLength > 0) rangeOffset else -1,
                            rangeEnd = if (rangeLength > 0) rangeOffset + rangeLength - 1 else -1,
                            keyUrl = keyUrl,
                            iv = iv,
                        )
                    )
                    nextOffset = if (rangeLength > 0) rangeOffset + rangeLength else 0L
                    rangeLength = -1
                    sequence++
                }
            }
        }
        if (pieces.isEmpty()) throw IOException(Lang.t("La lista HLS está vacía", "The HLS playlist is empty"))
        // Audio "empaquetado" (.aac, .mp3...) sin contenedor.
        val firstExt = com.sailer.browser.media.MediaSniffer.extensionOf(pieces.last().url)
        if (extension == "ts" && firstExt in setOf("aac", "mp3", "ac3", "ec3")) extension = firstExt!!
        return TrackPlan(pieces, extension)
    }

    private fun parseRange(value: String, defaultOffset: Long): Pair<Long, Long> {
        val parts = value.trim().trim('"').split('@')
        val length = parts[0].toLong()
        val offset = parts.getOrNull(1)?.toLong() ?: defaultOffset
        return offset to length
    }

    private fun parseHexIv(value: String): ByteArray {
        val hex = value.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun ivFromSequence(sequence: Long): ByteArray =
        java.nio.ByteBuffer.allocate(16).putLong(0L).putLong(sequence).array()
}
