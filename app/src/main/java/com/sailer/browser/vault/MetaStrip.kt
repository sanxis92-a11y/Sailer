package com.sailer.browser.vault

import java.io.ByteArrayOutputStream

/**
 * Lee y (solo si el usuario lo pide) quita los «datos ocultos» de una foto sin tocar la imagen: ubicación GPS,
 * modelo de cámara, fechas, autor, miniaturas y XMP/IPTC.
 *
 * - JPEG: se quitan los bloques EXIF, XMP, IPTC (Photoshop) y comentarios.
 *   Solo se conserva el giro de la foto (si no, saldría tumbada) en un EXIF
 *   mínimo nuevo. Las fotos en movimiento y las HDR se respetan: lo que va
 *   detrás de la imagen principal no se toca y su XMP especial se mantiene.
 * - PNG: se quitan los bloques eXIf, tEXt, zTXt, iTXt (aquí va el XMP) y tIME.
 * - WebP: se quitan los bloques EXIF y XMP.
 *
 * Kotlin puro (sin Android) para poder probarlo en el ordenador.
 */
object MetaStrip {

    /** Devuelve la foto limpia, o null si no es un formato conocido o no tenía nada que quitar. */
    fun strip(bytes: ByteArray): ByteArray? = runCatching {
        when {
            isJpeg(bytes) -> jpeg(bytes)
            isPng(bytes) -> png(bytes)
            isWebp(bytes) -> webp(bytes)
            else -> null
        }
    }.getOrNull()

    fun isJpeg(b: ByteArray) = b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    fun isPng(b: ByteArray) = b.size > 8 && b.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)

    fun isWebp(b: ByteArray) = b.size > 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP"

    // ---- Leer XMP ----

    /** Texto XMP de la foto (JPEG, PNG o WebP), o null si no tiene. */
    fun readXmp(b: ByteArray): String? = runCatching {
        when {
            isJpeg(b) -> {
                var pos = 2
                val parts = StringBuilder()
                while (pos + 4 <= b.size && b[pos] == 0xFF.toByte()) {
                    val marker = b[pos + 1].toInt() and 0xFF
                    if (marker == 0xDA || marker == 0xD9) break
                    if (marker == 0xFF) { pos++; continue }
                    if (marker == 0x01 || marker in 0xD0..0xD8) { pos += 2; continue }
                    val length = u16be(b, pos + 2)
                    if (length < 2 || pos + 2 + length > b.size) break
                    val start = pos + 4
                    val len = length - 2
                    if (marker == 0xE1 && startsWith(b, start, len, XMP_ID)) {
                        parts.append(String(b, start + XMP_ID.size, len - XMP_ID.size, Charsets.UTF_8))
                    } else if (marker == 0xE1 && startsWith(b, start, len, XMP_EXT_ID)) {
                        // XMP ampliado: 32 de huella + 4 de tamaño + 4 de posición.
                        val skip = XMP_EXT_ID.size + 40
                        if (len > skip) parts.append(String(b, start + skip, len - skip, Charsets.UTF_8))
                    }
                    pos += 2 + length
                }
                parts.toString().ifEmpty { null }
            }
            isPng(b) -> {
                var pos = 8
                var found: String? = null
                while (pos + 12 <= b.size && found == null) {
                    val len = u32be(b, pos).toInt()
                    if (len < 0 || pos + 12 + len > b.size) break
                    if (ascii(b, pos + 4, 4) == "iTXt") {
                        val text = String(b, pos + 8, len, Charsets.UTF_8)
                        if (text.startsWith("XML:com.adobe.xmp")) found = text.substring(text.indexOf('<').coerceAtLeast(0))
                    }
                    pos += 12 + len
                }
                found
            }
            isWebp(b) -> {
                var pos = 12
                var found: String? = null
                while (pos + 8 <= b.size && found == null) {
                    val len = u32le(b, pos + 4).toInt()
                    if (len < 0 || pos + 8 + len > b.size) break
                    if (ascii(b, pos, 4) == "XMP ") found = String(b, pos + 8, len, Charsets.UTF_8)
                    pos += 8 + len + (len and 1)
                }
                found
            }
            else -> null
        }
    }.getOrNull()

    /** Textos de un PNG (tEXt e iTXt que no son XMP): autor, descripción, programa... */
    fun pngTexts(b: ByteArray): List<Pair<String, String>> {
        if (!isPng(b)) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        var pos = 8
        while (pos + 12 <= b.size) {
            val len = u32be(b, pos).toInt()
            if (len < 0 || pos + 12 + len > b.size) break
            val type = ascii(b, pos + 4, 4)
            if (type == "tEXt" || type == "iTXt") {
                val raw = String(b, pos + 8, len, if (type == "tEXt") Charsets.ISO_8859_1 else Charsets.UTF_8)
                val key = raw.substringBefore('\u0000')
                if (key != "XML:com.adobe.xmp") {
                    val value = if (type == "tEXt") raw.substringAfter('\u0000') else raw.split('\u0000').drop(4).joinToString("").ifEmpty { raw.substringAfterLast('\u0000') }
                    if (value.isNotBlank()) out += key to value.trim()
                }
            }
            if (type == "IEND") break
            pos += 12 + len
        }
        return out
    }

    /**
     * Saca del XMP una lista legible de «nombre: valor» (atributos como
     * xmp:Rating="5" y elementos como <dc:creator>...</dc:creator>).
     */
    fun xmpFields(xmp: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        fun add(key: String, value: String) {
            val v = value.trim()
            if (v.isEmpty() || key.startsWith("xmlns") || key.startsWith("rdf:") || key.startsWith("x:") || key.startsWith("xml:")) return
            val list = out.getOrPut(key) { mutableListOf() }
            if (v !in list) list += v
        }
        val stack = ArrayList<String>()
        TAG.findAll(xmp).forEach { m ->
            val text = m.groupValues[5]
            if (text.isNotEmpty()) {
                val key = stack.lastOrNull { !it.startsWith("rdf:") && !it.startsWith("x:") }
                if (key != null && text.isNotBlank()) add(key, unescape(text))
                return@forEach
            }
            val name = m.groupValues[2]
            if (m.groupValues[1] != "/") ATTR.findAll(m.groupValues[3]).forEach { add(it.groupValues[1], unescape(it.groupValues[2])) }
            when {
                m.groupValues[1] == "/" -> {
                    val i = stack.lastIndexOf(name)
                    if (i >= 0) while (stack.size > i) stack.removeAt(stack.size - 1)
                }
                m.groupValues[4] == "/" -> Unit
                else -> stack += name
            }
        }
        return out.map { (k, v) -> k to v.joinToString(", ") }
    }

    private val TAG = Regex("<(/?)([A-Za-z][\\w.-]*:[\\w.-]+)([^>]*?)(/?)>|([^<]+)")
    private val ATTR = Regex("([A-Za-z][\\w.-]*:[\\w.-]+)\\s*=\\s*\"([^\"]*)\"")

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#xA;", "\n").replace("&amp;", "&")

    // ---- JPEG ----

    private fun jpeg(b: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var pos = 2
        var removed = false
        var orientationWritten = false
        // Si hay MPF (fotos HDR / varias imágenes), sus posiciones dependen de lo
        // que haya después; a partir de ahí no se quita nada.
        var afterMpf = false
        while (pos + 4 <= b.size) {
            if (b[pos] != 0xFF.toByte()) return null // estructura rara: mejor no tocar
            val marker = b[pos + 1].toInt() and 0xFF
            if (marker == 0xFF) { pos++; continue } // relleno
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                out.write(b, pos, 2); pos += 2; continue
            }
            if (marker == 0xDA || marker == 0xD9) {
                // Empieza la imagen (o termina): el resto se copia tal cual.
                out.write(b, pos, b.size - pos)
                pos = b.size
                break
            }
            val length = u16be(b, pos + 2)
            if (length < 2 || pos + 2 + length > b.size) return null
            val start = pos + 4
            val dataLen = length - 2
            var drop = false
            var keepOrientation = 0
            if (!afterMpf) when (marker) {
                0xE1 -> when {
                    startsWith(b, start, dataLen, EXIF_ID) -> {
                        drop = true
                        keepOrientation = exifOrientation(b, start + EXIF_ID.size, dataLen - EXIF_ID.size)
                    }
                    startsWith(b, start, dataLen, XMP_ID) || startsWith(b, start, dataLen, XMP_EXT_ID) ->
                        drop = !isSpecialXmp(b, start, dataLen)
                }
                0xED, 0xFE -> drop = true // IPTC de Photoshop y comentarios
                0xE2 -> if (startsWith(b, start, dataLen, MPF_ID)) afterMpf = true
            }
            if (drop) {
                removed = true
                if (keepOrientation in 2..8 && !orientationWritten) {
                    out.write(minimalExif(keepOrientation))
                    orientationWritten = true
                }
            } else {
                out.write(b, pos, 2 + length)
            }
            pos += 2 + length
        }
        if (pos < b.size) out.write(b, pos, b.size - pos)
        return if (removed) out.toByteArray() else null
    }

    /** XMP que hace falta para las fotos en movimiento y HDR (y no lleva ubicación). */
    private fun isSpecialXmp(b: ByteArray, start: Int, len: Int): Boolean {
        val text = String(b, start, len, Charsets.ISO_8859_1)
        val special = text.contains("hdrgm:") || text.contains("Container:Directory") ||
            text.contains("GCamera:MotionPhoto") || text.contains("GCamera:MicroVideo")
        return special && !text.contains("GPS")
    }

    /** Lee el giro (etiqueta 0x0112) de un bloque EXIF. 0 si no hay. */
    internal fun exifOrientation(b: ByteArray, tiff: Int, len: Int): Int {
        if (len < 8) return 0
        val little = when (ascii(b, tiff, 2)) {
            "II" -> true
            "MM" -> false
            else -> return 0
        }
        fun u16(p: Int) = if (little) u16le(b, p) else u16be(b, p)
        fun u32(p: Int) = if (little) u32le(b, p) else u32be(b, p)
        val ifd = tiff + u32(tiff + 4).toInt()
        if (ifd < tiff + 8 || ifd + 2 > tiff + len) return 0
        val count = u16(ifd)
        for (i in 0 until count) {
            val e = ifd + 2 + i * 12
            if (e + 12 > tiff + len) return 0
            if (u16(e) == 0x0112) return u16(e + 8)
        }
        return 0
    }

    /** Bloque EXIF nuevo con una sola etiqueta: el giro. */
    internal fun minimalExif(orientation: Int): ByteArray {
        val payload = byteArrayOf(
            0x45, 0x78, 0x69, 0x66, 0, 0, // "Exif\0\0"
            0x4D, 0x4D, 0x00, 0x2A, 0, 0, 0, 8, // TIFF big-endian, IFD0 en 8
            0, 1, // una etiqueta
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation = SHORT
            0, 0, 0, 0, // no hay más IFD
        )
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    // ---- PNG ----

    private fun png(b: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var pos = 8
        var removed = false
        while (pos + 12 <= b.size) {
            val len = u32be(b, pos)
            if (len < 0 || len > b.size) return null
            val total = 12 + len.toInt()
            if (pos + total > b.size) return null
            val type = ascii(b, pos + 4, 4)
            if (type in PNG_DROP) {
                removed = true
            } else {
                out.write(b, pos, total)
            }
            pos += total
            if (type == "IEND") break
        }
        if (pos < b.size) out.write(b, pos, b.size - pos)
        return if (removed) out.toByteArray() else null
    }

    // ---- WebP ----

    private fun webp(b: ByteArray): ByteArray? {
        val body = ByteArrayOutputStream(b.size)
        var pos = 12
        val end = minOf(b.size, 8 + u32le(b, 4).toInt())
        var removed = false
        var vp8xFlagsAt = -1
        while (pos + 8 <= end) {
            val type = ascii(b, pos, 4)
            val len = u32le(b, pos + 4).toInt()
            if (len < 0) return null
            val total = 8 + len + (len and 1)
            if (pos + 8 + len > end) return null
            val chunkEnd = minOf(pos + total, end)
            if (type == "EXIF" || type == "XMP ") {
                removed = true
            } else {
                if (type == "VP8X") vp8xFlagsAt = body.size() + 8
                body.write(b, pos, chunkEnd - pos)
            }
            pos = chunkEnd
        }
        if (!removed) return null
        val chunks = body.toByteArray()
        if (vp8xFlagsAt in chunks.indices) {
            chunks[vp8xFlagsAt] = (chunks[vp8xFlagsAt].toInt() and 0x08.inv() and 0x04.inv()).toByte()
        }
        val out = ByteArrayOutputStream(chunks.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val size = chunks.size + 4
        out.write(byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte()))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(chunks)
        return out.toByteArray()
    }

    // ---- Ayudas ----

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_DROP = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")
    private val EXIF_ID = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val XMP_EXT_ID = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val MPF_ID = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun startsWith(b: ByteArray, start: Int, len: Int, prefix: ByteArray): Boolean {
        if (len < prefix.size || start + prefix.size > b.size) return false
        for (i in prefix.indices) if (b[start + i] != prefix[i]) return false
        return true
    }

    private fun ascii(b: ByteArray, start: Int, len: Int) =
        if (start + len > b.size) "" else String(b, start, len, Charsets.ISO_8859_1)

    private fun u16be(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u16le(b: ByteArray, p: Int) = ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p].toInt() and 0xFF)
    private fun u32be(b: ByteArray, p: Int): Long = (u16be(b, p).toLong() shl 16) or u16be(b, p + 2).toLong()
    private fun u32le(b: ByteArray, p: Int): Long = (u16le(b, p + 2).toLong() shl 16) or u16le(b, p).toLong()
}
