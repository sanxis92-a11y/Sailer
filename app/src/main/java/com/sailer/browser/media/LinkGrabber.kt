package com.sailer.browser.media

import android.content.Context
import com.sailer.browser.download.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/** Algo descargable encontrado en una página o en un enlace pegado. */
data class GrabItem(
    val url: String,
    val kind: MediaKind,
    val width: Int = 0,
    val height: Int = 0,
    /** Página donde se encontró (algunas webs solo sirven la imagen si se indica). */
    val referer: String? = null,
    /** Título de esa página: nombre de la galería al guardar. */
    val album: String? = null,
)

/** Lo encontrado en una página: archivos, páginas de detalle de cada miniatura y la página siguiente. */
class GrabPage(val items: List<GrabItem>, val details: List<String>, val next: String?, val title: String)

/**
 * Recolector al estilo JDownloader (solo versión personal). De cada página saca:
 *  - imágenes de <img>, <picture>, srcset, atributos data-* (carga diferida,
 *    zoom, alta resolución), fondos CSS y direcciones escondidas en scripts/JSON;
 *  - la versión grande cuando la miniatura enlaza a la foto, y descarta las
 *    variantes pequeñas de la misma imagen (-150x150, _thumb, /thumbs/…);
 *  - vídeos y audios con enlace directo;
 *  - las páginas de detalle de cada miniatura (análisis a fondo) y el botón
 *    «Siguiente» para recorrer galerías de varias páginas.
 */
class LinkGrabber(private val context: Context, private val userAgent: String) {

    /** Recorre [startUrl] y sus páginas siguientes (hasta [maxPages]); [onPage] recibe cada una. */
    suspend fun crawl(
        startUrl: String,
        maxPages: Int = MAX_PAGES,
        onPage: suspend (pageNumber: Int, page: GrabPage) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val visited = mutableSetOf<String>()
        var url: String? = startUrl
        var number = 0
        while (url != null && number < maxPages && visited.add(url)) {
            coroutineContext.ensureActive()
            number++
            val page = runCatching { readPage(url) }.getOrNull() ?: break
            onPage(number, page)
            url = page.next
        }
    }

    /**
     * Análisis a fondo: abre la página de detalle de cada miniatura y se queda
     * con su imagen principal (la grande). [onFound] se llama con cada una.
     */
    suspend fun deep(detailPages: List<String>, onFound: suspend (done: Int, item: GrabItem?) -> Unit) =
        withContext(Dispatchers.IO) {
            val limit = Semaphore(4)
            var done = 0
            coroutineScope {
                detailPages.take(MAX_DETAILS).map { pageUrl ->
                    async {
                        val item = limit.withPermit { runCatching { mainImage(pageUrl) }.getOrNull() }
                        synchronized(this@LinkGrabber) { done++ }
                        onFound(done, item)
                    }
                }.awaitAll()
            }
        }

    /** Un enlace pegado: si ya es un archivo, se devuelve tal cual. */
    fun directItem(url: String): GrabItem? {
        val kind = MediaSniffer.kindForExtension(MediaSniffer.extensionOf(url)) ?: return null
        return GrabItem(url, kind)
    }

    private fun fetchHtml(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return HttpClients.get(context).newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            if (!response.header("Content-Type").orEmpty().contains("html", ignoreCase = true)) return null
            response.body?.string().orEmpty().take(MAX_HTML)
        }
    }

    fun readPage(pageUrl: String): GrabPage {
        val base = pageUrl.toHttpUrlOrNull() ?: return GrabPage(emptyList(), emptyList(), null, "")
        val html = fetchHtml(base) ?: return GrabPage(emptyList(), emptyList(), null, "")
        val title = titleOf(html)
        val (items, details) = findItems(html, base, pageUrl, title)
        return GrabPage(items, details, findNext(html, base), title)
    }

    /** Imagen principal de una página de detalle: og:image, o la más grande de sus <img>. */
    private fun mainImage(pageUrl: String): GrabItem? {
        val base = pageUrl.toHttpUrlOrNull() ?: return null
        val html = fetchHtml(base) ?: return null
        val title = titleOf(html)
        META.findAll(html).forEach { tag ->
            val property = (attr(tag.value, "property") ?: attr(tag.value, "name"))?.lowercase()
            if (property == "og:image" || property == "og:image:secure_url" || property == "twitter:image") {
                resolve(base, attr(tag.value, "content"))?.let { return GrabItem(it, MediaKind.IMAGE, referer = pageUrl, album = title) }
            }
        }
        // Sin og:image: la imagen con más píxeles declarados, o la primera que no parezca un icono.
        var best: Pair<String, Int>? = null
        IMG.findAll(html).forEach { img ->
            val src = resolve(base, imageSource(img.value)) ?: return@forEach
            if (JUNK.containsMatchIn(src.substringAfterLast('/'))) return@forEach
            val area = (attr(img.value, "width")?.toIntOrNull() ?: 0) * (attr(img.value, "height")?.toIntOrNull() ?: 0)
            if (best == null || area > best!!.second) best = src to area
        }
        return best?.let { GrabItem(it.first, MediaKind.IMAGE, referer = pageUrl, album = title) }
    }

    private fun findItems(html: String, base: HttpUrl, pageUrl: String, title: String): Pair<List<GrabItem>, List<String>> {
        val items = linkedMapOf<String, GrabItem>()
        val details = linkedSetOf<String>()
        fun add(raw: String?, kind: MediaKind?) {
            val url = resolve(base, raw) ?: return
            if (kind == null || items.containsKey(url) || items.size >= MAX_ITEMS) return
            if (kind == MediaKind.IMAGE && JUNK.containsMatchIn(url.substringAfterLast('/'))) return
            items[url] = GrabItem(url, kind, referer = pageUrl, album = title)
        }

        // 1) Miniaturas con enlace: a la foto grande (se guarda la grande) o a su página de detalle.
        val thumbsInLinks = mutableSetOf<String>()
        ANCHOR.findAll(html).forEach { match ->
            val href = attr(match.groupValues[1], "href") ?: return@forEach
            val target = resolve(base, href) ?: return@forEach
            val kind = kindOf(target)
            val imgs = IMG.findAll(match.groupValues[2]).mapNotNull { imageSource(it.value) }.toList()
            if (kind != null) {
                add(href, kind)
                thumbsInLinks.addAll(imgs.mapNotNull { resolve(base, it) })
            } else if (imgs.isNotEmpty() && target.toHttpUrlOrNull()?.host == base.host && target != base.toString()) {
                details.add(target.substringBefore('#'))
            }
        }
        // 2) Imágenes de la página (la versión más grande del srcset) y de <picture>.
        IMG.findAll(html).forEach { img ->
            val src = imageSource(img.value) ?: return@forEach
            val resolved = resolve(base, src) ?: return@forEach
            if (resolved !in thumbsInLinks) add(src, MediaKind.IMAGE)
        }
        SOURCE_SET.findAll(html).forEach { tag -> add(largest(attr(tag.value, "srcset") ?: attr(tag.value, "data-srcset")), MediaKind.IMAGE) }
        // 3) Atributos de carga diferida, zoom o alta resolución en cualquier etiqueta.
        DATA_ATTR.findAll(html).forEach { m ->
            val value = decodeEntities(m.groupValues[2])
            if (kindOf(resolve(base, value)) == MediaKind.IMAGE) add(value, MediaKind.IMAGE)
        }
        // 4) Fondos CSS: style="background-image:url(...)" y hojas de estilo de la página.
        CSS_URL.findAll(html).forEach { m -> m.groupValues[1].let { if (kindOf(resolve(base, it)) == MediaKind.IMAGE) add(it, MediaKind.IMAGE) } }
        // 5) Vídeos y audios con enlace directo, y portada de la página.
        MEDIA_TAG.findAll(html).forEach { tag ->
            val src = attr(tag.value, "src") ?: return@forEach
            add(src, kindOf(resolve(base, src)) ?: if (tag.groupValues[1].equals("audio", true)) MediaKind.AUDIO else MediaKind.VIDEO)
        }
        META.findAll(html).forEach { tag ->
            val property = attr(tag.value, "property") ?: attr(tag.value, "name") ?: return@forEach
            val content = attr(tag.value, "content")
            when (property.lowercase()) {
                "og:image", "og:image:url", "og:image:secure_url" -> add(content, MediaKind.IMAGE)
                "og:video", "og:video:url", "og:video:secure_url" -> add(content, kindOf(resolve(base, content)) ?: MediaKind.VIDEO)
            }
        }
        // 6) Direcciones escondidas en scripts y JSON (galerías que se montan con JavaScript).
        RAW_URL.findAll(html.replace("\\/", "/")).forEach { m ->
            val url = m.value
            val kind = kindOf(url)
            if (kind == MediaKind.IMAGE || kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) add(url, kind)
        }
        val list = items.values.filter { it.kind != MediaKind.HLS && it.kind != MediaKind.DASH }
        return dropSmallVariants(list) to details.toList()
    }

    /** Busca el enlace a la página siguiente de la galería (misma web). */
    private fun findNext(html: String, base: HttpUrl): String? {
        val candidates = mutableListOf<String>()
        LINK_TAG.findAll(html).forEach { tag ->
            if (attr(tag.value, "rel")?.contains("next", true) == true) attr(tag.value, "href")?.let { candidates.add(it) }
        }
        ANCHOR.findAll(html).forEach { match ->
            val tag = match.groupValues[1]
            val href = attr(tag, "href") ?: return@forEach
            val text = TAGS.replace(match.groupValues[2], " ").replace("&raquo;", "»").replace("&rsaquo;", "›")
                .replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()
            val rel = attr(tag, "rel").orEmpty()
            val cls = attr(tag, "class").orEmpty() + " " + attr(tag, "aria-label").orEmpty()
            if (rel.contains("next", true) || NEXT_TEXT.matches(text) || NEXT_CLASS.containsMatchIn(cls)) candidates.add(href)
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            resolve(base, candidate)?.takeIf { it.toHttpUrlOrNull()?.host == base.host && it != base.toString() }
        }
    }

    private fun titleOf(html: String): String {
        META.findAll(html).forEach { tag ->
            if (attr(tag.value, "property")?.equals("og:title", true) == true) {
                attr(tag.value, "content")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return TITLE.find(html)?.groupValues?.get(1)?.let { decodeEntities(TAGS.replace(it, "")).trim() }.orEmpty()
    }

    private fun imageSource(tag: String): String? =
        largest(attr(tag, "srcset") ?: attr(tag, "data-srcset"))
            ?: attr(tag, "data-src") ?: attr(tag, "data-original") ?: attr(tag, "data-lazy-src") ?: attr(tag, "data-full")
            ?: attr(tag, "src")?.takeIf { !it.startsWith("data:") }

    private fun largest(srcset: String?): String? = srcset?.split(',')
        ?.mapNotNull { part ->
            val bits = part.trim().split(Regex("\\s+"))
            bits.firstOrNull()?.takeIf { it.isNotBlank() }?.let { it to (bits.getOrNull(1)?.dropLast(1)?.toFloatOrNull() ?: 1f) }
        }
        ?.maxByOrNull { it.second }?.first

    private fun kindOf(url: String?): MediaKind? = url?.let { MediaSniffer.kindForExtension(MediaSniffer.extensionOf(it)) }
        ?.takeIf { it != MediaKind.FILE }

    private fun attr(tag: String, name: String): String? =
        Regex("""\s$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)
            ?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
            ?.let(::decodeEntities)

    private fun resolve(base: HttpUrl, raw: String?): String? {
        val value = raw?.trim()?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() && !it.startsWith("data:") && !it.startsWith("javascript:") } ?: return null
        val url = base.resolve(value) ?: return null
        if (!url.isHttps) return url.newBuilder().scheme("https").build().toString()
        return url.toString()
    }

    private fun decodeEntities(text: String) =
        text.replace("&amp;", "&").replace("&#038;", "&").replace("&quot;", "\"").replace("&#39;", "'")

    companion object {
        const val MAX_PAGES = 30
        const val MAX_DETAILS = 200
        private const val MAX_ITEMS = 1500
        private const val MAX_HTML = 3_000_000

        private val ANCHOR = Regex("""<a(\s[^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val IMG = Regex("""<img\s[^>]*>""", RegexOption.IGNORE_CASE)
        private val SOURCE_SET = Regex("""<source\s[^>]*srcset[^>]*>""", RegexOption.IGNORE_CASE)
        private val DATA_ATTR = Regex(
            """\sdata-(src|original|full|large|zoom|zoom-image|hires|big|image|lazy|lazy-src|url|bg|background|href|file|high-res|orig)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val CSS_URL = Regex("""url\(\s*['"]?([^'")\s]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
        private val RAW_URL = Regex(
            """https?://[^\s"'<>()\\]+?\.(?:jpe?g|png|webp|gif|avif|mp4|webm|m4v|mp3|m4a)(?:\?[^\s"'<>()\\]*)?(?=["'\s<>)\\]|$)""",
            RegexOption.IGNORE_CASE,
        )
        private val MEDIA_TAG = Regex("""<(video|audio|source)\s[^>]*>""", RegexOption.IGNORE_CASE)
        private val META = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
        private val LINK_TAG = Regex("""<link\s[^>]*>""", RegexOption.IGNORE_CASE)
        private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAGS = Regex("<[^>]*>")
        private val NEXT_TEXT = Regex(
            """(next|next page|siguiente|página siguiente|pagina siguiente|sig\.?|older|más|›|»|>|→|>>|»»)\s*[›»>→]?""",
            RegexOption.IGNORE_CASE,
        )
        private val NEXT_CLASS = Regex("""(^|[\s_-])(next|siguiente)([\s_-]|$)""", RegexOption.IGNORE_CASE)
        private val JUNK = Regex("""(logo|icon|avatar|sprite|pixel|blank|spacer|placeholder|loading|emoji|badge|button)""", RegexOption.IGNORE_CASE)

        /** Marcas típicas de miniatura en la dirección de una imagen. */
        private val SIZE_MARKS = listOf(
            Regex("""-\d{2,4}x\d{2,4}(?=\.\w{3,4}(\?|$))"""),
            Regex("""[_-](thumb|thumbnail|small|medium|preview|tn|th|sm|t)(?=\.\w{3,4}(\?|$))""", RegexOption.IGNORE_CASE),
            Regex("""/(thumbs?|thumbnails?|small|preview|tn|th)/""", RegexOption.IGNORE_CASE),
            Regex("""\?.*$"""),
        )

        /** Clave de una imagen sin las marcas de tamaño (para juntar variantes de la misma foto). */
        fun variantKey(url: String): String = SIZE_MARKS.fold(url) { acc, r -> r.replace(acc, "/") }.lowercase()

        private fun thumbScore(url: String): Int = SIZE_MARKS.dropLast(1).count { it.containsMatchIn(url) }

        /** De varias versiones de la misma imagen, deja solo la que no parece miniatura. */
        fun dropSmallVariants(items: List<GrabItem>): List<GrabItem> {
            val best = linkedMapOf<String, GrabItem>()
            items.forEach { item ->
                val key = if (item.kind == MediaKind.IMAGE) variantKey(item.url) else item.url
                val current = best[key]
                if (current == null || thumbScore(item.url) < thumbScore(current.url) ||
                    item.width * item.height > current.width * current.height
                ) {
                    best[key] = item
                }
            }
            return best.values.toList()
        }

        private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

        /** Saca todas las direcciones de un texto pegado (una por línea, separadas por espacios…). */
        fun urlsIn(text: String): List<String> =
            URL_IN_TEXT.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.distinct().toList()
    }
}
