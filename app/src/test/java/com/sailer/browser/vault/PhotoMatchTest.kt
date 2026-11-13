package com.sailer.browser.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.random.Random

class PhotoMatchTest {

    /** Una «foto» con formas al azar. */
    private fun photo(seed: Int, w: Int = 400, h: Int = 300): BufferedImage {
        val r = Random(seed)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))
        g.fillRect(0, 0, w, h)
        repeat(25) {
            g.color = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            g.fillOval(r.nextInt(w), r.nextInt(h), 20 + r.nextInt(w / 2), 20 + r.nextInt(h / 2))
        }
        g.dispose()
        return img
    }

    private fun scaled(src: BufferedImage, w: Int, h: Int, dx: Int = 0): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, dx, 0, w, h, null)
        g.dispose()
        return img
    }

    private fun blur(src: BufferedImage, radius: Int): BufferedImage {
        val k = 2 * radius + 1
        val op = java.awt.image.ConvolveOp(java.awt.image.Kernel(k, k, FloatArray(k * k) { 1f / (k * k) }), java.awt.image.ConvolveOp.EDGE_NO_OP, null)
        return op.filter(src, null)
    }

    private fun rotate90(src: BufferedImage): BufferedImage {
        val img = BufferedImage(src.height, src.width, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until src.height) for (x in 0 until src.width) img.setRGB(src.height - 1 - y, x, src.getRGB(x, y))
        return img
    }

    private var created = 1_000_000L

    private fun info(id: String, img: BufferedImage, bytes: Long = 1000): PhotoMatch.Info {
        val gray = IntArray(img.width * img.height) { PhotoMatch.luma(img.getRGB(it % img.width, it / img.width)) }
        created += 1000
        return PhotoMatch.Info(id, img.width, img.height, bytes, created, PhotoMatch.sharpness(gray, img.width, img.height), PhotoMatch.gridOf(gray, img.width, img.height))
    }

    @Test
    fun findsResizedCopyAndKeepsTheBigOne() {
        val a = photo(1)
        val infos = listOf(info("big", a), info("small", scaled(a, 200, 150)), info("other", photo(2)), info("other2", photo(3)))
        val groups = PhotoMatch.duplicates(infos, PhotoMatch.Options(), PhotoMatch.Keep.QUALITY, emptySet())
        assertEquals(1, groups.size)
        assertEquals(setOf("big", "small"), groups[0].items.map { it.id }.toSet())
        assertEquals("big", groups[0].keep.id)
    }

    @Test
    fun ignoredPairsAreNotGrouped() {
        val a = photo(1)
        val infos = listOf(info("x", a), info("y", scaled(a, 200, 150)))
        assertTrue(PhotoMatch.duplicates(infos, PhotoMatch.Options(), PhotoMatch.Keep.QUALITY, setOf(PhotoMatch.pairKey("y", "x"))).isEmpty())
    }

    @Test
    fun rotatedCopyOnlyWithOption() {
        val a = photo(4)
        val infos = listOf(info("a", a), info("r", rotate90(a)))
        assertTrue(PhotoMatch.duplicates(infos, PhotoMatch.Options(rotations = false), PhotoMatch.Keep.QUALITY, emptySet()).isEmpty())
        assertEquals(1, PhotoMatch.duplicates(infos, PhotoMatch.Options(rotations = true), PhotoMatch.Keep.QUALITY, emptySet()).size)
    }

    @Test
    fun sameSceneFindsSlightlyMovedShot() {
        val a = photo(5)
        val moved = scaled(a, 400, 300, dx = 14)
        val infos = listOf(info("a", a), info("b", moved), info("c", photo(6)), info("d", photo(7)), info("e", photo(8)))
        val d = PhotoMatch.distance(infos[0].hash, infos[1].hash)
        println("distancia escena movida: $d, correlación ${PhotoMatch.correlation(infos[0].grid, infos[1].grid)}")
        for (i in 2 until 5) println("distancia distinta: ${PhotoMatch.distance(infos[0].hash, infos[i].hash)} corr ${PhotoMatch.correlation(infos[0].grid, infos[i].grid)}")
        val scenes = PhotoMatch.sameScene(infos, PhotoMatch.Options(), PhotoMatch.Keep.QUALITY, emptySet())
        assertEquals(1, scenes.size)
        assertEquals(setOf("a", "b"), scenes[0].items.map { it.id }.toSet())
    }

    @Test
    fun blurryPhotosAreDetected() {
        val sharp = info("sharp", photo(9))
        val soft = info("soft", blur(photo(9), 6))
        println("nitidez: ${sharp.sharpness} vs ${soft.sharpness}")
        assertTrue(sharp.sharpness > soft.sharpness * 4)
        assertEquals(listOf("soft"), PhotoMatch.blurry(listOf(sharp, soft), 1).map { it.id })
    }
}
