package com.sailer.browser.tabs

import android.webkit.WebView
import com.sailer.browser.media.DetectedMedia

class Tab(val id: Long, val webView: WebView) {
    var title: String = ""
    var url: String = ""
    var progress: Int = 100

    /** Vídeos/audios detectados en la página actual (solo hilo principal). */
    val media = LinkedHashMap<String, DetectedMedia>()
}
