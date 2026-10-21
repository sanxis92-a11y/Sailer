package com.sailer.browser.lock

import android.content.Context
import android.util.Base64
import com.sailer.browser.settings.Prefs
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Bloqueo con PIN (y opcionalmente huella). La app se bloquea al abrirla y
 * cada vez que pasa a segundo plano. El PIN nunca se guarda: solo una
 * "huella" (hash) que no permite recuperarlo.
 */
object AppLock {

    @Volatile
    var isLocked = false
        private set

    /** Evita bloquear al volver de una pantalla del sistema abierta por nosotros. */
    @Volatile
    var skipNextLock = false

    fun isConfigured(context: Context): Boolean {
        val prefs = Prefs.get(context)
        return prefs.lockEnabled && prefs.pinHash != null
    }

    fun lockIfEnabled(context: Context) {
        if (isConfigured(context)) isLocked = true
    }

    fun onAppBackground(context: Context) {
        if (skipNextLock) {
            skipNextLock = false
            return
        }
        lockIfEnabled(context)
    }

    fun unlock() {
        isLocked = false
    }

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val prefs = Prefs.get(context)
        prefs.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.pinHash = Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP)
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val prefs = Prefs.get(context)
        val salt = prefs.pinSalt?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.pinHash?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(expected, hash(pin, salt))
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 30_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
