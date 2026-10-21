package com.sailer.browser.vault

import android.content.Context
import com.sailer.browser.media.MediaKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Copia de seguridad de la carpeta segura y los favoritos en un solo archivo,
 * cifrado con una contraseña que elige el usuario (no con la clave del móvil).
 * Sirve para pasar todo a otro móvil o para reinstalar la app sin perder nada.
 *
 * Formato: "SAILERK1" + sal (16) + iteraciones (int), y después bloques de
 * hasta 64 KB: longitud (int) + nonce (12) + datos AES-256-GCM. Cada bloque
 * lleva su número y si es el último como datos autenticados, así no se
 * pueden quitar, cambiar de orden ni recortar.
 */
object Backup {

    class WrongPasswordException : IOException("Contraseña incorrecta o archivo dañado")

    data class Result(val files: Int, val bookmarks: Int)

    private val MAGIC = "SAILERK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 310_000
    private const val SEGMENT = 64 * 1024
    private const val NONCE = 12
    private const val FORMAT = 1
    private val random = SecureRandom()

    /** Escribe la copia en [target]. [onProgress] recibe (hechos, total). */
    fun export(context: Context, password: CharArray, target: OutputStream, onProgress: (Int, Int) -> Unit): Result {
        val vault = Vault.get(context)
        val bookmarks = Bookmarks.get(context).list()
        val entries = vault.list()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(MAGIC.size + 16 + 4).put(MAGIC).put(salt).putInt(ITERATIONS).array()
        target.write(header)
        val key = deriveKey(password, salt, ITERATIONS)
        DataOutputStream(SealOutput(target, key, header).buffered(SEGMENT)).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(bookmarks.size)
            bookmarks.forEach {
                writeText(out, it.title)
                writeText(out, it.url)
                out.writeLong(it.created)
            }
            out.writeInt(entries.size)
            entries.forEachIndexed { i, entry ->
                onProgress(i, entries.size)
                val size = vault.crypto.plainLength(vault.fileOf(entry))
                writeText(out, entry.name)
                writeText(out, entry.kind.name)
                writeText(out, entry.mime ?: "")
                out.writeLong(entry.created)
                out.writeLong(size)
                vault.openInput(entry).use { input -> copyExactly(input, out, size) }
            }
            onProgress(entries.size, entries.size)
        }
        return Result(entries.size, bookmarks.size)
    }

    /** Lee una copia y añade su contenido a la carpeta segura y a los favoritos. */
    fun import(context: Context, password: CharArray, source: InputStream, onProgress: (Int, Int) -> Unit): Result {
        val header = ByteArray(MAGIC.size + 16 + 4)
        try {
            DataInputStream(source).readFully(header)
        } catch (e: EOFException) {
            throw IOException("No es una copia de Sailer")
        }
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("No es una copia de Sailer")
        val salt = ByteArray(16).also { buffer.get(it) }
        val iterations = buffer.int
        if (iterations !in 10_000..10_000_000) throw IOException("No es una copia de Sailer")
        val key = deriveKey(password, salt, iterations)

        val vault = Vault.get(context)
        val bookmarksStore = Bookmarks.get(context)
        DataInputStream(OpenInput(source, key, header).buffered(SEGMENT)).use { input ->
            if (input.readInt() != FORMAT) throw IOException("Versión de copia no compatible")
            val bookmarkCount = input.readInt()
            repeat(bookmarkCount) {
                val title = readText(input)
                val url = readText(input)
                input.readLong()
                bookmarksStore.add(title, url)
            }
            val count = input.readInt()
            for (i in 0 until count) {
                onProgress(i, count)
                val name = readText(input)
                val kind = runCatching { MediaKind.valueOf(readText(input)) }.getOrDefault(MediaKind.FILE)
                val mime = readText(input).ifEmpty { null }
                val created = input.readLong()
                val size = input.readLong()
                val writer = vault.newWriter()
                try {
                    copyExactly(input, writer.output, size)
                    writer.commit(name, kind, mime, created)
                } catch (e: Exception) {
                    writer.abort()
                    throw e
                }
            }
            onProgress(count, count)
            return Result(count, bookmarkCount)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(header: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 9).put(header).putLong(index).put((if (last) 1 else 0).toByte()).array()

    private fun writeText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readInt()
        if (size !in 0..1_000_000) throw IOException("Copia dañada")
        return ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long) {
        val buffer = ByteArray(SEGMENT)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("Copia incompleta")
            out.write(buffer, 0, n)
            remaining -= n
        }
    }

    /** Cifra lo que se escribe en bloques autenticados. */
    private class SealOutput(
        private val target: OutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
    ) : OutputStream() {
        private val buffer = ByteArray(SEGMENT)
        private var count = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
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
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(header, index, last))
            val sealed = cipher.doFinal(buffer, 0, count)
            target.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            target.write(nonce)
            target.write(sealed)
            index++
            count = 0
        }
    }

    /** Descifra y comprueba cada bloque; falla si algo se ha tocado. */
    private class OpenInput(
        source: InputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
    ) : InputStream() {
        private val input = DataInputStream(source.buffered(2 * SEGMENT))
        private var current = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == current.size) {
                if (finished) return -1
                nextSegment()
            }
            val n = minOf(len, current.size - position)
            System.arraycopy(current, position, b, off, n)
            position += n
            return n
        }

        private fun nextSegment() {
            val size = try {
                input.readInt()
            } catch (e: EOFException) {
                throw IOException("Copia incompleta")
            }
            if (size !in 16..SEGMENT + 16) throw WrongPasswordException()
            val nonce = ByteArray(NONCE).also { input.readFully(it) }
            val sealed = ByteArray(size).also { input.readFully(it) }
            // Tras el último bloque no debe quedar nada más.
            val last = isAtEnd()
            current = open(nonce, sealed, last)
            position = 0
            finished = last
            index++
        }

        private fun isAtEnd(): Boolean {
            input.mark(1)
            val next = input.read()
            if (next >= 0) {
                input.reset()
                return false
            }
            return true
        }

        private fun open(nonce: ByteArray, sealed: ByteArray, last: Boolean): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(header, index, last))
            return try {
                cipher.doFinal(sealed)
            } catch (e: AEADBadTagException) {
                throw WrongPasswordException()
            }
        }

        override fun close() = input.close()
    }
}
