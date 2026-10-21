package com.sailer.browser.web

/** Recibe cada archivo que una web intenta descargar. */
fun interface DownloadHandler {
    fun onDownloadRequested(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
    )
}
