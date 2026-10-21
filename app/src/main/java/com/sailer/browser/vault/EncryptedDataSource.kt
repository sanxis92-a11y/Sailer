package com.sailer.browser.vault

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Permite al reproductor leer vídeos cifrados descifrándolos al vuelo. */
@OptIn(UnstableApi::class)
class EncryptedDataSource(private val crypto: VaultCrypto) : BaseDataSource(false) {

    private var uri: Uri? = null
    private var input: InputStream? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val file = File(dataSpec.uri.path ?: throw IOException("Ruta no válida"))
        val plainLength = crypto.plainLength(file)
        if (dataSpec.position > plainLength) throw IOException("Posición fuera del archivo")
        input = crypto.decryptFrom(file, dataSpec.position)
        val available = plainLength - dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, available) else available
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = input ?: return C.RESULT_END_OF_INPUT
        var n: Int
        do {
            n = stream.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        } while (n == 0)
        if (n < 0) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            input?.close()
        } finally {
            input = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
