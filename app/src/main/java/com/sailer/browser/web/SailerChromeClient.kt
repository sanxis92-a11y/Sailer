package com.sailer.browser.web

import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

class SailerChromeClient(private val callbacks: Callbacks) : WebChromeClient() {

    interface Callbacks {
        fun onProgress(view: WebView, progress: Int)
        fun onTitle(view: WebView, title: String)
        fun onEnterFullscreen(view: View, onExit: CustomViewCallback)
        fun onExitFullscreen()
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgress(view, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        callbacks.onTitle(view, title.orEmpty())
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
