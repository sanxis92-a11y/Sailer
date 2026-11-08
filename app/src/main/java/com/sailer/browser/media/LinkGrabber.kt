package com.sailer.browser.media

import android.content.Context
import com.sailer.browser.download.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
)

/**
 * Recolector al estilo JDownloader (solo versión personal): lee el HTML de una
 * página, saca sus imágenes (la versión grande si la miniatura enlaza a ella),
 * vídeos y audios con enlace directo, y sigue los botones «Siguiente» para
 * recorrer galerías de varias páginas.
 */
class LinkGrabber(private val context: Context, private val userAgent: String) {

    /** Resultado de leer una página: lo encontrado y la dirección de la página siguiente. */
    private class Page(val items: List<GrabItem>, val next: String?)

    /**
     * Recorre [startUrl] y sus páginas siguientes (hasta [maxPages]). [onPage]
     * recibe lo nuevo de cada página según se va leyendo.
     */
    suspend fun crawl(
        startUrl: String,
        maxPages: Int = MAX_PAGES,
        onPage: suspend (pageNumber: Int, newItems: List<GrabItem>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val visited = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        var url: String? = startUrl
        var number = 0
        while (url != null && number < maxPages && visited.add(url)) {
            coroutineContext.ensureActive()
            number++
            val page = runCatching { readPage(url) }.getOrNull() ?: break
            val fresh = page.items.filter { seen.add(it.url) }
            onPage(number, fresh)
            url = page.next
        }
    }

    /** Un enlace pegado: si ya es un archivo, se devuelve tal cual; si es una página, se recorre. */
    fun directItem(url: String): GrabItem? {
        val kind = MediaSniffer.kindForExtension(MediaSniffer.extensionOf(url)) ?: return null
        return GrabItem(url, kind)
    }

    private fun readPage(pageUrl: String): Page {
        val base = pageUrl.toHttpUrlOrNull() ?: return Page(emptyList(), null)
        val request = Request.Builder().url(base).header("User-Agent", userAgent).build()
        val html = HttpClients.get(context).newCall(request).execute().use { response ->
            if (!response.isSuccessful) return Page(emptyList(), null)
            val type = response.header("Content-Type").orEmpty()
            if (!type.contains("html", ignoreCase = true)) return Page(emptyList(), null)
            response.body?.string().orEmpty().take(MAX_HTML)
        }
        return Page(findItems(html, base, pageUrl), findNext(html, base))
    }

    private fun findItems(html: String, base: HttpUrl, pageUrl: String): List<GrabItem> {
        val items = linkedMapOf<String, GrabItem>()
        fun add(raw: String?, kind: MediaKind?) {
            val url = resolve(base, raw) ?: return
            if (kind == null || items.containsKey(url) || items.size >= MAX_ITEMS) return
            if (kind == MediaKind.IMAGE && JUNK.containsMatchIn(url.substringAfterLast('/'))) return
            items[url] = GrabItem(url, kind, referer = pageUrl)
        }

        // 1) Miniaturas que enlazan a la foto grande: se guarda la grande y se ignora la miniatura.
        val thumbsInLinks = mutableSetOf<String>()
        ANCHOR.findAll(html).forEach { match ->
            val href = attr(match.groupValues[1], "href") ?: return@forEach
            val kind = kindOf(resolve(base, href))
            if (kind != null) {
                add(href, kind)
                IMG.findAll(match.groupValues[2]).forEach { img -> imageSource(img.value)?.let { thumbsInLinks.add(it) } }
            }
        }
        // 2) El resto de imágenes de la página (la versión más grande del srcset).
        IMG.findAll(html).forEach { img ->
            val src = imageSource(img.value) ?: return@forEach
            if (src !in thumbsInLinks) add(src, MediaKind.IMAGE)
        }
        // 3) Vídeos y audios con enlace directo, y las imágenes/vídeos de portada.
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
        return items.values.filter { it.kind != MediaKind.HLS && it.kind != MediaKind.DASH || MediaSniffer.classify(it.url) != null }
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

    private fun imageSource(tag: String): String? =
        largest(attr(tag, "srcset") ?: attr(tag, "data-srcset"))
            ?: attr(tag, "data-src") ?: attr(tag, "data-original") ?: attr(tag, "data-lazy-src")
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
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:") && !it.startsWith("javascript:") } ?: return null
        val url = base.resolve(value) ?: return null
        if (!url.isHttps) return url.newBuilder().scheme("https").build().toString()
        return url.toString()
    }

    private fun decodeEntities(text: String) =
        text.replace("&amp;", "&").replace("&#038;", "&").replace("&quot;", "\"").replace("&#39;", "'")

    companion object {
        const val MAX_PAGES = 30
        private const val MAX_ITEMS = 1000
        private const val MAX_HTML = 3_000_000

        private val ANCHOR = Regex("""<a(\s[^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val IMG = Regex("""<img\s[^>]*>""", RegexOption.IGNORE_CASE)
        private val MEDIA_TAG = Regex("""<(video|audio|source)\s[^>]*>""", RegexOption.IGNORE_CASE)
        private val META = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
        private val LINK_TAG = Regex("""<link\s[^>]*>""", RegexOption.IGNORE_CASE)
        private val TAGS = Regex("<[^>]*>")
        private val NEXT_TEXT = Regex(
            """(next|next page|siguiente|página siguiente|pagina siguiente|sig\.?|older|más|›|»|>|→|>>|»»)\s*[›»>→]?""",
            RegexOption.IGNORE_CASE,
        )
        private val NEXT_CLASS = Regex("""(^|[\s_-])(next|siguiente)([\s_-]|$)""", RegexOption.IGNORE_CASE)
        private val JUNK = Regex("""(logo|icon|avatar|sprite|pixel|blank|spacer|placeholder|loading)""", RegexOption.IGNORE_CASE)

        private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

        /** Saca todas las direcciones de un texto pegado (una por línea, separadas por espacios…). */
        fun urlsIn(text: String): List<String> =
            URL_IN_TEXT.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.distinct().toList()
    }
}
