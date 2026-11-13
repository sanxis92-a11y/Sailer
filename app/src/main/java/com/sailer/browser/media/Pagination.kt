package com.sailer.browser.media

/**
 * Encuentra la página siguiente de una galería por sus números, cuando no hay
 * un botón «Siguiente» claro: enlaces «1 2 3 … 20», ?page=2, /page/2/, -2.html,
 * ?start=40 (de 20 en 20)…
 *
 * Idea: de cada enlace de la misma web se saca una «plantilla» cambiando uno de
 * sus números por {n}. Si varios enlaces comparten plantilla y solo cambia ese
 * número, eso es la paginación. La página actual es el número que tiene la
 * dirección actual (o la primera si no lo tiene) y la siguiente, el que viene
 * después.
 */
object Pagination {

    /** Una forma de numerar páginas: la dirección con {n} y el salto entre una y otra. */
    data class Pattern(val template: String, val step: Int) {
        fun url(n: Int): String = template.replace(MARK, n.toString())
    }

    /** Resultado: la página siguiente y cómo se numeran (para seguir aunque falte el enlace). */
    data class Next(val url: String, val pattern: Pattern, val number: Int)

    private const val MARK = "{n}"
    private val NUMBER = Regex("""\d+""")

    /** Números que no son de página (años, códigos largos, tamaños de foto). */
    private fun plausible(n: Int) = n in 0..100_000

    /** Todas las plantillas de una dirección: una por cada número que tiene (sin tocar el dominio). */
    private fun templates(url: String): List<Pair<String, Int>> {
        val start = url.indexOf("://").let { if (it < 0) 0 else url.indexOf('/', it + 3).let { s -> if (s < 0) url.length else s } }
        return NUMBER.findAll(url).filter { it.range.first >= start }.mapNotNull { m ->
            val n = m.value.toIntOrNull()?.takeIf(::plausible) ?: return@mapNotNull null
            // Si el número es parte de un tamaño (800x600) o de una fecha larga, no es página.
            val before = url.getOrNull(m.range.first - 1)
            val after = url.getOrNull(m.range.last + 1)
            if (before == 'x' || after == 'x') return@mapNotNull null
            (url.substring(0, m.range.first) + MARK + url.substring(m.range.last + 1)) to n
        }.toList()
    }

    /**
     * Busca la siguiente página entre [links] (direcciones completas de la misma web)
     * estando en [current]. [known] es la numeración encontrada en la página anterior.
     */
    fun next(current: String, links: Collection<String>, known: Pattern? = null): Next? {
        val clean = current.substringBefore('#')
        val groups = HashMap<String, MutableSet<Int>>()
        links.forEach { link ->
            templates(link.substringBefore('#')).forEach { (t, n) -> groups.getOrPut(t) { sortedSetOf() }.add(n) }
        }
        val mine = templates(clean).toMap()

        // La numeración ya conocida tiene preferencia (es la que se estaba siguiendo).
        if (known != null) {
            val n = mine[known.template]
            if (n != null) {
                val wanted = n + known.step
                val nums = groups[known.template].orEmpty()
                if (wanted in nums || nums.any { it > n }) return Next(known.url(wanted), known, wanted)
            }
        }

        var best: Next? = null
        var bestScore = 0
        groups.forEach { (template, nums) ->
            if (nums.size < 2 && template !in mine) return@forEach
            val sorted = nums.sorted()
            val step = commonStep(sorted, mine[template]) ?: return@forEach
            val here = mine[template]
            val wanted = when {
                here != null -> here + step
                // La dirección actual no lleva número: es la primera página (o la 0 si se cuenta desde 0).
                step == 1 -> sorted.firstOrNull { it >= 2 } ?: return@forEach
                else -> sorted.firstOrNull { it > 0 } ?: return@forEach
            }
            if (wanted !in nums && (here == null || sorted.none { it > here })) return@forEach
            // Más enlaces con la misma forma = más seguro que es la paginación.
            val score = nums.size + (if (here != null) 2 else 0) + (if (wanted in nums) 3 else 0)
            if (score > bestScore) {
                bestScore = score
                best = Next(template.replace(MARK, wanted.toString()), Pattern(template, step), wanted)
            }
        }
        return best?.takeIf { it.url != clean }
    }

    /** Salto entre páginas: 1 (page=2, 3…) o el que más se repite (start=20, 40…). */
    private fun commonStep(sorted: List<Int>, here: Int?): Int? {
        val all = (sorted + listOfNotNull(here)).distinct().sorted()
        if (all.size < 2) return null
        val diffs = all.zipWithNext { a, b -> b - a }.filter { it > 0 }
        if (diffs.isEmpty()) return null
        if (1 in diffs) return 1
        val step = diffs.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        // Saltos raros (códigos de producto, años): no es paginación.
        return step.takeIf { it in 2..500 && diffs.count { d -> d % step == 0 } * 2 >= diffs.size }
    }

    /** Página siguiente «a ciegas» según la numeración, cuando ya no hay enlace. */
    fun guess(current: String, pattern: Pattern): Next? {
        val n = templates(current.substringBefore('#')).toMap()[pattern.template] ?: return null
        return Next(pattern.url(n + pattern.step), pattern, n + pattern.step)
    }
}
