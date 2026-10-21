package com.sailer.browser.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import java.io.File

/**
 * Modo incógnito permanente: nada de lo que hace el navegador debe sobrevivir
 * a una sesión. Hay dos momentos de limpieza:
 *  - [wipeSession]: al cerrar el navegador, con los WebView aún vivos.
 *  - [wipeDiskLeftovers]: al arrancar, por si la sesión anterior no terminó bien.
 */
object PrivacyManager {

    /** Borra cookies, almacenamiento web, caché, formularios e historial. */
    fun wipeSession(context: Context, webViews: List<WebView> = emptyList()) {
        webViews.forEach { webView ->
            webView.clearHistory()
            webView.clearFormData()
            webView.clearCache(true)
        }
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Elimina del disco todo lo que guarda WebView (cookies, IndexedDB,
     * localStorage, service workers, caché...). Solo debe llamarse antes de
     * crear el primer WebView del proceso.
     */
    fun wipeDiskLeftovers(context: Context) {
        File(context.dataDir, "app_webview").deleteRecursively()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
