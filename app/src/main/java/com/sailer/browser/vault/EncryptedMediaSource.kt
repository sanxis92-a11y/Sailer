package com.sailer.browser.vault

import android.media.MediaDataSource
import java.io.File

/** Deja leer un archivo cifrado a MediaExtractor (para unir audio y vídeo). */
class EncryptedMediaSource(crypto: VaultCrypto, file: File) : MediaDataSource() {

    private val reader = crypto.openReader(file)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        synchronized(reader) { reader.read(position, buffer, offset, size) }

    override fun getSize(): Long = reader.length

    override fun close() = reader.close()
}
