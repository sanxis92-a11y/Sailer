package com.sailer.browser.dns

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.sailer.browser.settings.Prefs
import java.util.concurrent.Executor

/** Activa o desactiva el DNS cifrado para las páginas web. */
object WebDns {

    private val direct = Executor { it.run() }

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun apply(context: Context) {
        if (!isSupported) return
        if (Prefs.get(context).dohEnabled) {
            val port = LocalDohProxy.start()
            val config = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
            ProxyController.getInstance().setProxyOverride(config, direct) {}
        } else {
            ProxyController.getInstance().clearProxyOverride(direct) {}
            LocalDohProxy.stop()
        }
    }
}
