package com.sailer.browser.tabs

import android.webkit.WebView
import com.sailer.browser.media.DetectedMedia

class Tab(val id: Long, val webView: WebView) {
    var title: String = ""
    var url: String = ""
    var progress: Int = 100

    /** Vídeos/audios detectados en la página actual (solo hilo principal). */
    val media = LinkedHashMap<String, DetectedMedia>()

    /**
     * Imágenes de la propia web que ha cargado la página (solo hilo principal).
     * Así no se pierden las que una web con scroll infinito ya ha quitado de la pantalla.
     */
    val seenImages = LinkedHashSet<String>()
}
