package com.sailer.browser.lock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sailer.browser.settings.Prefs
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Bloqueo con PIN (y opcionalmente huella). La app se bloquea al abrirla y
 * cada vez que pasa a segundo plano.
 *
 * El PIN nunca se guarda: solo una "huella" calculada con PBKDF2 y después
 * firmada con una clave que vive dentro del chip de seguridad del móvil. Así,
 * aunque alguien copiara los archivos de la app, no podría probar PINs en
 * otro ordenador. Los intentos fallidos se recuerdan aunque se cierre la app
 * y cada tanda de fallos aumenta el tiempo de espera.
 */
object AppLock {

    @Volatile
    var isLocked = false
        private set

    @Volatile
    private var skipUntil = 0L

    private const val HMAC_ALIAS = "sailer_pin_hmac"
    private const val PIN_VERSION = 2

    fun isConfigured(context: Context): Boolean {
        val prefs = Prefs.get(context)
        return prefs.lockEnabled && prefs.pinHash != null
    }

    fun lockIfEnabled(context: Context) {
        if (isConfigured(context)) isLocked = true
    }

    /** Evita bloquear al volver de una pantalla del sistema abierta por nosotros (máx. 2 minutos). */
    fun skipLockBriefly() {
        skipUntil = System.currentTimeMillis() + 120_000
    }

    fun onAppBackground(context: Context) {
        if (System.currentTimeMillis() < skipUntil) {
            skipUntil = 0
            return
        }
        lockIfEnabled(context)
    }

    fun unlock(context: Context) {
        isLocked = false
        skipUntil = 0
        Prefs.get(context).apply {
            failedAttempts = 0
            lockoutUntil = 0
        }
    }

    // ---- PIN ----

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val prefs = Prefs.get(context)
        prefs.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.pinHash = Base64.encodeToString(hashV2(pin, salt), Base64.NO_WRAP)
        prefs.pinVersion = PIN_VERSION
    }

    /** Milisegundos que faltan para poder volver a intentarlo (0 = ya se puede). */
    fun lockoutRemaining(context: Context): Long =
        maxOf(0L, Prefs.get(context).lockoutUntil - System.currentTimeMillis())

    fun checkPin(context: Context, pin: String): Boolean {
        if (lockoutRemaining(context) > 0) return false
        val prefs = Prefs.get(context)
        val salt = prefs.pinSalt?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.pinHash?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val ok = if (prefs.pinVersion >= PIN_VERSION) {
            MessageDigest.isEqual(expected, hashV2(pin, salt))
        } else {
            MessageDigest.isEqual(expected, pbkdf2(pin, salt)).also { matches ->
                // PIN de una versión anterior: pasarlo al formato nuevo.
                if (matches) setPin(context, pin)
            }
        }
        if (ok) {
            prefs.failedAttempts = 0
            prefs.lockoutUntil = 0
        } else {
            val failed = prefs.failedAttempts + 1
            prefs.failedAttempts = failed
            if (failed >= 5) {
                // 30 s, 1 min, 2 min... hasta 1 hora.
                val wait = minOf(30_000L shl minOf(failed - 5, 7), 3_600_000L)
                prefs.lockoutUntil = System.currentTimeMillis() + wait
            }
        }
        return ok
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 30_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun hashV2(pin: String, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey())
        return mac.doFinal(pbkdf2(pin, salt))
    }

    private fun hmacKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(HMAC_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return generator.generateKey()
    }
}
