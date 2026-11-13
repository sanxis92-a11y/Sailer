package com.sailer.browser.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.sailer.browser.media.MediaKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Analiza las fotos de la carpeta segura para «Limpiar fotos». Lo calculado
 * (rejilla, nitidez y tamaño de cada foto) se guarda cifrado para que la
 * próxima vez solo haya que mirar las fotos nuevas. También recuerda los
 * grupos que el usuario marcó como «No son iguales».
 */
class PhotoIndex(private val vault: Vault) {

    private val file = vault.dataFile("photo_index.dat")
    private val known = HashMap<String, Stored>()
    private val ignored = HashSet<String>()

    private class Stored(val width: Int, val height: Int, val sharpness: Float, val grid: ByteArray)

    init {
        load()
    }

    /**
     * Devuelve la información de las fotos de [entries], calculando las que
     * falten. Bloquea: llamar en segundo plano.
     */
    fun scan(entries: List<Vault.Entry>, progress: (done: Int, total: Int) -> Unit, cancelled: () -> Boolean): List<PhotoMatch.Info> {
        val photos = entries.filter { it.kind == MediaKind.IMAGE && it.size <= MAX_BYTES }
        val result = ArrayList<PhotoMatch.Info>(photos.size)
        var dirty = false
        photos.forEachIndexed { i, entry ->
            if (cancelled()) return result
            progress(i, photos.size)
            val stored = synchronized(known) { known[entry.id] } ?: analyze(entry)?.also {
                synchronized(known) { known[entry.id] = it }
                dirty = true
                if (i % 50 == 49) save()
            }
            if (stored != null) {
                result += PhotoMatch.Info(entry.id, stored.width, stored.height, entry.size, entry.created, stored.sharpness, stored.grid, entry.source)
            }
        }
        progress(photos.size, photos.size)
        // Olvidar lo de fotos que ya no existen.
        val alive = vault.list().map { it.id }.toSet()
        synchronized(known) {
            if (known.keys.retainAll(alive)) dirty = true
            if (ignored.removeAll { key -> key.split('|').any { it !in alive } }) dirty = true
        }
        if (dirty) save()
        return result
    }

    /** Parejas de fotos que el usuario dijo que no son iguales. */
    fun ignoredPairs(): Set<String> = synchronized(known) { ignored.toSet() }

    fun ignore(ids: List<String>) {
        synchronized(known) {
            for (i in ids.indices) for (j in i + 1 until ids.size) ignored += PhotoMatch.pairKey(ids[i], ids[j])
        }
        save()
    }

    private fun analyze(entry: Vault.Entry): Stored? = runCatching {
        val bytes = vault.openInput(entry).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WORK_SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        // Mismo tamaño de trabajo para todas, así la nitidez se puede comparar.
        val scale = WORK_SIZE.toFloat() / maxOf(decoded.width, decoded.height)
        val work = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val w = work.width
        val h = work.height
        val pixels = IntArray(w * h)
        work.getPixels(pixels, 0, w, 0, 0, w, h)
        work.recycle()
        for (i in pixels.indices) pixels[i] = PhotoMatch.luma(pixels[i])
        Stored(bounds.outWidth, bounds.outHeight, PhotoMatch.sharpness(pixels, w, h), PhotoMatch.gridOf(pixels, w, h))
    }.getOrNull()

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(String(vault.crypto.decryptBytes(file.readBytes()), Charsets.UTF_8))
            val photos = root.getJSONArray("photos")
            for (i in 0 until photos.length()) {
                val o = photos.getJSONObject(i)
                known[o.getString("id")] = Stored(
                    o.getInt("w"), o.getInt("h"), o.getDouble("s").toFloat(),
                    Base64.decode(o.getString("g"), Base64.NO_WRAP),
                )
            }
            root.optJSONArray("ignored")?.let { for (i in 0 until it.length()) ignored += it.getString(i) }
        }
    }

    private fun save() {
        val text = synchronized(known) {
            val photos = JSONArray()
            known.forEach { (id, s) ->
                photos.put(
                    JSONObject().put("id", id).put("w", s.width).put("h", s.height).put("s", s.sharpness.toDouble())
                        .put("g", Base64.encodeToString(s.grid, Base64.NO_WRAP))
                )
            }
            JSONObject().put("v", 1).put("photos", photos).put("ignored", JSONArray(ignored.toList())).toString()
        }
        runCatching {
            val tmp = vault.dataFile("photo_index.tmp")
            tmp.writeBytes(vault.crypto.encryptBytes(text.toByteArray(Charsets.UTF_8)))
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val MAX_BYTES = 40L * 1024 * 1024
        /** Lado mayor de la foto reducida con la que se trabaja. */
        private const val WORK_SIZE = 512
    }
}
