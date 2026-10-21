package com.sailer.browser.download

/** Un trozo que hay que descargar (segmento HLS/DASH o archivo completo). */
data class Piece(
    val url: String,
    /** Rango de bytes "inicio-fin" (incluido) o null para todo. */
    val rangeStart: Long = -1,
    val rangeEnd: Long = -1,
    /** Clave AES-128 de HLS (dirección) e IV, si el trozo viene cifrado. */
    val keyUrl: String? = null,
    val iv: ByteArray? = null,
)

/** Una pista (vídeo o audio) formada por trozos que se unen en orden. */
data class TrackPlan(
    val pieces: List<Piece>,
    /** Extensión del resultado: "ts", "mp4", "webm", "m4a"... */
    val extension: String,
)

/** Lo que hay que bajar para una descarga: vídeo y, si va aparte, audio. */
data class Plan(
    val video: TrackPlan,
    val audio: TrackPlan? = null,
) {
    val totalPieces: Int get() = video.pieces.size + (audio?.pieces?.size ?: 0)
}
