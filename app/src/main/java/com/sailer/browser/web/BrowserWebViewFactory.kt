package com.sailer.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Crea WebViews con una configuración pensada para la privacidad. */
class BrowserWebViewFactory(
    private val context: Context,
    private val requestFilter: RequestFilter,
    private val downloadHandler: DownloadHandler,
) {

    init {
        WebView.setWebContentsDebuggingEnabled(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(callbacks: SailerWebViewClient.Callbacks, chromeClient: SailerChromeClient): WebView {
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // muchas webs lo necesitan; se borra al cerrar
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = false
            // Sin ventanas emergentes: window.open() no abre ventanas nuevas.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            @Suppress("DEPRECATION")
            saveFormData = false
            // Quitar la marca "wv" para parecer un Chrome normal: algunas webs
            // bloquean los WebView y además es un dato más para identificarte.
            userAgentString = userAgentString.replace("; wv", "")
        }
        // No decirle a las webs qué app las está abriendo (cabecera X-Requested-With).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = SailerWebViewClient(requestFilter, callbacks)
        webView.webChromeClient = chromeClient
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            downloadHandler.onDownloadRequested(url, userAgent, contentDisposition, mimeType, contentLength)
        }
        return webView
    }
}
