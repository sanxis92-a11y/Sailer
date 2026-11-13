package com.sailer.browser.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sailer.browser.adblock.AdBlocker
import com.sailer.browser.web.LoginWall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * Navegador oculto para el recolector: abre la página en segundo plano como si
 * la abrieras tú (con JavaScript, tu sesión y el bloqueador de anuncios), baja
 * por ella pulsando «Cargar más», y devuelve lo que ha quedado montado. Así se
 * leen las galerías que llegan «vacías» cuando se piden sin navegador.
 *
 * No se ve en pantalla, no abre ventanas nuevas y no carga las fotos en sí (solo
 * apunta sus direcciones), para ir rápido y gastar pocos datos.
 */
class HiddenReader(private val context: Context, private val userAgent: String) {

    /** La página tal como queda después de cargarla y bajar por ella. */
    class Rendered(val url: String, val title: String, val html: String, val media: List<GrabItem>)

    private var webView: WebView? = null
    private var finished: CompletableDeferred<Unit>? = null

    /** Lee [url]; [scrollSteps] pantallas como mucho. null si no carga a tiempo. */
    suspend fun read(url: String, scrollSteps: Int): Rendered? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            val view = webView ?: create().also { webView = it }
            val done = CompletableDeferred<Unit>()
            finished = done
            view.loadUrl(url)
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { done.await() }
            PageCollector.applyLate(view)
            delay(1500)
            evaluate(view, AutoScroll.start(scrollSteps, pauseMs = 700))
            val until = System.currentTimeMillis() + scrollSteps * 900L + 3000
            while (System.currentTimeMillis() < until) {
                delay(800)
                val status = runCatching { JSONObject(decode(evaluate(view, AutoScroll.STATUS)) ?: "{}") }.getOrNull()
                if (status?.optBoolean("done") == true) break
            }
            evaluate(view, AutoScroll.STOP)
            val scan = decode(evaluate(view, MediaSniffer.SCAN_SCRIPT))
            val html = decode(evaluate(view, "document.documentElement.outerHTML.slice(0, 3000000)")).orEmpty()
            val finalUrl = view.url?.takeIf { it.startsWith("https://") } ?: url
            val (title, media) = parseScan(scan, finalUrl)
            Rendered(finalUrl, title, html, media)
        }
    }

    /** Cierra el navegador oculto (llamar al terminar el recorrido). */
    suspend fun close() = withContext(Dispatchers.Main) {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = false
            // Sin ventanas emergentes: con esto y sin onCreateWindow, window.open no hace nada.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            // Las fotos no se descargan aquí: solo hace falta saber dónde están.
            blockNetworkImage = true
            userAgentString = userAgent
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme?.lowercase() != "https" ||
                    AdBlocker.shouldBlockNavigation(request.url, view.url?.let { android.net.Uri.parse(it).host })

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                    ?.let { android.net.Uri.parse(it).host }
                if (request.isForMainFrame || !AdBlocker.shouldBlock(request, host ?: request.url.host)) return null
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = Unit

            override fun onPageFinished(view: WebView, url: String) {
                if (url != "about:blank") finished?.complete(Unit)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
            }
        }
        PageCollector.install(view)
        LoginWall.install(view)
        // Tamaño de un móvil, para que la página se monte y se pueda bajar por ella.
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(720)
        val height = metrics.heightPixels.coerceAtLeast(1280)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return view
    }

    private suspend fun evaluate(view: WebView, js: String): String? = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(js) { if (cont.isActive) cont.resume(it) }
    }

    /** evaluateJavascript devuelve el resultado como texto JSON: "\"hola\"" → hola. */
    private fun decode(raw: String?): String? =
        raw?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }

    private fun parseScan(json: String?, pageUrl: String): Pair<String, List<GrabItem>> {
        val obj = runCatching { JSONObject(json ?: return "" to emptyList()) }.getOrNull() ?: return "" to emptyList()
        val title = obj.optString("title")
        val out = mutableListOf<GrabItem>()
        obj.optJSONArray("images")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val src = o.optString("src").takeIf { it.startsWith("https://") } ?: continue
                val kind = if (o.optInt("v") == 1) MediaKind.VIDEO else MediaKind.IMAGE
                out.add(GrabItem(src, kind, o.optInt("w"), o.optInt("h"), referer = pageUrl, album = title))
            }
        }
        obj.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val src = o.optString("src").takeIf { it.startsWith("https://") } ?: continue
                val kind = MediaSniffer.classify(src) ?: if (o.optBoolean("audio")) MediaKind.AUDIO else MediaKind.VIDEO
                if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) out.add(GrabItem(src, kind, referer = pageUrl, album = title))
            }
        }
        return title to out
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val TOTAL_TIMEOUT_MS = 90_000L
    }
}
