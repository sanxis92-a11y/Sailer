package com.sailer.browser

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sailer.browser.adblock.AdBlocker
import com.sailer.browser.adblock.CosmeticFilter
import com.sailer.browser.diag.CrashLog
import com.sailer.browser.dns.WebDns
import com.sailer.browser.download.DownloadCenter
import com.sailer.browser.lock.AppLock
import com.sailer.browser.privacy.PrivacyManager
import com.sailer.browser.settings.Prefs
import com.sailer.browser.vault.Thumbnails

class SailerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Si la app se cerró de golpe la última vez (o Android la mató),
        // borramos aquí cualquier resto antes de que se cree ningún WebView.
        PrivacyManager.wipeDiskLeftovers(this)

        AdBlocker.enabled = Prefs.get(this).adBlockEnabled
        AdBlocker.load(this)
        CosmeticFilter.load(this)
        DownloadCenter.init(this)
        // DNS cifrado también para las páginas (si está activado).
        WebDns.apply(this)

        // Bloquear al abrir y cada vez que la app pasa a segundo plano.
        AppLock.lockIfEnabled(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppLock.onAppBackground(this@SailerApp)
                // Las miniaturas descifradas no se quedan en memoria con la app en segundo plano.
                Thumbnails.clear()
            }
        })
    }
}
