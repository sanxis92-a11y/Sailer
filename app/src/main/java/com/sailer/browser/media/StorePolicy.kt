package com.sailer.browser.media

import android.net.Uri
import com.sailer.browser.BuildConfig

/**
 * Normas de la versión de Google Play: no se descargan vídeos de YouTube ni
 * de plataformas de pago, y (si STREAM_DOWNLOADS es false) tampoco vídeos
 * por trozos (HLS/DASH). La versión completa no tiene ninguna limitación.
 */
object StorePolicy {

    private val isPlay = BuildConfig.FLAVOR == "play"

    /** Webs cuyos vídeos no se pueden descargar en la versión de Play. */
    private val BLOCKED_HOSTS = setOf(
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtube-nocookie.com",
        "netflix.com", "nflxvideo.net", "primevideo.com", "amazonvideo.com", "disneyplus.com",
        "hbomax.com", "max.com", "spotify.com", "scdn.co", "tiktok.com", "tiktokcdn.com",
        "instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "twitch.tv",
        "vimeo.com", "vimeocdn.com", "dailymotion.com",
    )

    fun allowsKind(kind: MediaKind): Boolean =
        BuildConfig.STREAM_DOWNLOADS || (kind != MediaKind.HLS && kind != MediaKind.DASH)

    /** ¿Se puede descargar esta dirección vista desde esta página? */
    fun allowsDownload(url: String, pageUrl: String?): Boolean =
        !isPlay || (!isBlocked(url) && !isBlocked(pageUrl))

    private fun isBlocked(url: String?): Boolean {
        val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.lowercase() ?: return false
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
