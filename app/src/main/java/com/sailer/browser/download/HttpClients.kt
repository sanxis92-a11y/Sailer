package com.sailer.browser.download

import android.content.Context
import android.webkit.CookieManager
import com.sailer.browser.settings.Prefs
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Cliente de red de las descargas, con DNS cifrado (DNS over HTTPS) opcional. */
object HttpClients {

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Cloudflare (1.1.1.1): las consultas DNS viajan cifradas por HTTPS. */
    val dohDns: Dns by lazy {
        DnsOverHttps.Builder()
            .client(base.newBuilder().cookieJar(CookieJar.NO_COOKIES).build())
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .build()
    }

    private val dohClient: OkHttpClient by lazy { base.newBuilder().dns(dohDns).build() }

    fun get(context: Context): OkHttpClient = if (Prefs.get(context).dohEnabled) dohClient else base

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
