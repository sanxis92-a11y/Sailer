package com.sailer.browser.web

import android.net.Uri
import android.webkit.WebResourceRequest

/**
 * Decide qué cargas se bloquean. Lo implementa el bloqueador de anuncios
 * ([com.sailer.browser.adblock.AdBlocker]).
 */
fun interface RequestFilter {
    /** Devuelve true si un recurso de la página (imagen, script...) debe bloquearse. */
    fun shouldBlock(request: WebResourceRequest): Boolean

    /** Devuelve true si una navegación (clic, redirección) debe bloquearse. */
    fun shouldBlockNavigation(url: Uri): Boolean = false

    companion object {
        val ALLOW_ALL = RequestFilter { false }
    }
}
