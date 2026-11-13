package com.sailer.browser.vault

import com.sailer.browser.Lang
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
import kotlin.concurrent.thread

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
        /** Carpeta donde está ("" = raíz; subcarpetas separadas por "/"). */
        val folder: String = "",
        /** Página de la que se descargó (para organizar en galerías). */
        val source: String = "",
        /** Título de esa página o galería. */
        val album: String = "",
    )

    fun interface Listener {
        fun onVaultChanged()
    }

    val crypto = VaultCrypto(context)
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.bin")
    private val entries = mutableListOf<Entry>()
    /** Carpetas creadas por el usuario (rutas completas, p. ej. "Viajes/2024"). */
    private val folders = sortedSetOf<String>()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    init {
        loadIndex()
        // Borrar restos de descargas que se quedaron a medias.
        val known = entries.map { "${it.id}.bin" }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".bin") && file.name != indexFile.name && file.name !in known) file.delete()
            if (file.name.endsWith(".mig") || file.name.endsWith(".strip")) file.delete()
        }
        thread(name = "vault-migrate") { migrateLegacyFiles() }
    }

    /**
     * Pasa los archivos del formato antiguo (AES-CTR, versión 1.2 y anteriores)
     * al nuevo (AES-GCM autenticado). Se hace una sola vez, en segundo plano.
     */
    private fun migrateLegacyFiles() {
        if (indexFile.exists() && crypto.isLegacyBytes(indexFile.readBytes())) {
            synchronized(this) { saveIndex() }
        }
        list().forEach { entry ->
            val file = fileOf(entry)
            if (!file.exists() || crypto.isV2(file)) return@forEach
            val tmp = File(dir, "${entry.id}.mig")
            runCatching {
                crypto.decrypt(file).use { input ->
                    crypto.encrypt(FileOutputStream(tmp).buffered(256 * 1024)).use { out -> input.copyTo(out, 256 * 1024) }
                }
                if (!tmp.renameTo(file)) throw java.io.IOException(Lang.t("No se pudo reemplazar", "Couldn't replace the file"))
            }.onFailure { tmp.delete() }
        }
    }

    fun list(): List<Entry> = synchronized(this) { entries.sortedByDescending { it.created } }

    /** Archivos que están directamente en [folder]. */
    fun filesIn(folder: String): List<Entry> = synchronized(this) { entries.filter { it.folder == folder } }

    /** Subcarpetas directas de [folder]. */
    fun foldersIn(folder: String): List<String> = synchronized(this) {
        folders.filter { parentOf(it) == folder }
    }

    fun allFolders(): List<String> = synchronized(this) { folders.toList() }

    /** Número de archivos dentro de [folder] y sus subcarpetas. */
    fun countIn(folder: String): Int = synchronized(this) { entries.count { isInside(it.folder, folder) } }

    fun sizeIn(folder: String): Long = synchronized(this) { entries.filter { isInside(it.folder, folder) }.sumOf { it.size } }

    fun createFolder(parent: String, name: String): String = ensureFolder(join(parent, cleanName(name)))

    /** Crea la carpeta [path] (ruta completa) y las intermedias si faltan. */
    fun ensureFolder(path: String): String {
        if (path.isEmpty()) return path
        synchronized(this) {
            addFolderLocked(path)
            saveIndex()
        }
        notifyChanged()
        return path
    }

    private fun addFolderLocked(path: String) {
        var current = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { part ->
            current = join(current, part)
            folders.add(current)
        }
    }

    fun renameFolder(path: String, newName: String): String {
        val target = join(parentOf(path), cleanName(newName))
        if (target == path) return path
        synchronized(this) {
            if (target in folders) throw IllegalArgumentException(Lang.t("Ya existe una carpeta con ese nombre", "A folder with that name already exists"))
            moveFolderLocked(path, target)
            saveIndex()
        }
        notifyChanged()
        return target
    }

    /** Mueve archivos y carpetas a [target]. Una carpeta no se puede meter dentro de sí misma. */
    fun move(files: Collection<Entry>, folderPaths: Collection<String>, target: String) {
        synchronized(this) {
            val ids = files.map { it.id }.toSet()
            for (i in entries.indices) {
                if (entries[i].id in ids) entries[i] = entries[i].copy(folder = target)
            }
            folderPaths.forEach { path ->
                if (isInside(target, path)) return@forEach
                val newPath = join(target, nameOf(path))
                if (newPath != path && newPath !in folders) moveFolderLocked(path, newPath)
            }
            saveIndex()
        }
        notifyChanged()
    }

    /** Borra una carpeta con todo lo que contiene. */
    fun deleteFolder(path: String) {
        val removed: List<Entry>
        synchronized(this) {
            removed = entries.filter { isInside(it.folder, path) }
            entries.removeAll(removed)
            folders.removeAll { isInside(it, path) }
            saveIndex()
        }
        removed.forEach { fileOf(it).delete() }
        notifyChanged()
    }

    private fun moveFolderLocked(from: String, to: String) {
        val moved = folders.filter { isInside(it, from) }
        folders.removeAll(moved.toSet())
        moved.forEach { folders.add(to + it.removePrefix(from)) }
        for (i in entries.indices) {
            val f = entries[i].folder
            if (isInside(f, from)) entries[i] = entries[i].copy(folder = to + f.removePrefix(from))
        }
    }

    fun find(id: String): Entry? = synchronized(this) { entries.firstOrNull { it.id == id } }

    fun fileOf(entry: Entry): File = File(dir, "${entry.id}.bin")

    fun openInput(entry: Entry): InputStream = crypto.decrypt(fileOf(entry))

    /** Lectura descifrada en cualquier posición (miniaturas de vídeo). */
    fun openReader(entry: Entry): VaultCrypto.Reader = crypto.openReader(fileOf(entry))

    /** Empieza a escribir un archivo nuevo cifrado. */
    fun newWriter(): Writer = Writer(UUID.randomUUID().toString())

    inner class Writer(private val id: String) {
        private val file = File(dir, "$id.bin")
        val output: OutputStream = crypto.encrypt(FileOutputStream(file).buffered(256 * 1024))

        fun commit(
            name: String,
            kind: MediaKind,
            mime: String?,
            created: Long = System.currentTimeMillis(),
            folder: String = "",
            source: String = "",
            album: String = "",
        ): Entry {
            output.close()
            val entry = Entry(id, name, kind, mime, crypto.plainLength(file), created, folder, source, album)
            synchronized(this@Vault) {
                if (folder.isNotEmpty()) addFolderLocked(folder)
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

    /** Mete en la carpeta segura un archivo ya cifrado con [crypto] (lo mueve). */
    fun adopt(
        file: File,
        name: String,
        kind: MediaKind,
        mime: String?,
        folder: String = "",
        source: String = "",
        album: String = "",
    ): Entry {
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.bin")
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        val entry = Entry(id, name, kind, mime, crypto.plainLength(target), System.currentTimeMillis(), folder, source, album)
        synchronized(this) {
            if (folder.isNotEmpty()) addFolderLocked(folder)
            entries.add(entry)
            saveIndex()
        }
        notifyChanged()
        return entry
    }

    /**
     * Organiza en galerías los archivos que están directamente en [folder]:
     * cada uno va a «web/título de la página» según de dónde se descargó.
     * Devuelve cuántos se han movido.
     */
    fun organize(folder: String): Int {
        var moved = 0
        synchronized(this) {
            entries.replaceAll { e ->
                if (e.folder != folder || e.source.isEmpty()) return@replaceAll e
                val target = galleryFolder(e.source, e.album)
                if (target.isEmpty() || target == e.folder) return@replaceAll e
                addFolderLocked(target)
                moved++
                e.copy(folder = target)
            }
            if (moved > 0) saveIndex()
        }
        if (moved > 0) notifyChanged()
        return moved
    }

    /** Archivo cifrado propio de la carpeta segura para guardar datos auxiliares (no puede acabar en .bin). */
    fun dataFile(name: String): File = File(dir, name)

    /**
     * Quita a una foto los datos ocultos (ubicación GPS, cámara, XMP...).
     * Devuelve true si había algo que quitar.
     */
    fun stripMetadata(entry: Entry): Boolean {
        if (entry.kind != MediaKind.IMAGE || entry.size > MAX_STRIP_BYTES) return false
        val bytes = runCatching { openInput(entry).use { it.readBytes() } }.getOrNull() ?: return false
        val clean = MetaStrip.strip(bytes) ?: return false
        val tmp = File(dir, "${entry.id}.strip")
        try {
            crypto.encrypt(FileOutputStream(tmp).buffered(256 * 1024)).use { it.write(clean) }
            synchronized(this) {
                val index = entries.indexOfFirst { it.id == entry.id }
                if (index < 0 || !tmp.renameTo(fileOf(entry))) return false
                entries[index] = entries[index].copy(size = clean.size.toLong())
                saveIndex()
            }
        } finally {
            tmp.delete()
        }
        notifyChanged()
        return true
    }

    fun delete(entry: Entry) = delete(listOf(entry))

    fun delete(list: Collection<Entry>) {
        val ids = list.map { it.id }.toSet()
        synchronized(this) {
            entries.removeAll { it.id in ids }
            saveIndex()
        }
        list.forEach { fileOf(it).delete() }
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
            val text = String(crypto.decryptBytes(indexFile.readBytes()), Charsets.UTF_8)
            // Formato antiguo: solo la lista de archivos. Nuevo: {"entries": [...], "folders": [...]}.
            val json = if (text.trimStart().startsWith("[")) {
                JSONArray(text)
            } else {
                val root = JSONObject(text)
                root.optJSONArray("folders")?.let { f -> for (i in 0 until f.length()) folders.add(f.getString(i)) }
                root.getJSONArray("entries")
            }
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
                        folder = o.optString("folder"),
                        source = o.optString("source"),
                        album = o.optString("album"),
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
                    .put("folder", e.folder)
                    .put("source", e.source)
                    .put("album", e.album)
            )
        }
        val root = JSONObject().put("entries", json).put("folders", JSONArray(folders.toList()))
        val tmp = File(dir, "index.tmp")
        tmp.writeBytes(crypto.encryptBytes(root.toString().toByteArray(Charsets.UTF_8)))
        tmp.renameTo(indexFile)
    }

    companion object {
        private const val MAX_STRIP_BYTES = 40L * 1024 * 1024

        /** «web» o «web/galería» para lo descargado de [pageUrl]. */
        fun galleryFolder(pageUrl: String?, album: String?): String {
            val host = pageUrl?.takeIf { it.startsWith("http") }
                ?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() } ?: return ""
            val site = cleanName(host.removePrefix("www."))
            val title = album?.trim()?.take(60)?.takeIf { it.isNotEmpty() } ?: return site
            return join(site, cleanName(title))
        }

        fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

        fun parentOf(path: String) = path.substringBeforeLast('/', "")

        fun nameOf(path: String) = path.substringAfterLast('/')

        /** ¿[path] es [folder] o está dentro de ella? */
        fun isInside(path: String, folder: String) = path == folder || path.startsWith("$folder/")

        fun cleanName(name: String) = name.replace('/', '-').trim().ifEmpty { "?" }

        @Volatile
        private var instance: Vault? = null

        fun get(context: Context): Vault =
            instance ?: synchronized(this) {
                instance ?: Vault(context.applicationContext).also { instance = it }
            }
    }
}
