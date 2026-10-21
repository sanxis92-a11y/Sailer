package com.sailer.browser.settings

import android.content.Context
import androidx.core.content.edit

/** Ajustes del navegador (guardados en el móvil, nunca en la nube). */
class Prefs private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_ADBLOCK, value) }

    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCK, value) }

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs.edit { putBoolean(KEY_BIOMETRIC, value) }

    var dohEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOH, true)
        set(value) = prefs.edit { putBoolean(KEY_DOH, value) }

    var pinHash: String?
        get() = prefs.getString(KEY_PIN_HASH, null)
        set(value) = prefs.edit { putString(KEY_PIN_HASH, value) }

    var pinSalt: String?
        get() = prefs.getString(KEY_PIN_SALT, null)
        set(value) = prefs.edit { putString(KEY_PIN_SALT, value) }

    companion object {
        private const val KEY_ADBLOCK = "adblock"
        private const val KEY_LOCK = "lock"
        private const val KEY_BIOMETRIC = "biometric"
        private const val KEY_DOH = "doh"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
