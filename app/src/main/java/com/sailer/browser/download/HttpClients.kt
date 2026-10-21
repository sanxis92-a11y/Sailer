package com.sailer.browser.download

import android.content.Context
import android.webkit.CookieManager
import com.sailer.browser.settings.Prefs
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Cliente de red de las descargas, con DNS cifrado (DNS over HTTPS) opcional. */
object HttpClients {

    @Volatile
    private var cached: Pair<Boolean, OkHttpClient>? = null

    fun get(context: Context): OkHttpClient {
        val doh = Prefs.get(context).dohEnabled
        cached?.let { (cachedDoh, client) -> if (cachedDoh == doh) return client }
        val base = OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val client = if (doh) {
            // Cloudflare (1.1.1.1): las consultas DNS viajan cifradas por HTTPS.
            val dns = DnsOverHttps.Builder()
                .client(base)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                .build()
            base.newBuilder().dns(dns).build()
        } else {
            base
        }
        cached = doh to client
        return client
    }

    /** Usa las cookies de la sesión actual del navegador (nunca guarda nuevas). */
    private object WebViewCookieJar : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val header = runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()
                ?: return emptyList()
            return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    }
}
