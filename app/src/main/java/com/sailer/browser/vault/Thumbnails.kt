package com.sailer.browser.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import com.sailer.browser.media.MediaKind

/**
 * Miniaturas de fotos y vídeos de la carpeta segura. Se descifran solo en
 * memoria (nunca se guardan en disco) y se borran al salir de la carpeta.
 */
object Thumbnails {

    private const val MAX_IMAGE_BYTES = 40L * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Archivos de los que no se pudo sacar miniatura (para no reintentarlo). */
    private val failed = mutableSetOf<String>()

    fun supports(entry: Vault.Entry) = entry.kind == MediaKind.IMAGE || entry.kind == MediaKind.VIDEO

    fun cached(entry: Vault.Entry): Bitmap? = cache.get(entry.id)

    fun hasFailed(entry: Vault.Entry) = synchronized(failed) { entry.id in failed }

    /** Calcula la miniatura (cuadrada, [size] píxeles). Bloquea: llamar en segundo plano. */
    fun load(vault: Vault, entry: Vault.Entry, size: Int): Bitmap? {
        cache.get(entry.id)?.let { return it }
        val bitmap = runCatching {
            when (entry.kind) {
                MediaKind.IMAGE -> image(vault, entry, size)
                MediaKind.VIDEO -> video(vault, entry, size)
                else -> null
            }
        }.getOrNull()?.let { ThumbnailUtils.extractThumbnail(it, size, size, ThumbnailUtils.OPTIONS_RECYCLE_INPUT) }
        if (bitmap != null) cache.put(entry.id, bitmap) else synchronized(failed) { failed.add(entry.id) }
        return bitmap
    }

    fun clear() {
        cache.evictAll()
        synchronized(failed) { failed.clear() }
    }

    private fun image(vault: Vault, entry: Vault.Entry, size: Int): Bitmap? {
        if (entry.size > MAX_IMAGE_BYTES) return null
        val bytes = vault.openInput(entry).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun video(vault: Vault, entry: Vault.Entry, size: Int): Bitmap? {
        val reader = vault.openReader(entry)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int): Int =
                    reader.read(position, buffer, offset, count)

                override fun getSize(): Long = reader.length

                override fun close() = Unit
            })
            val time = 2_000_000L
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size * 2, size * 2)
            } else {
                retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: retriever.frameAtTime
        } finally {
            runCatching { retriever.release() }
            runCatching { reader.close() }
        }
    }
}
