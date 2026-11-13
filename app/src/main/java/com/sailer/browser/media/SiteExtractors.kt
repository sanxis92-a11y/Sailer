package com.sailer.browser.media

import android.content.Context
import com.sailer.browser.download.HttpClients
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Piezas especiales para webs muy usadas (como hacen gallery-dl o JDownloader):
 * en lugar de leer la página, se pide a la web la lista de publicaciones, que
 * trae la foto original y el enlace a la página siguiente.
 *
 *  - Reddit: subforos, usuarios, búsquedas y publicaciones (también galerías).
 *  - Redgifs: el vídeo en HD de cada enlace.
 *  - Imgur: los .gifv se cambian por su vídeo .mp4.
 */
class SiteExtractors(private val context: Context, private val userAgent: String) {

    /** null = esta web no tiene pieza especial (se lee como siempre). */
    fun read(pageUrl: String): GrabPage? {
        val url = pageUrl.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase()
        return when {
            host == "reddit.com" || host.endsWith(".reddit.com") -> runCatching { reddit(pageUrl) }.getOrNull()
            host == "redgifs.com" || host.endsWith(".redgifs.com") ->
                redgifsId(pageUrl)?.let { id -> redgif(id, pageUrl, "")?.let { GrabPage(listOf(it), emptyList(), null, "Redgifs") } }
            else -> null
        }
    }

    // ---- Reddit ----

    private fun reddit(pageUrl: String): GrabPage? {
        val api = redditApiUrl(pageUrl) ?: return null
        val text = get(api) ?: return null
        val listings = text.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONArray().put(JSONObject(it)) }
        val items = mutableListOf<GrabItem>()
        var after: String? = null
        var title = ""
        for (l in 0 until listings.length()) {
            val data = listings.optJSONObject(l)?.optJSONObject("data") ?: continue
            if (l == 0) after = data.optString("after").takeIf { it.isNotEmpty() && it != "null" }
            val children = data.optJSONArray("children") ?: continue
            for (c in 0 until children.length()) {
                val child = children.optJSONObject(c) ?: continue
                if (child.optString("kind") != "t3") continue
                val post = child.optJSONObject("data") ?: continue
                val postTitle = post.optString("title")
                if (title.isEmpty()) title = post.optString("subreddit_name_prefixed").ifEmpty { postTitle }
                val permalink = "https://www.reddit.com" + post.optString("permalink")
                items.addAll(redditPost(post, permalink, postTitle))
            }
        }
        // En una publicación suelta no hay «siguiente».
        val isPost = Regex("/comments/").containsMatchIn(pageUrl)
        val next = if (!isPost && after != null) withQuery(pageUrl, "after", after) else null
        return GrabPage(items, emptyList(), next, title)
    }

    private fun redditPost(post: JSONObject, permalink: String, title: String): List<GrabItem> {
        val out = mutableListOf<GrabItem>()
        fun add(url: String?, kind: MediaKind, w: Int = 0, h: Int = 0) {
            val clean = url?.replace("&amp;", "&")?.takeIf { it.startsWith("https://") } ?: return
            out.add(GrabItem(clean, kind, w, h, referer = permalink, album = title))
        }
        // Galería: cada foto en su tamaño original.
        val meta = post.optJSONObject("media_metadata")
        if (meta != null && meta.length() > 0) {
            val order = post.optJSONObject("gallery_data")?.optJSONArray("items")
            val ids = if (order != null) (0 until order.length()).mapNotNull { order.optJSONObject(it)?.optString("media_id") }
            else meta.keys().asSequence().toList()
            ids.forEach { id ->
                val m = meta.optJSONObject(id) ?: return@forEach
                val s = m.optJSONObject("s") ?: return@forEach
                when (m.optString("e")) {
                    "AnimatedImage" -> {
                        val mp4 = s.optString("mp4")
                        if (mp4.isNotEmpty()) add(mp4, MediaKind.VIDEO) else add(s.optString("gif"), MediaKind.IMAGE)
                    }
                    else -> add(s.optString("u"), MediaKind.IMAGE, s.optInt("x"), s.optInt("y"))
                }
            }
            return out
        }
        // Vídeo de Reddit: la lista HLS lleva el sonido junto.
        val video = post.optJSONObject("secure_media")?.optJSONObject("reddit_video")
            ?: post.optJSONObject("media")?.optJSONObject("reddit_video")
        if (post.optBoolean("is_video") && video != null) {
            val hls = video.optString("hls_url").ifEmpty { null }
            if (hls != null) add(hls, MediaKind.HLS, video.optInt("width"), video.optInt("height"))
            else add(video.optString("fallback_url"), MediaKind.VIDEO, video.optInt("width"), video.optInt("height"))
            return out
        }
        val link = post.optString("url_overridden_by_dest").ifEmpty { post.optString("url") }
        val host = link.toHttpUrlOrNull()?.host.orEmpty()
        when {
            host.endsWith("imgur.com") && link.endsWith(".gifv") -> add(link.removeSuffix(".gifv") + ".mp4", MediaKind.VIDEO)
            host.endsWith("redgifs.com") -> redgifsId(link)?.let { id -> redgif(id, permalink, title)?.let { out.add(it) } }
            MediaSniffer.kindForExtension(MediaSniffer.extensionOf(link)) == MediaKind.IMAGE -> add(link, MediaKind.IMAGE)
            MediaSniffer.kindForExtension(MediaSniffer.extensionOf(link)) == MediaKind.VIDEO -> add(link, MediaKind.VIDEO)
            else -> {
                // Enlace a otra web: al menos la imagen de vista previa a tamaño completo.
                val source = post.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source")
                if (source != null && post.optString("post_hint") == "image") {
                    add(source.optString("url"), MediaKind.IMAGE, source.optInt("width"), source.optInt("height"))
                }
            }
        }
        return out
    }

    /** Dirección de la lista en JSON de una página de Reddit (100 publicaciones por vez). */
    private fun redditApiUrl(pageUrl: String): String? {
        val url = pageUrl.toHttpUrlOrNull() ?: return null
        val path = url.encodedPath.trimEnd('/').let { if (it.endsWith(".json")) it else "$it.json" }
        val builder = url.newBuilder().host("www.reddit.com").encodedPath(if (path == ".json") "/.json" else path)
            .setQueryParameter("raw_json", "1")
        if (url.queryParameter("limit") == null) builder.setQueryParameter("limit", "100")
        return builder.build().toString()
    }

    private fun withQuery(pageUrl: String, key: String, value: String): String? =
        pageUrl.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter(key, value)?.build()?.toString()

    // ---- Redgifs ----

    private fun redgifsId(url: String): String? =
        Regex("""redgifs\.com/(?:watch|ifr|i)/([A-Za-z]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()

    private fun redgif(id: String, referer: String, title: String): GrabItem? {
        val token = redgifsToken() ?: return null
        val text = get("https://api.redgifs.com/v2/gifs/$id", mapOf("Authorization" to "Bearer $token")) ?: return null
        val gif = JSONObject(text).optJSONObject("gif") ?: return null
        val urls = gif.optJSONObject("urls") ?: return null
        val best = urls.optString("hd").ifEmpty { urls.optString("sd") }.takeIf { it.startsWith("https://") } ?: return null
        return GrabItem(best, MediaKind.VIDEO, gif.optInt("width"), gif.optInt("height"), referer = referer, album = title)
    }

    private fun redgifsToken(): String? {
        synchronized(Companion) {
            val now = System.currentTimeMillis()
            if (cachedToken != null && now - tokenTime < 20 * 60_000) return cachedToken
            val text = get("https://api.redgifs.com/v2/auth/temporary") ?: return null
            cachedToken = runCatching { JSONObject(text).optString("token").ifEmpty { null } }.getOrNull()
            tokenTime = now
            return cachedToken
        }
    }

    // ---- Red ----

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return HttpClients.get(context).newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.take(8_000_000)
        }
    }

    companion object {
        @Volatile private var cachedToken: String? = null
        @Volatile private var tokenTime = 0L
    }
}
