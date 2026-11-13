package com.sailer.browser.vault

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Comparación de fotos para encontrar duplicadas, fotos de la misma escena y
 * fotos borrosas. Kotlin puro (sin Android) para poder probarlo en el ordenador.
 *
 * De cada foto se guarda una «rejilla»: la foto reducida a 16×16 cuadritos
 * en gris. A partir de ella se calcula la huella (dHash horizontal +
 * vertical, 128 bits): cada bit dice si un cuadrito es más claro que el de
 * al lado. Dos fotos iguales, aunque tengan distinto tamaño o compresión,
 * dan casi la misma huella.
 */
object PhotoMatch {

    const val GRID = 16

    /** Lo que se sabe de cada foto. */
    class Info(
        val id: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val created: Long,
        /** Nitidez: cuanto más baja, más borrosa. */
        val sharpness: Float,
        /** Rejilla 16×16 en gris (0-255). */
        val grid: ByteArray,
        /** Página de la que se descargó (si se sabe). */
        val source: String = "",
    ) {
        val pixels get() = width.toLong() * height
        internal val hash: LongArray by lazy { dHash(grid) }
        internal val variants: List<LongArray> by lazy { transforms(grid).map { dHash(it) } }
    }

    /** Cuánto se tienen que parecer: 0 estricto, 1 normal, 2 amplio. */
    class Options(
        val level: Int = 1,
        /** Buscar también fotos giradas o volteadas. */
        val rotations: Boolean = false,
        /** Misma escena solo si se guardaron con menos de 30 minutos de diferencia (o vienen de la misma página). */
        val timeLimit: Boolean = true,
    ) {
        val dupBits get() = intArrayOf(6, 10, 14)[level.coerceIn(0, 2)]
        val sceneBits get() = intArrayOf(28, 36, 44)[level.coerceIn(0, 2)]
        val sceneCorrelation get() = floatArrayOf(0.88f, 0.80f, 0.72f)[level.coerceIn(0, 2)]
    }

    // ---- Preparar ----

    /** Reduce una imagen en gris ([w]×[h]) a la rejilla de 16×16. */
    fun gridOf(gray: IntArray, w: Int, h: Int): ByteArray {
        val small = resize(gray, w, h, GRID, GRID)
        return ByteArray(GRID * GRID) { small[it].coerceIn(0, 255).toByte() }
    }

    /**
     * Nitidez con la varianza del laplaciano: en una foto nítida hay muchos
     * bordes marcados y el valor es alto; en una borrosa es bajo.
     */
    fun sharpness(gray: IntArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3) return 0f
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    /** Luminancia (0-255) de un color ARGB. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Reducción por media de áreas (para pasar de grande a pequeño). */
    fun resize(src: IntArray, w: Int, h: Int, tw: Int, th: Int): IntArray {
        val out = IntArray(tw * th)
        for (ty in 0 until th) {
            val y0 = ty * h / th
            val y1 = maxOf(y0 + 1, (ty + 1) * h / th)
            for (tx in 0 until tw) {
                val x0 = tx * w / tw
                val x1 = maxOf(x0 + 1, (tx + 1) * w / tw)
                var s = 0L
                for (y in y0 until minOf(y1, h)) for (x in x0 until minOf(x1, w)) s += src[y * w + x]
                val count = (minOf(y1, h) - y0) * (minOf(x1, w) - x0)
                out[ty * tw + tx] = if (count > 0) (s / count).toInt() else 0
            }
        }
        return out
    }

    // ---- Huellas ----

    internal fun dHash(grid: ByteArray): LongArray {
        val g = IntArray(grid.size) { grid[it].toInt() and 0xFF }
        val horizontal = resize(g, GRID, GRID, 9, 8)
        val vertical = resize(g, GRID, GRID, 8, 9)
        var a = 0L
        var b = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (horizontal[y * 9 + x] > horizontal[y * 9 + x + 1]) a = a or (1L shl bit)
            if (vertical[y * 8 + x] > vertical[(y + 1) * 8 + x]) b = b or (1L shl bit)
            bit++
        }
        return longArrayOf(a, b)
    }

    internal fun distance(a: LongArray, b: LongArray) =
        java.lang.Long.bitCount(a[0] xor b[0]) + java.lang.Long.bitCount(a[1] xor b[1])

    /** Las 7 formas de girar o voltear la rejilla (sin contar la original). */
    internal fun transforms(grid: ByteArray): List<ByteArray> {
        val n = GRID
        fun map(f: (Int, Int) -> Int) = ByteArray(n * n) { i -> grid[f(i % n, i / n)] }
        return listOf(
            map { x, y -> y * n + (n - 1 - x) },             // volteo horizontal
            map { x, y -> (n - 1 - y) * n + x },             // volteo vertical
            map { x, y -> (n - 1 - y) * n + (n - 1 - x) },   // 180°
            map { x, y -> x * n + y },                       // trasponer
            map { x, y -> (n - 1 - x) * n + y },             // 90°
            map { x, y -> x * n + (n - 1 - y) },             // 270°
            map { x, y -> (n - 1 - x) * n + (n - 1 - y) },   // antitrasponer
        )
    }

    private fun bestDistance(a: Info, b: Info, rotations: Boolean): Int {
        var d = distance(a.hash, b.hash)
        if (rotations && d > 0) b.variants.forEach { d = minOf(d, distance(a.hash, it)) }
        return d
    }

    /** Parecido de las rejillas (correlación, de -1 a 1), sin importar el brillo. */
    internal fun correlation(a: ByteArray, b: ByteArray): Float {
        val n = a.size
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) { ma += a[i].toInt() and 0xFF; mb += b[i].toInt() and 0xFF }
        ma /= n; mb /= n
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in 0 until n) {
            val da = (a[i].toInt() and 0xFF) - ma
            val db = (b[i].toInt() and 0xFF) - mb
            cov += da * db; va += da * da; vb += db * db
        }
        if (va < 1e-6 || vb < 1e-6) return if (abs(ma - mb) < 8) 1f else 0f
        return (cov / sqrt(va * vb)).toFloat()
    }

    // ---- Agrupar ----

    /** Un grupo de fotos parecidas; [keep] es la que se recomienda conservar. */
    class Group(val items: List<Info>, val keep: Info)

    /** Reglas para elegir cuál conservar. */
    enum class Keep { QUALITY, RESOLUTION, SIZE, OLDEST, NEWEST }

    fun duplicates(infos: List<Info>, options: Options, keep: Keep, ignored: Set<String>): List<Group> =
        group(infos, keep, ignored) { a, b -> bestDistance(a, b, options.rotations) <= options.dupBits }

    /**
     * Misma escena: fotos casi iguales pero no idénticas (una ráfaga, la misma
     * pose un segundo después...). Las que ya son duplicadas exactas no salen aquí.
     */
    fun sameScene(infos: List<Info>, options: Options, keep: Keep, ignored: Set<String>): List<Group> =
        group(infos, keep, ignored) { a, b ->
            if (options.timeLimit && abs(a.created - b.created) > 30 * 60_000L && (a.source.isEmpty() || a.source != b.source)) {
                return@group false
            }
            val d = distance(a.hash, b.hash)
            d in (options.dupBits + 1)..options.sceneBits && correlation(a.grid, b.grid) >= options.sceneCorrelation
        }

    /** Fotos borrosas (de la más borrosa a la menos). [level]: 0 solo muy borrosas, 1 normal, 2 también algo borrosas. */
    fun blurry(infos: List<Info>, level: Int): List<Info> {
        val limit = floatArrayOf(25f, 60f, 120f)[level.coerceIn(0, 2)]
        return infos.filter { it.sharpness < limit && it.width >= 64 && it.height >= 64 }.sortedBy { it.sharpness }
    }

    fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

    private fun group(infos: List<Info>, keep: Keep, ignored: Set<String>, same: (Info, Info) -> Boolean): List<Group> {
        val parent = IntArray(infos.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (i in infos.indices) for (j in i + 1 until infos.size) {
            val a = infos[i]
            val b = infos[j]
            if (find(i) == find(j)) continue
            if (pairKey(a.id, b.id) in ignored) continue
            if (same(a, b)) parent[find(i)] = find(j)
        }
        return infos.indices.groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { members ->
                val items = members.map { infos[it] }
                val best = best(items, keep)
                Group(listOf(best) + items.filter { it !== best }, best)
            }
            .sortedByDescending { it.items.size }
    }

    fun best(items: List<Info>, keep: Keep): Info = when (keep) {
        Keep.QUALITY -> items.maxWith(compareBy<Info>({ it.pixels }, { it.sharpness }, { it.bytes }))
        Keep.RESOLUTION -> items.maxWith(compareBy<Info>({ it.pixels }, { it.bytes }))
        Keep.SIZE -> items.maxWith(compareBy<Info>({ it.bytes }, { it.pixels }))
        Keep.OLDEST -> items.minBy { it.created }
        Keep.NEWEST -> items.maxBy { it.created }
    }
}
