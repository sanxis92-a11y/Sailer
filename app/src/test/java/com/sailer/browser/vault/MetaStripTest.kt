package com.sailer.browser.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO

class MetaStripTest {

    private fun image(): BufferedImage {
        val img = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 48) for (x in 0 until 64) img.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or ((x + y) * 2))
        return img
    }

    private fun encode(format: String): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image(), format, it) }.toByteArray()

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /** EXIF con giro 6 y una etiqueta GPS falsa. */
    private fun exif(): ByteArray {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, // II, IFD en 8
            2, 0,
            0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, // Orientation = 6
            0x25, 0x88.toByte(), 4, 0, 1, 0, 0, 0, 0x55, 0x55, 0, 0, // GPS IFD (falso)
            0, 0, 0, 0,
        )
        return "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff + "GPS 41.38N 2.17E".toByteArray()
    }

    private fun contains(hay: ByteArray, needle: String) = String(hay, Charsets.ISO_8859_1).contains(needle)

    @Test
    fun jpegLosesExifXmpAndCommentButKeepsOrientation() {
        val plain = encode("jpg")
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><exif:GPSLatitude>41</exif:GPSLatitude></x:xmpmeta>".toByteArray(Charsets.ISO_8859_1)
        val dirty = plain.copyOfRange(0, 2) + segment(0xE1, exif()) + segment(0xE1, xmp) +
            segment(0xFE, "secreto".toByteArray()) + plain.copyOfRange(2, plain.size)
        val clean = MetaStrip.strip(dirty)
        assertNotNull(clean)
        clean!!
        assertFalse(contains(clean, "GPS"))
        assertFalse(contains(clean, "xmpmeta"))
        assertFalse(contains(clean, "secreto"))
        assertTrue(contains(clean, "Exif"))
        val start = String(clean, Charsets.ISO_8859_1).indexOf("Exif") + 6
        assertEquals(6, MetaStrip.exifOrientation(clean, start, clean.size - start))
        assertNotNull(ImageIO.read(ByteArrayInputStream(clean)))
        assertEquals(plain.size + MetaStrip.minimalExif(6).size, clean.size)
    }

    @Test
    fun jpegWithoutMetadataIsLeftAlone() {
        assertNull(MetaStrip.strip(encode("jpg")))
    }

    @Test
    fun motionPhotoXmpIsKept() {
        val plain = encode("jpg")
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<GCamera:MotionPhoto>1</GCamera:MotionPhoto><Container:Directory/>".toByteArray(Charsets.ISO_8859_1)
        val dirty = plain.copyOfRange(0, 2) + segment(0xE1, exif()) + segment(0xE1, xmp) + plain.copyOfRange(2, plain.size)
        val clean = MetaStrip.strip(dirty)!!
        assertTrue(contains(clean, "MotionPhoto"))
        assertFalse(contains(clean, "GPS"))
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(t); update(data) }.value
        val len = data.size
        return byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()) + t + data +
            byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    @Test
    fun pngLosesTextChunks() {
        val plain = encode("png")
        val dirty = plain.copyOfRange(0, 33) + pngChunk("tEXt", "Author\u0000Josep".toByteArray(Charsets.ISO_8859_1)) +
            pngChunk("iTXt", "XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x/>".toByteArray(Charsets.ISO_8859_1)) +
            plain.copyOfRange(33, plain.size)
        assertNotNull(ImageIO.read(ByteArrayInputStream(dirty)))
        val clean = MetaStrip.strip(dirty)!!
        assertTrue(clean.contentEquals(plain))
    }

    @Test
    fun webpLosesExifAndXmpChunks() {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val len = data.size
            val pad = if (len % 2 == 1) byteArrayOf(0) else byteArrayOf()
            return type.toByteArray(Charsets.US_ASCII) + byteArrayOf(len.toByte(), (len shr 8).toByte(), 0, 0) + data + pad
        }
        val vp8x = chunk("VP8X", byteArrayOf(0x0C, 0, 0, 0, 9, 0, 0, 9, 0, 0))
        val vp8 = chunk("VP8 ", ByteArray(21) { it.toByte() })
        val body = vp8x + vp8 + chunk("EXIF", exif()) + chunk("XMP ", "<x>GPS</x>".toByteArray())
        val size = body.size + 4
        val dirty = "RIFF".toByteArray() + byteArrayOf(size.toByte(), (size shr 8).toByte(), 0, 0) + "WEBP".toByteArray() + body
        val clean = MetaStrip.strip(dirty)!!
        assertFalse(contains(clean, "GPS"))
        assertEquals(12 + vp8x.size + vp8.size, clean.size)
        assertEquals(clean.size - 8, (clean[4].toInt() and 0xFF) or ((clean[5].toInt() and 0xFF) shl 8))
        assertEquals(0, clean[20].toInt() and 0x0C)
    }

    @Test
    fun unknownFormatIsIgnored() {
        assertNull(MetaStrip.strip("hola".toByteArray()))
        assertNull(MetaStrip.strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x12, 0x34, 0x56)))
    }
}

class XmpReadTest {
    @Test
    fun readsXmpFieldsFromJpeg() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmp:Rating="5" exif:GPSLatitude="41,23.1N">
            <dc:creator><rdf:Seq><rdf:li>Ana &amp; Luis</rdf:li></rdf:Seq></dc:creator>
            <mwg-rs:Regions><rdf:Bag><rdf:li mwg-rs:Name="Marta"/></rdf:Bag></mwg-rs:Regions>
            </rdf:Description></rdf:RDF></x:xmpmeta>"""
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray()
        val len = payload.size + 2
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val read = MetaStrip.readXmp(jpeg)
        assertNotNull(read)
        val fields = MetaStrip.xmpFields(read!!).toMap()
        assertEquals("5", fields["xmp:Rating"])
        assertEquals("41,23.1N", fields["exif:GPSLatitude"])
        assertEquals("Ana & Luis", fields["dc:creator"])
        assertEquals("Marta", fields["mwg-rs:Name"])
    }
}
