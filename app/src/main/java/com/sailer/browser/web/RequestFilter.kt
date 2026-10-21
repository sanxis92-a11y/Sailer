package com.sailer.browser.web

import android.webkit.WebResourceRequest

/**
 * Punto de enganche para el bloqueador de anuncios, rastreadores y pop-ups
 * (paso 2). Se consulta para cada recurso que carga una web.
 */
fun interface RequestFilter {
    /** Devuelve true si la petición debe bloquearse. */
    fun shouldBlock(request: WebResourceRequest): Boolean

    companion object {
        /** De momento no se bloquea nada. */
        val ALLOW_ALL = RequestFilter { false }
    }
}
