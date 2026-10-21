package com.sailer.browser.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sailer.browser.BuildConfig
import com.sailer.browser.R
import com.sailer.browser.adblock.AdBlocker
import com.sailer.browser.databinding.ActivitySettingsBinding
import com.sailer.browser.lock.AppLock
import com.sailer.browser.lock.LockActivity
import com.sailer.browser.lock.SecureActivity

class SettingsActivity : SecureActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        prefs = Prefs.get(this)

        binding.switchAdblock.isChecked = prefs.adBlockEnabled
        binding.switchAdblock.setOnCheckedChangeListener { _, checked ->
            prefs.adBlockEnabled = checked
            AdBlocker.enabled = checked
        }

        binding.switchDoh.isChecked = prefs.dohEnabled
        binding.switchDoh.setOnCheckedChangeListener { _, checked -> prefs.dohEnabled = checked }

        binding.switchLock.isChecked = AppLock.isConfigured(this)
        binding.switchLock.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                askNewPin(onCancel = { binding.switchLock.isChecked = false }) {
                    prefs.lockEnabled = true
                    updateLockViews()
                }
            } else {
                prefs.lockEnabled = false
                prefs.biometricEnabled = false
                updateLockViews()
            }
        }

        binding.switchBiometric.isChecked = prefs.biometricEnabled
        binding.switchBiometric.setOnCheckedChangeListener { _, checked -> prefs.biometricEnabled = checked }

        binding.changePin.setOnClickListener { askNewPin(onCancel = {}) {} }
        binding.privateDns.setOnClickListener { explainPrivateDns() }
        binding.version.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        updateLockViews()
    }

    private fun updateLockViews() {
        val lockOn = AppLock.isConfigured(this)
        val biometricAvailable = LockActivity.isBiometricAvailable(this)
        binding.switchBiometric.visibility = if (lockOn && biometricAvailable) View.VISIBLE else View.GONE
        if (!lockOn) binding.switchBiometric.isChecked = false
        binding.changePin.visibility = if (lockOn) View.VISIBLE else View.GONE
    }

    private fun askNewPin(onCancel: () -> Unit, onDone: () -> Unit) {
        val first = pinField(R.string.settings_pin_new)
        val second = pinField(R.string.settings_pin_repeat)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(first)
            addView(second)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_pin_title)
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = first.text.toString()
                when {
                    pin.length < 4 -> {
                        Toast.makeText(this, R.string.settings_pin_short, Toast.LENGTH_LONG).show()
                        onCancel()
                    }
                    pin != second.text.toString() -> {
                        Toast.makeText(this, R.string.settings_pin_mismatch, Toast.LENGTH_LONG).show()
                        onCancel()
                    }
                    else -> {
                        AppLock.setPin(this, pin)
                        Toast.makeText(this, R.string.settings_pin_saved, Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    private fun pinField(hint: Int) = EditText(this).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun explainPrivateDns() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_private_dns)
            .setMessage(R.string.settings_private_dns_help)
            .setPositiveButton(R.string.settings_open_settings) { _, _ ->
                AppLock.skipNextLock = true
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
