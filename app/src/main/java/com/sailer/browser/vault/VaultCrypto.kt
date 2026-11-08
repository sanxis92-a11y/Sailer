package com.sailer.browser.vault

import com.sailer.browser.Lang
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de la carpeta segura.
 *
 * - Una clave maestra vive dentro del chip de seguridad del móvil (Android
 *   Keystore) y nunca puede sacarse de él. Esa clave protege la "clave de
 *   datos" (AES-256) con la que se cifran los archivos.
 * - Formato actual (v2): AES-256-GCM por bloques de 64 KB. Cada bloque lleva
 *   su propio nonce aleatorio y una etiqueta de autenticación ligada a su
 *   posición, así que cualquier cambio, recorte o reordenación se detecta, y
 *   se puede saltar a cualquier punto de un vídeo sin descifrarlo entero.
 *
 *   Archivo: "SAILERv2" (8) + identificador aleatorio (8) + bloques.
 *   Bloque:  nonce (12) + datos cifrados (hasta 64 KB) + etiqueta (16).
 *
 * - Formato antiguo (v1, AES-CTR sin autenticación): solo se lee, para migrarlo.
 */
class VaultCrypto(context: Context) {

    private val dataKey: SecretKey = loadOrCreateDataKey(context.applicationContext)
    private val random = SecureRandom()

    /** Lector con acceso aleatorio al contenido descifrado de un archivo. */
    interface Reader : Closeable {
        val length: Long
        fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int
    }

    // ---- Escritura (siempre v2) ----

    fun encrypt(out: OutputStream): OutputStream {
        val fileId = ByteArray(8).also { random.nextBytes(it) }
        out.write(MAGIC_FILE)
        out.write(fileId)
        return GcmWriter(out, fileId, 0, ByteArray(0))
    }

    /** Abre un archivo cifrado para seguir escribiendo al final (lo crea si no existe). */
    fun appendTo(file: File): OutputStream {
        if (!file.exists() || file.length() <= HEADER || !isV2(file)) {
            file.delete()
            return encrypt(FileOutputStream(file))
        }
        val reader = GcmReader(file)
        val lastIndex = reader.segmentCount - 1
        val lastPlain = reader.use { it.segment(lastIndex) }
        val lastStart = HEADER + lastIndex * FULL_SEGMENT
        RandomAccessFile(file, "rw").use { it.setLength(lastStart) }
        // El último bloque se vuelve a cifrar con un nonce nuevo, nunca con el mismo.
        return GcmWriter(FileOutputStream(file, true), reader.fileId, lastIndex, lastPlain)
    }

    // ---- Lectura (v2 y v1) ----

    fun isV2(file: File): Boolean {
        if (file.length() < HEADER) return false
        val head = ByteArray(MAGIC_FILE.size)
        FileInputStream(file).use { readFully(it, head) }
        return head.contentEquals(MAGIC_FILE)
    }

    fun openReader(file: File): Reader = if (isV2(file)) GcmReader(file) else CtrReader(file)

    /** Tamaño real (sin cifrar) del contenido de un archivo. */
    fun plainLength(file: File): Long {
        if (!file.exists() || file.length() < HEADER) return 0L
        return openReader(file).use { it.length }
    }

    fun decrypt(file: File): InputStream = decryptFrom(file, 0)

    /** Abre un archivo descifrando desde la posición indicada (para vídeos). */
    fun decryptFrom(file: File, position: Long): InputStream = ReaderInputStream(openReader(file), position)

    // ---- Datos pequeños (índice, favoritos) ----

    fun encryptBytes(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(128, nonce))
        cipher.updateAAD(MAGIC_BYTES)
        return MAGIC_BYTES + nonce + cipher.doFinal(plain)
    }

    fun decryptBytes(data: ByteArray): ByteArray {
        if (data.size >= MAGIC_BYTES.size + NONCE + TAG && data.copyOfRange(0, MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(128, data, MAGIC_BYTES.size, NONCE))
            cipher.updateAAD(MAGIC_BYTES)
            val start = MAGIC_BYTES.size + NONCE
            return cipher.doFinal(data, start, data.size - start)
        }
        // Formato antiguo (AES-CTR).
        if (data.size < HEADER) throw IOException(Lang.t("Datos dañados", "Damaged data"))
        return ctr(data.copyOfRange(0, HEADER), 0).doFinal(data, HEADER, data.size - HEADER)
    }

    fun isLegacyBytes(data: ByteArray): Boolean =
        data.size < MAGIC_BYTES.size || !data.copyOfRange(0, MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)

    // ---- Implementación v2 ----

    private fun aad(fileId: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(17).put(fileId).putLong(index).put((if (last) 1 else 0).toByte()).array()

    private inner class GcmWriter(
        private val target: OutputStream,
        private val fileId: ByteArray,
        private var index: Long,
        initial: ByteArray,
    ) : OutputStream() {
        private val buffer = ByteArray(SEGMENT)
        private var count = initial.size
        private var closed = false

        init {
            System.arraycopy(initial, 0, buffer, 0, initial.size)
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                // Un bloque lleno solo se escribe cuando llegan más datos,
                // así el último bloque siempre se marca como último.
                if (count == SEGMENT) emit(last = false)
                val n = minOf(remaining, SEGMENT - count)
                System.arraycopy(b, offset, buffer, count, n)
                count += n
                offset += n
                remaining -= n
            }
        }

        override fun flush() = target.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                emit(last = true)
            } finally {
                target.close()
            }
        }

        private fun emit(last: Boolean) {
            val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(fileId, index, last))
            target.write(nonce)
            target.write(cipher.doFinal(buffer, 0, count))
            index++
            count = 0
        }
    }

    private inner class GcmReader(file: File) : Reader {
        private val raf = RandomAccessFile(file, "r")
        private val fileLength = raf.length()
        val fileId = ByteArray(8)
        val segmentCount: Long
        override val length: Long
        private var cachedIndex = -1L
        private var cached = ByteArray(0)

        init {
            raf.seek(MAGIC_FILE.size.toLong())
            raf.readFully(fileId)
            val body = fileLength - HEADER
            segmentCount = (body + FULL_SEGMENT - 1) / FULL_SEGMENT
            length = body - segmentCount * OVERHEAD
            if (segmentCount <= 0 || length < 0) throw IOException(Lang.t("Archivo dañado", "Damaged file"))
        }

        fun segment(index: Long): ByteArray {
            if (index == cachedIndex) return cached
            val start = HEADER + index * FULL_SEGMENT
            val size = minOf(FULL_SEGMENT.toLong(), fileLength - start).toInt()
            if (size < OVERHEAD) throw IOException(Lang.t("Archivo dañado", "Damaged file"))
            val data = ByteArray(size)
            synchronized(raf) {
                raf.seek(start)
                raf.readFully(data)
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(128, data, 0, NONCE))
            cipher.updateAAD(aad(fileId, index, index == segmentCount - 1))
            // Si alguien ha modificado el archivo, aquí salta un error en vez de devolver datos falsos.
            cached = cipher.doFinal(data, NONCE, size - NONCE)
            cachedIndex = index
            return cached
        }

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            if (position >= length) return -1
            if (count == 0) return 0
            val plain = segment(position / SEGMENT)
            val inner = (position % SEGMENT).toInt()
            val n = minOf(count, plain.size - inner)
            System.arraycopy(plain, inner, buffer, offset, n)
            return n
        }

        override fun close() = raf.close()
    }

    // ---- Implementación v1 (solo lectura) ----

    private inner class CtrReader(file: File) : Reader {
        private val raf = RandomAccessFile(file, "r")
        private val iv = ByteArray(HEADER).also { raf.readFully(it) }
        override val length: Long = raf.length() - HEADER

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            if (position >= length) return -1
            val n = minOf(count.toLong(), length - position).toInt()
            if (n <= 0) return 0
            synchronized(raf) {
                raf.seek(HEADER + position)
                raf.readFully(buffer, offset, n)
            }
            ctr(iv, position).update(buffer, offset, n, buffer, offset)
            return n
        }

        override fun close() = raf.close()
    }

    private fun ctr(iv: ByteArray, position: Long): Cipher {
        val sum = BigInteger(1, iv).add(BigInteger.valueOf(position / 16)).mod(BigInteger.ONE.shiftLeft(128))
        val bytes = sum.toByteArray()
        val counter = ByteArray(16)
        val copy = minOf(bytes.size, 16)
        System.arraycopy(bytes, bytes.size - copy, counter, 16 - copy, copy)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, dataKey, IvParameterSpec(counter))
        val skip = (position % 16).toInt()
        if (skip > 0) cipher.update(ByteArray(skip))
        return cipher
    }

    private class ReaderInputStream(private val reader: Reader, private var position: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = reader.read(position, b, off, len)
            if (n > 0) position += n
            return n
        }

        override fun close() = reader.close()
    }

    companion object {
        const val HEADER = 16
        private const val SEGMENT = 64 * 1024
        private const val NONCE = 12
        private const val TAG = 16
        private const val OVERHEAD = NONCE + TAG
        private const val FULL_SEGMENT = SEGMENT + OVERHEAD
        private val MAGIC_FILE = "SAILERv2".toByteArray(Charsets.US_ASCII)
        private val MAGIC_BYTES = "SAILERb2".toByteArray(Charsets.US_ASCII)

        private const val MASTER_ALIAS = "sailer_vault_master"
        private const val PREFS = "vault"
        private const val KEY_DATA = "data_key"

        private fun readFully(input: InputStream, target: ByteArray) {
            var read = 0
            while (read < target.size) {
                val n = input.read(target, read, target.size - read)
                if (n < 0) throw IOException(Lang.t("Archivo dañado", "Damaged file"))
                read += n
            }
        }

        private fun loadOrCreateDataKey(context: Context): SecretKey {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val master = masterKey()
            prefs.getString(KEY_DATA, null)?.let { stored ->
                val bytes = Base64.decode(stored, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, bytes, 0, 12))
                return SecretKeySpec(cipher.doFinal(bytes, 12, bytes.size - 12), "AES")
            }
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, master)
            val wrapped = cipher.iv + cipher.doFinal(raw)
            prefs.edit().putString(KEY_DATA, Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()
            return SecretKeySpec(raw, "AES")
        }

        private fun masterKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    MASTER_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
