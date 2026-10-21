package com.sailer.browser

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sailer.browser.adblock.AdBlocker
import com.sailer.browser.adblock.CosmeticFilter
import com.sailer.browser.download.DownloadCenter
import com.sailer.browser.lock.AppLock
import com.sailer.browser.privacy.PrivacyManager
import com.sailer.browser.settings.Prefs

class SailerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Si la app se cerró de golpe la última vez (o Android la mató),
        // borramos aquí cualquier resto antes de que se cree ningún WebView.
        PrivacyManager.wipeDiskLeftovers(this)

        AdBlocker.enabled = Prefs.get(this).adBlockEnabled
        AdBlocker.load(this)
        CosmeticFilter.load(this)
        DownloadCenter.init(this)

        // Bloquear al abrir y cada vez que la app pasa a segundo plano.
        AppLock.lockIfEnabled(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppLock.onAppBackground(this@SailerApp)
            }
        })
    }
}
