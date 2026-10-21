package com.sailer.browser.adblock

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import com.sailer.browser.web.RequestFilter
import kotlin.concurrent.thread

/**
 * Bloquea anuncios, rastreadores y redirecciones a webs de publicidad
 * comparando cada dirección con una lista de ~70.000 dominios
 * (lista "StevenBlack hosts", licencia MIT) incluida en la app.
 */
object AdBlocker : RequestFilter {

    @Volatile
    private var hosts: Set<String> = emptySet()

    @Volatile
    var enabled = true

    /** Carga la lista en segundo plano (tarda una fracción de segundo). */
    fun load(context: Context) {
        val appContext = context.applicationContext
        thread(name = "adblock-load") {
            val set = HashSet<String>(100_000)
            appContext.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val host = line.trim()
                    if (host.isNotEmpty() && !host.startsWith("#")) set.add(host)
                }
            }
            hosts = set
        }
    }

    fun isBlockedHost(host: String?): Boolean {
        if (!enabled || host.isNullOrEmpty()) return false
        val list = hosts
        var candidate = host.lowercase()
        while (true) {
            if (candidate in list) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.length - 1) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    override fun shouldBlock(request: WebResourceRequest): Boolean =
        !request.isForMainFrame && isBlockedHost(request.url.host)

    override fun shouldBlockNavigation(url: Uri): Boolean = isBlockedHost(url.host)
}
