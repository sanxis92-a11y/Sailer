package com.sailer.browser.web

import android.content.Context
import android.widget.Toast
import com.sailer.browser.R

/**
 * Punto de enganche para el descargador de medios (paso 3): recibe cada
 * archivo que una web intenta descargar.
 */
fun interface DownloadHandler {
    fun onDownloadRequested(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
    )

    companion object {
        /** Provisional: avisa de que las descargas aún no están disponibles. */
        fun notYetAvailable(context: Context) = DownloadHandler { _, _, _, _, _ ->
            Toast.makeText(context, R.string.downloads_soon, Toast.LENGTH_SHORT).show()
        }
    }
}
