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
import com.sailer.browser.settings.Prefs

class LockActivity : SecureActivity() {

    override val checksLock = false

    private lateinit var binding: ActivityLockBinding
    private var failedAttempts = 0

    /** Modo "comprobar PIN" (p. ej. para abrir los favoritos) sin tocar el bloqueo general. */
    private val verifyOnly by lazy { intent.getBooleanExtra(EXTRA_VERIFY, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
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

        val canUseBiometric = Prefs.get(this).biometricEnabled && isBiometricAvailable(this)
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
    }

    private fun tryPin() {
        val pin = binding.pinField.text.toString()
        if (AppLock.checkPin(this, pin)) {
            unlock()
        } else {
            failedAttempts++
            binding.pinField.text?.clear()
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.setText(R.string.lock_wrong_pin)
            if (failedAttempts >= 5) {
                // Tras 5 fallos, esperar 30 segundos.
                binding.unlockButton.isEnabled = false
                binding.pinField.isEnabled = false
                binding.errorText.setText(R.string.lock_wait)
                binding.root.postDelayed({
                    failedAttempts = 0
                    binding.unlockButton.isEnabled = true
                    binding.pinField.isEnabled = true
                    binding.errorText.visibility = View.GONE
                }, 30_000)
            }
        }
    }

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
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    private fun unlock() {
        if (verifyOnly) setResult(RESULT_OK) else AppLock.unlock()
        finish()
    }

    companion object {
        const val EXTRA_VERIFY = "verify"

        fun isBiometricAvailable(context: android.content.Context): Boolean =
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
