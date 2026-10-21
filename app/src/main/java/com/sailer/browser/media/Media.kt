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

    /**
     * JavaScript que describe la página: título, imagen de portada y cada
     * vídeo/audio con su resolución, duración y miniatura.
     */
    const val SCAN_SCRIPT = """
        (function() {
          function meta(p) { var m = document.querySelector('meta[property="' + p + '"]'); return m ? m.content : ''; }
          var items = [];
          document.querySelectorAll('video, audio').forEach(function(e) {
            var srcs = [];
            if (e.currentSrc) srcs.push(e.currentSrc);
            if (e.src) srcs.push(e.src);
            e.querySelectorAll('source').forEach(function(s) { if (s.src) srcs.push(s.src); });
            if (srcs.length === 0) srcs.push('');
            srcs.forEach(function(s) {
              items.push({ src: s, w: e.videoWidth || 0, h: e.videoHeight || 0,
                d: isFinite(e.duration) ? e.duration : 0, poster: e.poster || '', audio: e.tagName === 'AUDIO' });
            });
          });
          ['og:video', 'og:video:url', 'og:video:secure_url'].forEach(function(p) {
            var v = meta(p);
            if (v) items.push({ src: v, w: 0, h: 0, d: 0, poster: '', audio: false });
          });
          return JSON.stringify({ title: document.title, image: meta('og:image'), items: items });
        })();
    """
}

/** Un vídeo/audio tal como lo ve la página (resolución, duración, miniatura). */
data class MediaElementInfo(
    val src: String,
    val width: Int,
    val height: Int,
    val duration: Double,
    val poster: String,
    val isAudio: Boolean,
)

data class PageMediaInfo(
    val title: String,
    val image: String,
    val elements: List<MediaElementInfo>,
)
