package com.sailer.browser.tabs

import android.webkit.WebView

class Tab(val id: Long, val webView: WebView) {
    var title: String = ""
    var url: String = ""
    var progress: Int = 100
}
