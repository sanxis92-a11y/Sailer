package com.sailer.browser.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class SailerChromeClient(
    private val requestFilter: RequestFilter,
    private val callbacks: Callbacks,
) : WebChromeClient() {

    interface Callbacks {
        fun onProgress(view: WebView, progress: Int)
        fun onTitle(view: WebView, title: String)
        fun onEnterFullscreen(view: View, onExit: CustomViewCallback)
        fun onExitFullscreen()
        fun onOpenInNewTab(url: String)
        fun onPopupBlocked(view: WebView)
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgress(view, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        callbacks.onTitle(view, title.orEmpty())
    }

    /**
     * Ventanas nuevas (pop-ups y pestañas que abren las webs). Solo se permite
     * abrir en pestaña nueva un enlace que el usuario ha tocado de verdad y que
     * va justo a esa dirección; todo lo demás (publicidad que se abre al hacer
     * clic en cualquier sitio) se descarta. Para saber a dónde quiere ir la
     * ventana se usa un WebView "sonda" sin JavaScript que nunca se muestra.
     */
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        val hit = view.hitTestResult
        val tappedLink = hit.extra.takeIf { isUserGesture && hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE }
        val pageHost = view.url?.let { Uri.parse(it).host }
        val probe = WebView(view.context)
        probe.settings.javaScriptEnabled = false
        probe.webViewClient = object : WebViewClient() {
            private var decided = false

            fun decide(target: String) {
                if (decided || target.startsWith("about:")) return
                decided = true
                val allowed = tappedLink != null && sameAddress(target, tappedLink) &&
                    !requestFilter.shouldBlockNavigation(Uri.parse(target), pageHost)
                if (allowed) callbacks.onOpenInNewTab(UrlUtils.upgradeToHttps(target)) else callbacks.onPopupBlocked(view)
                probe.post { probe.destroy() }
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest): Boolean {
                decide(request.url.toString())
                return true
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                v.stopLoading()
                decide(url)
            }
        }
        // Si en 10 segundos no ha intentado ir a ningún sitio, se descarta.
        probe.postDelayed({ runCatching { probe.destroy() } }, 10_000)
        (resultMsg.obj as WebView.WebViewTransport).webView = probe
        resultMsg.sendToTarget()
        return true
    }

    private fun sameAddress(a: String, b: String): Boolean {
        fun norm(s: String) = UrlUtils.upgradeToHttps(s).trimEnd('/').substringBefore('#')
        return norm(a) == norm(b)
    }

    // Vídeos a pantalla completa.
    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        callbacks.onEnterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        callbacks.onExitFullscreen()
    }

    // Las webs no pueden pedir ubicación, cámara ni micrófono.
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback,
    ) {
        callback.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }
}
