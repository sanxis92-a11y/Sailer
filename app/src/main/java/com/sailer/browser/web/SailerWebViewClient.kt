package com.sailer.browser.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

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
    }

    enum class BlockReason { INSECURE_HTTP, BAD_CERTIFICATE }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (request.url.scheme?.lowercase()) {
            // Redirecciones a webs de publicidad (pop-unders): bloqueadas.
            "https" -> requestFilter.shouldBlockNavigation(request.url)
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
        if (!requestFilter.shouldBlock(request)) {
            callbacks.onResourceRequest(view, request)
            return null
        }
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        callbacks.onPageStarted(view, url)
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
