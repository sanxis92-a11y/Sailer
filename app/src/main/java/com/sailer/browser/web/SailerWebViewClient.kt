package com.sailer.browser.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

class SailerWebViewClient(
    private val requestFilter: RequestFilter,
    private val callbacks: Callbacks,
) : WebViewClient() {

    interface Callbacks {
        fun onPageStarted(view: WebView, url: String)
        fun onPageFinished(view: WebView, url: String)
        fun onBlocked(view: WebView, reason: BlockReason)

        /** Se llama (en segundo plano) por cada recurso que carga la página. */
        fun onResourceRequest(view: WebView, request: WebResourceRequest)

        /** El motor de la página se ha cerrado (falta de memoria o fallo de la web). */
        fun onRendererGone(view: WebView)
    }

    enum class BlockReason { INSECURE_HTTP, BAD_CERTIFICATE }

    /** Web que se está viendo (se actualiza en cuanto empieza a cargar). */
    @Volatile
    var pageHost: String? = null
        private set

    /** Anuncios y rastreadores bloqueados en la página actual. */
    val blockedCount = AtomicInteger(0)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (request.url.scheme?.lowercase()) {
            // Redirecciones a webs de publicidad (pop-unders): bloqueadas.
            // Pinterest y similares: no dejar que te lleven solos a «Regístrate» sin tocar nada.
            "https" -> LoginWall.isForcedWall(request) || requestFilter.shouldBlockNavigation(request.url, pageHost).also { blocked ->
                if (blocked) blockedCount.incrementAndGet()
            }
            "about", "data", "blob" -> false
            "http" -> {
                // Forzar HTTPS: reintentar la misma dirección de forma segura.
                if (request.isForMainFrame) view.loadUrl(UrlUtils.upgradeToHttps(url))
                true
            }
            // intent://, market://, tel:... no se abren otras apps desde aquí.
            else -> true
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) {
            pageHost = request.url.host
            blockedCount.set(0)
        }
        if (!requestFilter.shouldBlock(request, pageHost)) {
            callbacks.onResourceRequest(view, request)
            return null
        }
        blockedCount.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val host = android.net.Uri.parse(url).host
        if (host != null && host != pageHost) {
            pageHost = host
            blockedCount.set(0)
        }
        callbacks.onPageStarted(view, url)
    }

    /**
     * Sin esto, cuando una web pesada hace caer el motor de páginas, Android
     * cierra toda la app. Así solo se recarga esa pestaña.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        callbacks.onRendererGone(view)
        return true
    }

    override fun onPageFinished(view: WebView, url: String) {
        callbacks.onPageFinished(view, url)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Nunca aceptar certificados no válidos.
        handler.cancel()
        callbacks.onBlocked(view, BlockReason.BAD_CERTIFICATE)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame && request.url.scheme.equals("http", ignoreCase = true)) {
            callbacks.onBlocked(view, BlockReason.INSECURE_HTTP)
        }
    }
}
