package com.sailer.browser

import android.app.Application
import com.sailer.browser.privacy.PrivacyManager

class SailerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Si la app se cerró de golpe la última vez (o Android la mató),
        // borramos aquí cualquier resto antes de que se cree ningún WebView.
        PrivacyManager.wipeDiskLeftovers(this)
    }
}
