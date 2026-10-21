package com.sailer.browser.web

import android.net.Uri
import android.util.Patterns

object UrlUtils {

    private const val SEARCH_URL = "https://duckduckgo.com/?q="

    /**
     * Convierte lo que el usuario escribe en una dirección segura:
     *  - "ejemplo.com"        -> "https://ejemplo.com"
     *  - "http://ejemplo.com" -> "https://ejemplo.com"
     *  - "gatos graciosos"    -> búsqueda en DuckDuckGo
     */
    fun fromUserInput(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        return when {
            lower.startsWith("https://") -> text
            lower.startsWith("http://") -> upgradeToHttps(text)
            !text.contains(' ') && Patterns.WEB_URL.matcher(text).matches() -> "https://$text"
            else -> SEARCH_URL + Uri.encode(text)
        }
    }

    fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /** Lo que se muestra en la barra de direcciones (vacío para páginas internas). */
    fun forDisplay(url: String?): String =
        if (url == null || url.startsWith("about:") || url.startsWith("data:")) "" else url
}
