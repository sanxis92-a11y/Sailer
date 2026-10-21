package com.sailer.browser.adblock

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import com.sailer.browser.settings.Prefs
import com.sailer.browser.web.RequestFilter
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.concurrent.thread

/**
 * Bloquea anuncios, rastreadores y redirecciones de publicidad comparando
 * cada dirección con ~250.000 dominios (StevenBlack, HaGeZi PRO++, EasyList,
 * EasyList Adult y EasyPrivacy). Las listas se generan con
 * `tools/build_blocklists.py` y se guardan como "huellas" de 64 bits
 * ordenadas, que ocupan poca memoria y se buscan muy rápido.
 */
object AdBlocker : RequestFilter {

    @Volatile
    private var hashes = LongArray(0)

    @Volatile
    var enabled = true

    @Volatile
    private var allowlist: Set<String> = emptySet()

    fun load(context: Context) {
        val appContext = context.applicationContext
        allowlist = Prefs.get(appContext).adBlockAllowlist
        thread(name = "adblock-load") {
            val bytes = appContext.assets.open("blocklist.bin").use { it.readBytes() }
            val array = LongArray(bytes.size / 8)
            ByteBuffer.wrap(bytes).asLongBuffer().get(array)
            hashes = array
        }
    }

    // ---- Webs donde el usuario lo ha desactivado ----

    fun siteKey(host: String?): String? = host?.lowercase()?.removePrefix("www.")?.ifEmpty { null }

    fun isAllowedSite(pageHost: String?): Boolean = matchesAny(pageHost, allowlist)

    fun setAllowedSite(context: Context, pageHost: String?, allowed: Boolean) {
        val key = siteKey(pageHost) ?: return
        val updated = if (allowed) allowlist + key else allowlist - key
        allowlist = updated
        Prefs.get(context).adBlockAllowlist = updated
    }

    val allowedSites: Set<String> get() = allowlist

    fun isActiveOn(pageHost: String?): Boolean = enabled && !isAllowedSite(pageHost)

    // ---- Comprobaciones ----

    fun isBlockedHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val list = hashes
        if (list.isEmpty()) return false
        var candidate = host.lowercase().trimEnd('.')
        while (true) {
            if (Arrays.binarySearch(list, fnv1a64(candidate)) >= 0) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.length - 1) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    override fun shouldBlock(request: WebResourceRequest, pageHost: String?): Boolean =
        !request.isForMainFrame && isActiveOn(pageHost) && isBlockedHost(request.url.host)

    override fun shouldBlockNavigation(url: Uri, pageHost: String?): Boolean =
        isActiveOn(pageHost) && isBlockedHost(url.host)

    private fun matchesAny(host: String?, set: Set<String>): Boolean {
        if (host.isNullOrEmpty() || set.isEmpty()) return false
        var candidate = host.lowercase()
        while (true) {
            if (candidate in set) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /** Mismo cálculo que en tools/build_blocklists.py. */
    private fun fnv1a64(text: String): Long {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash
    }
}
