package com.sailer.browser.privacy

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import androidx.core.content.edit
import com.sailer.browser.vault.Vault
import java.io.File

/**
 * «Mantener la sesión iniciada» en las webs que el usuario elige.
 *
 * El navegador lo borra todo al cerrarse. Eso hace que, en las webs donde
 * tienes cuenta, tengas que volver a entrar cada vez (y algunas siguen
 * pidiéndote que inicies sesión aunque acabes de hacerlo). Para las webs
 * marcadas aquí se guarda una copia CIFRADA de sus cookies y se vuelve a
 * poner después de cada limpieza. El resto de webs se sigue borrando igual.
 *
 * Además, en esas webs se permiten las cookies de terceros, que algunos
 * inicios de sesión necesitan (cuando la cuenta está en otro dominio).
 *
 * Solo se guardan cookies: lo que la web guarde en su almacenamiento local
 * se sigue borrando al cerrar.
 */
object SessionKeeper {

    private const val PREFS = "sessions"
    private const val KEY_SITES = "sites_enc"
    private const val FILE = "sessions.bin"
    private const val MAX_AGE = 30L * 24 * 3600 // 30 días; se renueva cada vez que se guarda

    @Volatile private var sites: Set<String> = emptySet()
    @Volatile private var loaded = false

    /** Direcciones exactas visitadas de cada web marcada (www.x.com, cuentas.x.com…). */
    private val seenHosts = mutableSetOf<String>()

    fun siteKey(host: String?): String? = host?.lowercase()?.trimEnd('.')?.removePrefix("www.")?.ifEmpty { null }

    fun load(context: Context) {
        if (loaded) return
        sites = runCatching {
            val stored = prefs(context).getString(KEY_SITES, null) ?: return@runCatching emptySet<String>()
            val plain = crypto(context).decryptBytes(Base64.decode(stored, Base64.NO_WRAP))
            String(plain, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())
        loaded = true
    }

    val keptSites: Set<String> get() = sites

    fun isKept(host: String?): Boolean = siteOf(host) != null

    fun setKept(context: Context, host: String?, keep: Boolean) {
        val key = siteKey(host) ?: return
        load(context)
        sites = if (keep) sites + key else sites - key
        val data = crypto(context).encryptBytes(sites.joinToString("\n").toByteArray(Charsets.UTF_8))
        prefs(context).edit { putString(KEY_SITES, Base64.encodeToString(data, Base64.NO_WRAP)) }
        if (keep) host?.lowercase()?.let { synchronized(seenHosts) { seenHosts += it } }
        save(context)
    }

    /** Apunta una dirección visitada para guardar luego sus cookies. */
    fun noteVisit(context: Context, host: String?) {
        if (host == null || !isKept(host)) return
        val added = synchronized(seenHosts) { seenHosts.add(host.lowercase()) }
        if (added) save(context)
    }

    /**
     * Guarda (cifradas) las cookies de las webs marcadas. Se llama al terminar
     * de cargar sus páginas, al pasar la app a segundo plano y antes de borrar.
     */
    fun save(context: Context) {
        if (sites.isEmpty()) {
            file(context).delete()
            return
        }
        val cookies = CookieManager.getInstance()
        val previous = readSaved(context)
        val hosts = synchronized(seenHosts) { seenHosts.toSet() } +
            sites.flatMap { listOf(it, "www.$it") } + previous.keys
        val lines = mutableListOf<String>()
        for (host in hosts) {
            if (!isKept(host)) continue
            val now = runCatching { cookies.getCookie("https://$host/") }.getOrNull()
            // Sin cookies (p. ej. has cerrado sesión en la web): no se guarda nada de esa dirección.
            val value = now?.takeIf { it.isNotBlank() } ?: continue
            lines += "$host\t$value"
        }
        if (lines.isEmpty()) {
            file(context).delete()
            return
        }
        runCatching {
            val data = crypto(context).encryptBytes(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeBytes(data)
            tmp.renameTo(file(context))
        }
    }

    /** Vuelve a poner las cookies guardadas (después de cada limpieza). */
    fun restore(context: Context) {
        val saved = readSaved(context)
        if (saved.isEmpty()) return
        val cookies = CookieManager.getInstance()
        for ((host, header) in saved) {
            if (!isKept(host)) continue
            synchronized(seenHosts) { seenHosts += host }
            header.split(";").forEach { part ->
                val pair = part.trim()
                if (pair.isEmpty() || !pair.contains('=')) return@forEach
                cookies.setCookie("https://$host/", "$pair; Path=/; Secure; Max-Age=$MAX_AGE")
            }
        }
        cookies.flush()
    }

    /** Olvida todas las sesiones guardadas (por ejemplo, con «Borrar todo y salir»). */
    fun forgetAll(context: Context) {
        load(context)
        sites = emptySet()
        prefs(context).edit { remove(KEY_SITES) }
        synchronized(seenHosts) { seenHosts.clear() }
        file(context).delete()
    }

    private fun siteOf(host: String?): String? {
        if (host.isNullOrEmpty() || sites.isEmpty()) return null
        var candidate = host.lowercase().trimEnd('.')
        while (true) {
            if (candidate in sites) return candidate
            val dot = candidate.indexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun readSaved(context: Context): Map<String, String> {
        val f = file(context)
        if (!f.exists()) return emptyMap()
        return runCatching {
            String(crypto(context).decryptBytes(f.readBytes()), Charsets.UTF_8)
                .split('\n')
                .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun crypto(context: Context) = Vault.get(context.applicationContext).crypto
}
