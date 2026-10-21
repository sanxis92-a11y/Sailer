package com.sailer.browser.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
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
 *   Keystore) y nunca puede sacarse de él.
 * - Esa clave protege la "clave de datos" (AES-256) con la que se cifran los
 *   archivos en modo AES-CTR, que permite saltar a cualquier punto de un
 *   vídeo sin descifrarlo entero.
 *
 * Formato de cada archivo: 16 bytes de IV aleatorio + datos cifrados.
 */
class VaultCrypto(context: Context) {

    private val dataKey: SecretKey = loadOrCreateDataKey(context.applicationContext)
    private val random = SecureRandom()

    fun encrypt(out: OutputStream): OutputStream {
        val iv = ByteArray(HEADER).also { random.nextBytes(it) }
        out.write(iv)
        return CtrOutputStream(out, ctr(Cipher.ENCRYPT_MODE, iv))
    }

    fun decrypt(input: InputStream): InputStream {
        val iv = readIv(input)
        return CtrInputStream(input, ctr(Cipher.DECRYPT_MODE, iv))
    }

    /** Abre un archivo descifrando desde la posición indicada (para vídeos). */
    fun decryptFrom(file: File, position: Long): InputStream {
        val input = FileInputStream(file)
        try {
            val iv = readIv(input)
            val block = position / 16
            val skip = (position % 16).toInt()
            input.channel.position(HEADER + block * 16)
            val stream = CtrInputStream(input, ctr(Cipher.DECRYPT_MODE, addToCounter(iv, block)))
            if (skip > 0) {
                val discard = ByteArray(skip)
                var read = 0
                while (read < skip) {
                    val n = stream.read(discard, read, skip - read)
                    if (n < 0) break
                    read += n
                }
            }
            return stream
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }

    fun encryptBytes(plain: ByteArray): ByteArray {
        val iv = ByteArray(HEADER).also { random.nextBytes(it) }
        return iv + ctr(Cipher.ENCRYPT_MODE, iv).doFinal(plain)
    }

    fun decryptBytes(data: ByteArray): ByteArray {
        if (data.size < HEADER) throw IOException("Datos dañados")
        return ctr(Cipher.DECRYPT_MODE, data.copyOfRange(0, HEADER)).doFinal(data, HEADER, data.size - HEADER)
    }

    private fun ctr(mode: Int, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply { init(mode, dataKey, IvParameterSpec(iv)) }

    private fun readIv(input: InputStream): ByteArray {
        val iv = ByteArray(HEADER)
        var read = 0
        while (read < HEADER) {
            val n = input.read(iv, read, HEADER - read)
            if (n < 0) throw IOException("Archivo dañado")
            read += n
        }
        return iv
    }

    private fun addToCounter(iv: ByteArray, blocks: Long): ByteArray {
        val sum = BigInteger(1, iv).add(BigInteger.valueOf(blocks)).mod(BigInteger.ONE.shiftLeft(128))
        val bytes = sum.toByteArray()
        val out = ByteArray(16)
        val copy = minOf(bytes.size, 16)
        System.arraycopy(bytes, bytes.size - copy, out, 16 - copy, copy)
        return out
    }

    private class CtrInputStream(private val source: InputStream, private val cipher: Cipher) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = source.read(b, off, len)
            if (n <= 0) return n
            cipher.update(b, off, n, b, off)
            return n
        }

        override fun close() = source.close()
    }

    private class CtrOutputStream(private val target: OutputStream, private val cipher: Cipher) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            cipher.update(b, off, len)?.let { target.write(it) }
        }

        override fun flush() = target.flush()

        override fun close() {
            cipher.doFinal()?.let { if (it.isNotEmpty()) target.write(it) }
            target.close()
        }
    }

    companion object {
        const val HEADER = 16
        private const val MASTER_ALIAS = "sailer_vault_master"
        private const val PREFS = "vault"
        private const val KEY_DATA = "data_key"

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
