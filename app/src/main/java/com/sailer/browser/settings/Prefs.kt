package com.sailer.browser.settings

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.sailer.browser.vault.Vault

/** Ajustes del navegador (guardados en el móvil, nunca en la nube). */
class Prefs private constructor(context: Context) {

    private val appContext = context.applicationContext
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

    /**
     * Webs en las que el usuario ha desactivado el bloqueador. Se guardan
     * cifradas, porque revelan qué webs visitas.
     */
    var adBlockAllowlist: Set<String>
        get() {
            prefs.getString(KEY_ALLOWLIST_ENC, null)?.let { stored ->
                return runCatching {
                    val plain = Vault.get(appContext).crypto.decryptBytes(Base64.decode(stored, Base64.NO_WRAP))
                    String(plain, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }.toSet()
                }.getOrDefault(emptySet())
            }
            // Versiones anteriores lo guardaban sin cifrar: migrar.
            val legacy = prefs.getStringSet(KEY_ALLOWLIST, null)?.toSet() ?: return emptySet()
            adBlockAllowlist = legacy
            prefs.edit { remove(KEY_ALLOWLIST) }
            return legacy
        }
        set(value) {
            val data = Vault.get(appContext).crypto.encryptBytes(value.joinToString("\n").toByteArray(Charsets.UTF_8))
            prefs.edit { putString(KEY_ALLOWLIST_ENC, Base64.encodeToString(data, Base64.NO_WRAP)) }
        }

    var pinVersion: Int
        get() = prefs.getInt(KEY_PIN_VERSION, 1)
        set(value) = prefs.edit { putInt(KEY_PIN_VERSION, value) }

    var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED, 0)
        set(value) = prefs.edit { putInt(KEY_FAILED, value) }

    var lockoutUntil: Long
        get() = prefs.getLong(KEY_LOCKOUT, 0L)
        set(value) = prefs.edit { putLong(KEY_LOCKOUT, value) }

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
        private const val KEY_ALLOWLIST = "adblock_allowlist"
        private const val KEY_ALLOWLIST_ENC = "adblock_allowlist_enc"
        private const val KEY_PIN_VERSION = "pin_version"
        private const val KEY_FAILED = "pin_failed"
        private const val KEY_LOCKOUT = "pin_lockout_until"
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
