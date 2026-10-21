package com.sailer.browser.adblock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * Bloqueo "cosmético": oculta dentro de la página los huecos, banners y
 * capas de anuncios que no se pueden bloquear por dominio. Usa las reglas
 * de ocultación de EasyList y EasyList Adult.
 */
object CosmeticFilter {

    private val main = Handler(Looper.getMainLooper())
    private val handlers = WeakHashMap<WebView, ScriptHandler?>()

    @Volatile
    private var generic: List<String> = emptyList()

    /** Reglas por web: dominio -> selectores a ocultar. */
    @Volatile
    private var specific: Map<String, List<String>> = emptyMap()

    /** Excepciones: dominio -> selectores que NO se deben ocultar allí. */
    @Volatile
    private var exceptions: Map<String, List<String>> = emptyMap()

    @Volatile
    private var script: String? = null

    val supportsDocumentStart: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun load(context: Context) {
        val appContext = context.applicationContext
        thread(name = "cosmetic-load") {
            generic = appContext.assets.open("cosmetic_generic.txt").bufferedReader()
                .readLines().filter { it.isNotBlank() }
            val hide = HashMap<String, MutableList<String>>()
            val allow = HashMap<String, MutableList<String>>()
            appContext.assets.open("cosmetic_specific.txt").bufferedReader().forEachLine { line ->
                val isException = line.contains("#@#")
                val separator = if (isException) "#@#" else "##"
                val domains = line.substringBefore(separator)
                val selector = line.substringAfter(separator)
                if (domains.isEmpty() || selector.isEmpty()) return@forEachLine
                val target = if (isException) allow else hide
                domains.split(',').forEach { target.getOrPut(it) { mutableListOf() }.add(selector) }
            }
            specific = hide
            exceptions = allow
            rebuildScript()
        }
    }

    /** Vuelve a generar el script (al cambiar las webs permitidas) y lo aplica a todas las pestañas. */
    fun rebuildScript() {
        if (generic.isEmpty()) return
        script = buildScript()
        main.post { handlers.keys.toList().forEach { install(it) } }
    }

    /** Instala el filtro en un WebView (se ejecuta al empezar a cargar cada página). */
    fun install(webView: WebView) {
        handlers.remove(webView)?.remove()
        val js = script
        if (js == null || !supportsDocumentStart) {
            handlers[webView] = null
            return
        }
        handlers[webView] = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
    }

    fun uninstall(webView: WebView) {
        handlers.remove(webView)?.remove()
    }

    /** Para WebViews antiguos sin scripts de inicio: aplicar al terminar de cargar. */
    fun applyLate(webView: WebView) {
        if (supportsDocumentStart) return
        script?.let { webView.evaluateJavascript(it, null) }
    }

    private fun buildScript(): String {
        val allowedSites = JSONArray(AdBlocker.allowedSites.toList())
        val enabled = AdBlocker.enabled
        return """
            (function() {
              if (window.top !== window || window.__sailerCosmetic) return;
              window.__sailerCosmetic = true;
              if (!$enabled) return;
              var h = location.hostname.toLowerCase();
              function on(d) { return h === d || h.slice(-(d.length + 1)) === '.' + d; }
              var allowed = $allowedSites;
              for (var i = 0; i < allowed.length; i++) if (on(allowed[i])) return;
              var G = ${JSONArray(generic)};
              var S = ${JSONObject(specific as Map<*, *>)};
              var X = ${JSONObject(exceptions as Map<*, *>)};
              var skip = {}, extra = [];
              var parts = h.split('.');
              for (var j = 0; j < parts.length - 1; j++) {
                var d = parts.slice(j).join('.');
                if (X[d]) X[d].forEach(function(s) { skip[s] = 1; });
                if (S[d]) extra = extra.concat(S[d]);
              }
              var all = G.concat(extra), css = [];
              for (var k = 0; k < all.length; k++) if (!skip[all[k]]) css.push(all[k] + '{display:none!important}');
              var style = document.createElement('style');
              style.textContent = css.join('\n');
              (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
    }
}
