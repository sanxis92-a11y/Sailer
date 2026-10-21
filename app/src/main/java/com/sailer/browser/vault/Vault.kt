package com.sailer.browser.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.sailer.browser.media.MediaKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Carpeta segura: archivos cifrados dentro del almacenamiento privado de la
 * app (la galería y las demás apps no pueden verlos). Hasta los nombres de
 * los archivos se guardan cifrados en un índice.
 */
class Vault private constructor(context: Context) {

    data class Entry(
        val id: String,
        val name: String,
        val kind: MediaKind,
        val mime: String?,
        val size: Long,
        val created: Long,
    )

    fun interface Listener {
        fun onVaultChanged()
    }

    val crypto = VaultCrypto(context)
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.bin")
    private val entries = mutableListOf<Entry>()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    init {
        loadIndex()
        // Borrar restos de descargas que se quedaron a medias.
        val known = entries.map { "${it.id}.bin" }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".bin") && file.name != indexFile.name && file.name !in known) file.delete()
        }
    }

    fun list(): List<Entry> = synchronized(this) { entries.sortedByDescending { it.created } }

    fun find(id: String): Entry? = synchronized(this) { entries.firstOrNull { it.id == id } }

    fun fileOf(entry: Entry): File = File(dir, "${entry.id}.bin")

    fun openInput(entry: Entry): InputStream = crypto.decrypt(fileOf(entry).inputStream().buffered())

    /** Empieza a escribir un archivo nuevo cifrado. */
    fun newWriter(): Writer = Writer(UUID.randomUUID().toString())

    inner class Writer(private val id: String) {
        private val file = File(dir, "$id.bin")
        val output: OutputStream = crypto.encrypt(FileOutputStream(file).buffered(256 * 1024))

        fun commit(name: String, kind: MediaKind, mime: String?): Entry {
            output.close()
            val entry = Entry(id, name, kind, mime, file.length() - VaultCrypto.HEADER, System.currentTimeMillis())
            synchronized(this@Vault) {
                entries.add(entry)
                saveIndex()
            }
            notifyChanged()
            return entry
        }

        fun abort() {
            runCatching { output.close() }
            file.delete()
        }
    }

    fun delete(entry: Entry) {
        synchronized(this) {
            entries.removeAll { it.id == entry.id }
            saveIndex()
        }
        fileOf(entry).delete()
        notifyChanged()
    }

    fun rename(entry: Entry, newName: String) {
        synchronized(this) {
            val index = entries.indexOfFirst { it.id == entry.id }
            if (index >= 0) entries[index] = entries[index].copy(name = newName)
            saveIndex()
        }
        notifyChanged()
    }

    fun addListener(listener: Listener) = listeners.add(listener)

    fun removeListener(listener: Listener) = listeners.remove(listener)

    private fun notifyChanged() {
        main.post { listeners.forEach { it.onVaultChanged() } }
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        runCatching {
            val json = JSONArray(String(crypto.decryptBytes(indexFile.readBytes()), Charsets.UTF_8))
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                entries.add(
                    Entry(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        kind = runCatching { MediaKind.valueOf(o.getString("kind")) }.getOrDefault(MediaKind.FILE),
                        mime = o.optString("mime").ifEmpty { null },
                        size = o.optLong("size"),
                        created = o.optLong("created"),
                    )
                )
            }
        }
    }

    private fun saveIndex() {
        val json = JSONArray()
        entries.forEach { e ->
            json.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("kind", e.kind.name)
                    .put("mime", e.mime ?: "")
                    .put("size", e.size)
                    .put("created", e.created)
            )
        }
        val tmp = File(dir, "index.tmp")
        tmp.writeBytes(crypto.encryptBytes(json.toString().toByteArray(Charsets.UTF_8)))
        tmp.renameTo(indexFile)
    }

    companion object {
        @Volatile
        private var instance: Vault? = null

        fun get(context: Context): Vault =
            instance ?: synchronized(this) {
                instance ?: Vault(context.applicationContext).also { instance = it }
            }
    }
}
