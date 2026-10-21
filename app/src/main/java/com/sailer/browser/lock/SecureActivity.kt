package com.sailer.browser.lock

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.sailer.browser.settings.Disguise

/**
 * Base de todas las pantallas: bloquea capturas de pantalla y la vista previa
 * en "recientes", y muestra la pantalla de bloqueo si toca.
 */
abstract class SecureActivity : AppCompatActivity() {

    protected open val checksLock: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Disguise.isEnabled(this)) {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(Disguise.label(this)))
        }
    }

    override fun onResume() {
        super.onResume()
        if (checksLock && AppLock.isLocked) {
            startActivity(Intent(this, LockActivity::class.java))
        }
    }
}
