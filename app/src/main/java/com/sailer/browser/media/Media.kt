package com.sailer.browser.media

import android.net.Uri

enum class MediaKind { VIDEO, AUDIO, IMAGE, HLS, DASH, FILE }

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
            "mpd" -> MediaKind.DASH
            in VIDEO_EXT -> MediaKind.VIDEO
            in AUDIO_EXT -> MediaKind.AUDIO
            else -> null
        }
    }

    fun kindForExtension(ext: String?): MediaKind? = when (ext?.lowercase()) {
        "m3u8" -> MediaKind.HLS
        "mpd" -> MediaKind.DASH
        "ts" -> MediaKind.VIDEO
        in VIDEO_EXT -> MediaKind.VIDEO
        in AUDIO_EXT -> MediaKind.AUDIO
        in IMAGE_EXT -> MediaKind.IMAGE
        else -> null
    }

    fun kindForMime(mime: String?): MediaKind? = when {
        mime == null -> null
        mime.contains("mpegurl", ignoreCase = true) -> MediaKind.HLS
        mime.contains("dash+xml", ignoreCase = true) -> MediaKind.DASH
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        mime.startsWith("image/") -> MediaKind.IMAGE
        else -> null
    }

    /**
     * JavaScript que describe la página: título, imagen de portada, cada
     * vídeo/audio con su resolución, duración y miniatura, y las imágenes
     * (sin iconos pequeños; de cada una, la versión más grande que ofrece, y si
     * una miniatura enlaza a la foto a tamaño completo, esa foto).
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
          var images = [], seen = {};
          function addImage(u, w, h) {
            if (!u || u.indexOf('https://') !== 0 || seen[u] || images.length >= 1500) return;
            seen[u] = true;
            images.push({ src: u, w: w || 0, h: h || 0 });
          }
          function largest(srcset) {
            var best = '', bestW = 0;
            (srcset || '').split(',').forEach(function(part) {
              var bits = part.trim().split(/\s+/);
              var w = parseFloat(bits[1]) || 1;
              if (bits[0] && w >= bestW) { best = bits[0]; bestW = w; }
            });
            if (!best) return '';
            try { return new URL(best, document.baseURI).href; } catch (e) { return ''; }
          }
          function isImageLink(u) {
            return /\.(jpe?g|png|gif|webp|bmp|avif|heic)(\?|#|$)/i.test(u || '');
          }
          function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return ''; } }
          var details = [], seenDetail = {};
          addImage(meta('og:image'), 0, 0);
          document.querySelectorAll('img').forEach(function(e) {
            // Como JDownloader: si la miniatura enlaza a la foto grande, se usa la grande.
            var link = e.closest('a');
            if (link && isImageLink(link.href)) { addImage(link.href, 0, 0); return; }
            // Si enlaza a otra página de la misma web, esa página tiene la foto grande (análisis a fondo).
            if (link && link.href && link.origin === location.origin && link.href.split('#')[0] !== location.href.split('#')[0]) {
              var d = link.href.split('#')[0];
              if (!seenDetail[d] && details.length < 300) { seenDetail[d] = true; details.push(d); }
            }
            var w = e.naturalWidth || e.width, h = e.naturalHeight || e.height;
            if (w && h && (w < 120 || h < 120)) return;
            var lazy = e.getAttribute('data-src') || e.getAttribute('data-original') || e.getAttribute('data-lazy-src');
            var src = largest(e.getAttribute('srcset') || e.getAttribute('data-srcset')) || e.currentSrc || e.src;
            if ((!src || src.indexOf('data:') === 0) && lazy) {
              try { src = new URL(lazy, document.baseURI).href; } catch (err) {}
            }
            addImage(src, w, h);
          });
          document.querySelectorAll('a[href]').forEach(function(a) { if (isImageLink(a.href)) addImage(a.href, 0, 0); });
          // <picture> con varias versiones: la más grande.
          document.querySelectorAll('picture source').forEach(function(s) {
            addImage(largest(s.getAttribute('srcset') || s.getAttribute('data-srcset')), 0, 0);
          });
          // Atributos de carga diferida, zoom o alta resolución.
          var dataAttrs = ['data-src', 'data-original', 'data-full', 'data-large', 'data-zoom', 'data-zoom-image',
            'data-hires', 'data-big', 'data-image', 'data-lazy', 'data-lazy-src', 'data-url', 'data-bg',
            'data-background', 'data-file', 'data-high-res', 'data-orig'];
          document.querySelectorAll('[' + dataAttrs.join('],[') + ']').forEach(function(e) {
            dataAttrs.forEach(function(name) { var v = e.getAttribute(name); if (v && isImageLink(v)) addImage(abs(v), 0, 0); });
          });
          // Fondos CSS (muchas galerías ponen las fotos como fondo de un div).
          var all = document.querySelectorAll('body *');
          for (var i = 0; i < all.length && i < 4000; i++) {
            var bg = getComputedStyle(all[i]).backgroundImage;
            if (bg && bg.indexOf('url(') >= 0) {
              var m = bg.match(/url\(["']?([^"')]+)["']?\)/);
              if (m && m[1].indexOf('data:') !== 0) {
                var r = all[i].getBoundingClientRect();
                if (r.width >= 120 && r.height >= 120) addImage(abs(m[1]), 0, 0);
              }
            }
          }
          // Direcciones escondidas en scripts o JSON (galerías montadas con JavaScript).
          var html = document.documentElement.innerHTML.slice(0, 3000000).split('\\/').join('/');
          var re = /https:\/\/[^\s"'<>()\\]+?\.(?:jpe?g|png|webp|gif|avif)(?:\?[^\s"'<>()\\]*)?(?=["'\s<>)\\])/gi, found;
          while ((found = re.exec(html)) !== null && images.length < 1500) addImage(found[0], 0, 0);
          // Lo que PageCollector fue apuntando mientras bajabas (webs con scroll infinito
          // que quitan de la pantalla las fotos ya vistas, como Scrolller).
          var mem = window.__nvSeen || [];
          for (var k = 0; k < mem.length && images.length < 3000; k++) {
            var it = mem[k];
            if (it.small) continue;
            if (it.v) { if (!seen[it.src]) { seen[it.src] = true; images.push({ src: it.src, w: it.w, h: it.h, v: 1 }); } }
            else if (seen[it.src]) { images.forEach(function(x) { if (x.src === it.src && !x.w && it.w) { x.w = it.w; x.h = it.h; } }); }
            else { seen[it.src] = true; images.push({ src: it.src, w: it.w, h: it.h }); }
          }
          return JSON.stringify({ title: document.title, image: meta('og:image'), items: items, images: images, details: details });
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

/** Una imagen de la página con su tamaño real en píxeles (0 si aún no se conoce). */
data class PageImage(val url: String, val width: Int, val height: Int, val isVideo: Boolean = false)

data class PageMediaInfo(
    val title: String,
    val image: String,
    val elements: List<MediaElementInfo>,
    val images: List<PageImage> = emptyList(),
    /** Páginas de detalle a las que enlazan las miniaturas (para el análisis a fondo). */
    val details: List<String> = emptyList(),
)
