package com.sailer.browser.web

import android.net.Uri
import android.webkit.WebResourceRequest

/**
 * Decide qué cargas se bloquean. Lo implementa el bloqueador de anuncios
 * ([com.sailer.browser.adblock.AdBlocker]). [pageHost] es la web que se está viendo.
 */
fun interface RequestFilter {
    /** Devuelve true si un recurso de la página (imagen, script...) debe bloquearse. */
    fun shouldBlock(request: WebResourceRequest, pageHost: String?): Boolean

    /** Devuelve true si una navegación (clic, redirección) debe bloquearse. */
    fun shouldBlockNavigation(url: Uri, pageHost: String?): Boolean = false

    companion object {
        val ALLOW_ALL = RequestFilter { _, _ -> false }
    }
}
