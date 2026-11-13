package com.sailer.browser.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhotoMatchTest {

    /** Imagen en gris hecha a mano (las pruebas no tienen java.awt en Android). */
    private class Gray(val w: Int, val h: Int, val px: IntArray = IntArray(w * h))

    /** Una «foto» con elipses al azar. */
    private fun photo(seed: Int, w: Int = 400, h: Int = 300): Gray {
        val r = Random(seed)
        val g = Gray(w, h)
        g.px.fill(r.nextInt(256))
        repeat(25) {
            val cx = r.nextInt(w)
            val cy = r.nextInt(h)
            val rx = 10 + r.nextInt(w / 4)
            val ry = 10 + r.nextInt(h / 4)
            val v = r.nextInt(256)
            for (y in maxOf(0, cy - ry) until minOf(h, cy + ry)) for (x in maxOf(0, cx - rx) until minOf(w, cx + rx)) {
                val dx = (x - cx).toDouble() / rx
                val dy = (y - cy).toDouble() / ry
                if (dx * dx + dy * dy <= 1) g.px[y * w + x] = v
            }
        }
        return g
    }

    /** Cambia el tamaño (vecino más cercano) y desplaza [dx] píxeles a la derecha. */
    private fun scaled(src: Gray, w: Int, h: Int, dx: Int = 0): Gray {
        val g = Gray(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = ((x - dx) * src.w / w).coerceIn(0, src.w - 1)
            g.px[y * w + x] = src.px[(y * src.h / h) * src.w + sx]
        }
        return g
    }

    private fun blur(src: Gray, radius: Int): Gray {
        val g = Gray(src.w, src.h)
        for (y in 0 until src.h) for (x in 0 until src.w) {
            var s = 0
            var n = 0
            for (yy in maxOf(0, y - radius)..minOf(src.h - 1, y + radius)) for (xx in maxOf(0, x - radius)..minOf(src.w - 1, x + radius)) {
                s += src.px[yy * src.w + xx]; n++
            }
            g.px[y * src.w + x] = s / n
        }
        return g
    }

    private fun rotate90(src: Gray): Gray {
        val g = Gray(src.h, src.w)
        for (y in 0 until src.h) for (x in 0 until src.w) g.px[x * src.h + (src.h - 1 - y)] = src.px[y * src.w + x]
        return g
    }

    private var created = 1_000_000L

    private fun info(id: String, img: Gray, bytes: Long = 1000): PhotoMatch.Info {
        created += 1000
        return PhotoMatch.Info(id, img.w, img.h, bytes, created, PhotoMatch.sharpness(img.px, img.w, img.h), PhotoMatch.gridOf(img.px, img.w, img.h))
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
        println("escena movida: distancia ${PhotoMatch.distance(infos[0].hash, infos[1].hash)}, correlación ${PhotoMatch.correlation(infos[0].grid, infos[1].grid)}")
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
