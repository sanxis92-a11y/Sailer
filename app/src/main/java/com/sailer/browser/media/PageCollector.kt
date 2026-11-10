package com.sailer.browser.media

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sailer.browser.BuildConfig
import java.util.WeakHashMap

/**
 * «Memoria» de la página para webs con scroll infinito (Scrolller, Reddit,
 * redes sociales…). Esas webs borran de la pantalla las fotos que ya has
 * pasado y cargan las nuevas con JavaScript, así que al pulsar «Descargar»
 * solo quedarían las que se ven en ese momento.
 *
 * Este script se mete en cada página al empezar a cargar y va apuntando,
 * mientras bajas, todas las imágenes y vídeos que aparecen:
 *  - las etiquetas <img>, <picture>, <video> (y la versión más grande de srcset);
 *  - las direcciones que llegan en las respuestas JSON de la web (ahí suele
 *    venir la foto original a tamaño completo, no solo la miniatura).
 * [MediaSniffer.SCAN_SCRIPT] lee esa lista al abrir el analizador.
 * Solo en la versión personal (GRABBER).
 */
object PageCollector {

    private val handlers = WeakHashMap<WebView, ScriptHandler?>()

    private val supportsDocumentStart: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun install(webView: WebView) {
        if (!BuildConfig.GRABBER || !supportsDocumentStart) return
        handlers.remove(webView)?.remove()
        handlers[webView] = WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf("*"))
    }

    /** WebViews antiguos sin scripts de inicio: se pone al terminar de cargar (no ve lo anterior). */
    fun applyLate(webView: WebView) {
        if (!BuildConfig.GRABBER || supportsDocumentStart) return
        webView.evaluateJavascript(SCRIPT, null)
    }

    /** Nombre (poco llamativo) donde la página guarda lo visto. */
    const val STORE = "__nvSeen"

    private val SCRIPT = """
        (function() {
          if (window.top !== window || window.$STORE) return;
          var seen = {}, list = [], MAX = 5000;
          try { Object.defineProperty(window, '$STORE', { value: list, enumerable: false }); } catch (e) { return; }
          var MEDIA = /\.(jpe?g|png|gif|webp|avif|bmp|heic|mp4|webm|m4v|mov|mp3|m4a)(\?|#|$)/i;
          function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return ''; } }
          function add(u, w, h, v) {
            if (!u || list.length >= MAX) return;
            u = abs(u);
            if (u.indexOf('https://') !== 0) return;
            var old = seen[u];
            if (old) { if (w && h && !old.w) { old.w = w; old.h = h; } return; }
            var item = { src: u, w: w || 0, h: h || 0, v: v ? 1 : 0 };
            seen[u] = item;
            list.push(item);
          }
          function largest(srcset) {
            var best = '', bestW = 0;
            (srcset || '').split(',').forEach(function(part) {
              var bits = part.trim().split(/\s+/);
              var w = parseFloat(bits[1]) || 1;
              if (bits[0] && w >= bestW) { best = bits[0]; bestW = w; }
            });
            return best;
          }
          function img(e) {
            var w = e.naturalWidth || 0, h = e.naturalHeight || 0;
            if (w && h && (w < 120 || h < 120)) {
              // Iconos y botones: se olvidan aunque se hubieran apuntado antes de cargar.
              var o = seen[abs(e.currentSrc || e.src)];
              if (o) o.small = 1;
              return;
            }
            var link = e.closest && e.closest('a');
            if (link && MEDIA.test(link.href || '')) add(link.href, 0, 0, /\.(mp4|webm|m4v|mov)(\?|#|$)/i.test(link.href));
            var s = largest(e.getAttribute('srcset') || e.getAttribute('data-srcset'));
            if (s) add(s, 0, 0);
            var lazy = e.getAttribute('data-src') || e.getAttribute('data-original') || e.getAttribute('data-lazy-src');
            if (lazy) add(lazy, 0, 0);
            var c = e.currentSrc || e.src;
            if (c && c.indexOf('data:') !== 0 && c.indexOf('blob:') !== 0) add(c, w, h);
            var p = e.parentElement;
            if (p && p.tagName === 'PICTURE') p.querySelectorAll('source').forEach(function(x) {
              var b = largest(x.getAttribute('srcset') || x.getAttribute('data-srcset'));
              if (b) add(b, 0, 0);
            });
          }
          function video(e) {
            [e.currentSrc, e.src].forEach(function(s) { if (s && s.indexOf('blob:') !== 0) add(s, e.videoWidth, e.videoHeight, true); });
            e.querySelectorAll('source').forEach(function(s) { if (s.src) add(s.src, 0, 0, true); });
            if (e.poster) add(e.poster, 0, 0);
          }
          function scan(root) {
            if (!root || !root.querySelectorAll) return;
            if (root.tagName === 'IMG') img(root);
            if (root.tagName === 'VIDEO') video(root);
            root.querySelectorAll('img').forEach(img);
            root.querySelectorAll('video').forEach(video);
          }
          // Direcciones en las respuestas de la web (JSON de las galerías infinitas).
          var RAW = /https:\/\/[^\s"'<>()\\]+?\.(?:jpe?g|png|webp|gif|avif|mp4|webm|m4v)(?:\?[^\s"'<>()\\]*)?(?=["'\s<>)\\]|$)/gi;
          function text(t) {
            if (!t || t.length > 8000000) return;
            t = t.split('\\/').join('/').split('\\u002F').join('/');
            var m;
            RAW.lastIndex = 0;
            while ((m = RAW.exec(t)) !== null && list.length < MAX) add(m[0], 0, 0, /\.(mp4|webm|m4v)/i.test(m[0]));
          }
          function isJson(type) { return /json|javascript|graphql|text\/plain/i.test(type || ''); }
          try {
            var of = window.fetch;
            if (of) window.fetch = function() {
              var p = of.apply(this, arguments);
              p.then(function(r) {
                try { if (isJson(r.headers.get('content-type'))) r.clone().text().then(text, function() {}); } catch (e) {}
              }, function() {});
              return p;
            };
            var oo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function() {
              this.addEventListener('load', function() {
                try {
                  if ((this.responseType === '' || this.responseType === 'text') && isJson(this.getResponseHeader('content-type'))) text(this.responseText);
                } catch (e) {}
              });
              return oo.apply(this, arguments);
            };
          } catch (e) {}
          // Lo que aparece en pantalla mientras bajas.
          function start() {
            scan(document.documentElement);
            document.querySelectorAll('script[type="application/json"],script[type="application/ld+json"],script#__NEXT_DATA__').forEach(function(s) { text(s.textContent); });
            new MutationObserver(function(muts) {
              muts.forEach(function(m) {
                if (m.type === 'attributes') { scan(m.target); return; }
                m.addedNodes.forEach(function(n) { if (n.nodeType === 1) scan(n); });
              });
            }).observe(document.documentElement, { childList: true, subtree: true, attributes: true,
              attributeFilter: ['src', 'srcset', 'data-src', 'data-srcset', 'poster'] });
            document.addEventListener('load', function(ev) { var t = ev.target; if (t && t.tagName === 'IMG') img(t); }, true);
          }
          if (document.documentElement) start(); else document.addEventListener('DOMContentLoaded', start);
        })();
    """.trimIndent()
}
