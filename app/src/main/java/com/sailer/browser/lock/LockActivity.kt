package com.sailer.browser.lock

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.sailer.browser.R
import com.sailer.browser.databinding.ActivityLockBinding
import com.sailer.browser.settings.Disguise
import com.sailer.browser.settings.Prefs

class LockActivity : SecureActivity() {

    override val checksLock = false

    private lateinit var binding: ActivityLockBinding

    /** Modo "comprobar PIN" (p. ej. para abrir los favoritos) sin tocar el bloqueo general. */
    private val verifyOnly by lazy { intent.getBooleanExtra(EXTRA_VERIFY, false) }

    /** Con el disimulo activado, el bloqueo es una calculadora: PIN y luego «=». */
    private val asCalculator by lazy { !verifyOnly && Disguise.isEnabled(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        val canUseBiometric = Prefs.get(this).biometricEnabled && isBiometricAvailable(this)
        if (asCalculator) {
            val calculator = CalculatorScreen(
                this,
                onPinEntered = { pin ->
                    // Un PIN incorrecto no muestra nada: la calculadora sigue como si tal cosa.
                    AppLock.lockoutRemaining(this) == 0L && AppLock.checkPin(this, pin).also { if (it) unlock() }
                },
                onBiometric = if (canUseBiometric) ({ showBiometricPrompt() }) else null,
            )
            setContentView(calculator.view)
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            })
            return
        }
        setContentView(binding.root)

        binding.unlockButton.setOnClickListener { tryPin() }
        binding.pinField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryPin()
                true
            } else {
                false
            }
        }

        binding.biometricButton.visibility = if (canUseBiometric) View.VISIBLE else View.GONE
        binding.biometricButton.setOnClickListener { showBiometricPrompt() }
        if (canUseBiometric && savedInstanceState == null) showBiometricPrompt()

        // "Atrás" no se salta el bloqueo: manda la app a segundo plano.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (verifyOnly) finish() else moveTaskToBack(true)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!verifyOnly && !AppLock.isLocked) finish()
        if (!asCalculator) showLockout()
    }

    private fun tryPin() {
        if (AppLock.lockoutRemaining(this) > 0) {
            showLockout()
            return
        }
        val pin = binding.pinField.text.toString()
        if (AppLock.checkPin(this, pin)) {
            unlock()
        } else {
            binding.pinField.text?.clear()
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.setText(R.string.lock_wrong_pin)
            if (AppLock.lockoutRemaining(this) > 0) showLockout()
        }
    }

    /** Tras varios fallos hay que esperar (el tiempo se guarda aunque cierres la app). */
    private fun showLockout() {
        val remaining = AppLock.lockoutRemaining(this)
        if (remaining <= 0) {
            binding.unlockButton.isEnabled = true
            binding.pinField.isEnabled = true
            binding.errorText.visibility = View.GONE
            return
        }
        binding.unlockButton.isEnabled = false
        binding.pinField.isEnabled = false
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = getString(R.string.lock_wait_seconds, (remaining + 999) / 1000)
        binding.root.removeCallbacks(lockoutTicker)
        binding.root.postDelayed(lockoutTicker, 1000)
    }

    private val lockoutTicker = Runnable { showLockout() }

    private fun showBiometricPrompt() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_biometric_title))
            .setNegativeButtonText(getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    private fun unlock() {
        if (verifyOnly) setResult(RESULT_OK) else AppLock.unlock(this)
        finish()
    }

    companion object {
        const val EXTRA_VERIFY = "verify"

        fun isBiometricAvailable(context: android.content.Context): Boolean =
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
