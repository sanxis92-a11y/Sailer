package com.sailer.browser.vault

import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile

/** Deja leer un archivo cifrado a MediaExtractor (para unir audio y vídeo). */
class EncryptedMediaSource(private val crypto: VaultCrypto, file: File) : MediaDataSource() {

    private val raf = RandomAccessFile(file, "r")
    private val iv = crypto.readIv(file)
    private val length = file.length() - VaultCrypto.HEADER

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        val n = minOf(size.toLong(), length - position).toInt()
        if (n <= 0) return 0
        synchronized(raf) {
            raf.seek(VaultCrypto.HEADER + position)
            raf.readFully(buffer, offset, n)
        }
        crypto.xorAt(iv, position, buffer, offset, n)
        return n
    }

    override fun getSize(): Long = length

    override fun close() = raf.close()
}
