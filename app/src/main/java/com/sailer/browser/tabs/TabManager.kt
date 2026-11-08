package com.sailer.browser.tabs

import android.webkit.WebView

/** Lleva la lista de pestañas abiertas y cuál se está viendo. */
class TabManager(
    private val createWebView: () -> WebView,
    private val homeHtml: String,
    private val listener: Listener,
) {

    interface Listener {
        fun onCurrentTabChanged(tab: Tab?)
        fun onTabsChanged(tabs: List<Tab>)
    }

    private val _tabs = mutableListOf<Tab>()
    val tabs: List<Tab> get() = _tabs

    var current: Tab? = null
        private set

    private var nextId = 1L

    fun findByWebView(webView: WebView): Tab? = _tabs.firstOrNull { it.webView === webView }

    fun newTab(url: String? = null): Tab {
        val tab = Tab(nextId++, createWebView())
        _tabs.add(tab)
        if (url.isNullOrEmpty()) {
            tab.webView.loadDataWithBaseURL(HOME_URL, homeHtml, "text/html", "utf-8", null)
        } else {
            tab.webView.loadUrl(url)
        }
        select(tab)
        listener.onTabsChanged(tabs)
        return tab
    }

    fun select(tab: Tab) {
        if (current === tab) return
        current?.webView?.onPause()
        current = tab
        tab.webView.onResume()
        listener.onCurrentTabChanged(tab)
    }

    fun close(tab: Tab) {
        val index = _tabs.indexOf(tab)
        if (index < 0) return
        _tabs.removeAt(index)
        if (current === tab) {
            current = null
            val next = _tabs.getOrNull(index) ?: _tabs.lastOrNull()
            if (next != null) select(next) else listener.onCurrentTabChanged(null)
        }
        destroy(tab.webView)
        listener.onTabsChanged(tabs)
    }

    /**
     * Cambia el WebView de una pestaña cuyo motor se ha cerrado por uno nuevo
     * y vuelve a abrir la misma dirección (o el inicio, si [reload] es false).
     */
    fun replaceCrashed(webView: WebView, reload: Boolean): Tab? {
        val index = _tabs.indexOfFirst { it.webView === webView }
        if (index < 0) return null
        val old = _tabs[index]
        val tab = Tab(old.id, createWebView()).apply { title = old.title; url = old.url }
        _tabs[index] = tab
        if (!reload || tab.url.isBlank() || tab.url == HOME_URL) {
            tab.url = ""
            tab.webView.loadDataWithBaseURL(HOME_URL, homeHtml, "text/html", "utf-8", null)
        } else {
            tab.webView.loadUrl(tab.url)
        }
        if (current === old) {
            current = tab
            listener.onCurrentTabChanged(tab)
        } else {
            tab.webView.onPause()
        }
        destroy(old.webView)
        return tab
    }

    /** Cierra todas las pestañas y devuelve sus WebView aún sin destruir. */
    fun detachAll(): List<WebView> {
        val webViews = _tabs.map { it.webView }
        _tabs.clear()
        current = null
        return webViews
    }

    companion object {
        const val HOME_URL = "about:blank"

        fun destroy(webView: WebView) {
            webView.stopLoading()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
}
