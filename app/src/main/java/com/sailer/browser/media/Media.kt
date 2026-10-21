package com.sailer.browser.media

import android.net.Uri

enum class MediaKind { VIDEO, AUDIO, IMAGE, HLS, FILE }

data class DetectedMedia(val url: String, val kind: MediaKind)

/** Reconoce direcciones de vídeo, audio y listas HLS por su extensión. */
object MediaSniffer {

    private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "avi", "flv")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic")

    fun extensionOf(url: String): String? {
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: return null
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return null
        return name.substringAfterLast('.').lowercase().takeIf { it.length in 2..5 }
    }

    /** Devuelve el tipo de medio o null si no parece un vídeo/audio/HLS. */
    fun classify(url: String): MediaKind? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        return when (extensionOf(url)) {
            "m3u8" -> MediaKind.HLS
            in VIDEO_EXT -> MediaKind.VIDEO
            in AUDIO_EXT -> MediaKind.AUDIO
            else -> null
        }
    }

    fun kindForExtension(ext: String?): MediaKind? = when (ext?.lowercase()) {
        "m3u8" -> MediaKind.HLS
        "ts" -> MediaKind.VIDEO
        in VIDEO_EXT -> MediaKind.VIDEO
        in AUDIO_EXT -> MediaKind.AUDIO
        in IMAGE_EXT -> MediaKind.IMAGE
        else -> null
    }

    fun kindForMime(mime: String?): MediaKind? = when {
        mime == null -> null
        mime.contains("mpegurl", ignoreCase = true) -> MediaKind.HLS
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        mime.startsWith("image/") -> MediaKind.IMAGE
        else -> null
    }

    /** JavaScript que lista los vídeos y audios presentes en la página. */
    const val SCAN_SCRIPT = """
        (function() {
          var r = [];
          document.querySelectorAll('video, audio, video source, audio source').forEach(function(e) {
            var s = e.currentSrc || e.src;
            if (s) r.push(s);
          });
          document.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"]').forEach(function(m) {
            if (m.content) r.push(m.content);
          });
          return JSON.stringify(r);
        })();
    """
}
