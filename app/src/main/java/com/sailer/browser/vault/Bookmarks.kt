package com.sailer.browser.vault

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Favoritos privados: se guardan cifrados con la misma clave que la carpeta segura. */
class Bookmarks private constructor(context: Context) {

    data class Bookmark(val id: String, val title: String, val url: String, val created: Long)

    private val vault = Vault.get(context)
    private val file = File(File(context.filesDir, "vault").apply { mkdirs() }, "bookmarks.dat")
    private val items = mutableListOf<Bookmark>()

    init {
        load()
    }

    fun list(): List<Bookmark> = synchronized(this) { items.sortedByDescending { it.created } }

    fun contains(url: String): Boolean = synchronized(this) { items.any { it.url == url } }

    fun add(title: String, url: String) = synchronized(this) {
        if (items.none { it.url == url }) {
            items.add(Bookmark(UUID.randomUUID().toString(), title.ifBlank { url }, url, System.currentTimeMillis()))
            save()
        }
    }

    fun rename(bookmark: Bookmark, title: String) = synchronized(this) {
        val index = items.indexOfFirst { it.id == bookmark.id }
        if (index >= 0) items[index] = items[index].copy(title = title)
        save()
    }

    fun remove(bookmark: Bookmark) = synchronized(this) {
        items.removeAll { it.id == bookmark.id }
        save()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val json = JSONArray(String(vault.crypto.decryptBytes(file.readBytes()), Charsets.UTF_8))
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                items.add(Bookmark(o.getString("id"), o.getString("title"), o.getString("url"), o.optLong("created")))
            }
        }
    }

    private fun save() {
        val json = JSONArray()
        items.forEach {
            json.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("created", it.created))
        }
        val tmp = File(file.parentFile, "bookmarks.tmp")
        tmp.writeBytes(vault.crypto.encryptBytes(json.toString().toByteArray(Charsets.UTF_8)))
        tmp.renameTo(file)
    }

    companion object {
        @Volatile
        private var instance: Bookmarks? = null

        fun get(context: Context): Bookmarks =
            instance ?: synchronized(this) {
                instance ?: Bookmarks(context.applicationContext).also { instance = it }
            }
    }
}
