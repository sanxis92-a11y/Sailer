package com.sailer.browser.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sailer.browser.lock.showSecure
import com.sailer.browser.BuildConfig
import com.sailer.browser.Lang
import com.sailer.browser.R
import com.sailer.browser.adblock.AdBlocker
import com.sailer.browser.adblock.CosmeticFilter
import com.sailer.browser.databinding.ActivitySettingsBinding
import com.sailer.browser.dns.WebDns
import com.sailer.browser.lock.AppLock
import com.sailer.browser.lock.LockActivity
import com.sailer.browser.lock.SecureActivity
import com.sailer.browser.vault.Backup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class SettingsActivity : SecureActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) askBackupPassword(confirm = true) { runBackup(uri, export = true, it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askBackupPassword(confirm = false) { runBackup(uri, export = false, it) }
    }

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
            thread { CosmeticFilter.rebuildScript() }
        }

        binding.switchDoh.isChecked = prefs.dohEnabled
        binding.switchDoh.setOnCheckedChangeListener { _, checked ->
            prefs.dohEnabled = checked
            WebDns.apply(this)
        }

        if (!Disguise.isAvailable) {
            binding.switchDisguise.visibility = View.GONE
            binding.disguiseHelp.visibility = View.GONE
        }
        binding.switchDisguise.isChecked = Disguise.isEnabled(this)
        binding.switchDisguise.setOnCheckedChangeListener { _, checked ->
            if (checked && !AppLock.isConfigured(this)) {
                // La calculadora se abre con el PIN, así que primero hay que elegir uno.
                askNewPin(onCancel = { binding.switchDisguise.isChecked = false }) {
                    prefs.lockEnabled = true
                    binding.switchLock.isChecked = true
                    updateLockViews()
                    enableDisguise()
                }
            } else if (checked) {
                enableDisguise()
            } else {
                Disguise.setEnabled(this, false)
            }
        }

        binding.switchLock.isChecked = AppLock.isConfigured(this)
        binding.switchLock.setOnCheckedChangeListener { _, checked ->
            if (checked && AppLock.isConfigured(this)) {
                updateLockViews()
            } else if (checked) {
                askNewPin(onCancel = { binding.switchLock.isChecked = false }) {
                    prefs.lockEnabled = true
                    updateLockViews()
                }
            } else {
                prefs.lockEnabled = false
                prefs.biometricEnabled = false
                // Sin PIN no se podría salir de la calculadora.
                binding.switchDisguise.isChecked = false
                updateLockViews()
            }
        }

        binding.switchBiometric.isChecked = prefs.biometricEnabled
        binding.switchBiometric.setOnCheckedChangeListener { _, checked -> prefs.biometricEnabled = checked }

        binding.changePin.setOnClickListener { askNewPin(onCancel = {}) {} }
        binding.privateDns.setOnClickListener { explainPrivateDns() }
        binding.backupExport.setOnClickListener {
            AppLock.skipLockBriefly()
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            exportLauncher.launch(Lang.t("nauvel-copia-$date.nauvel", "nauvel-backup-$date.nauvel"))
        }
        binding.backupImport.setOnClickListener {
            AppLock.skipLockBriefly()
            importLauncher.launch(arrayOf("*/*"))
        }
        binding.privacyPolicy.setOnClickListener { showTextAsset(R.string.settings_privacy_policy, "privacy.txt") }
        binding.licenses.setOnClickListener { showTextAsset(R.string.settings_licenses, "licenses.txt") }
        binding.version.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        updateLockViews()
    }

    private fun enableDisguise() {
        Disguise.setEnabled(this, true)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_disguise)
            .setMessage(R.string.settings_disguise_how)
            .setPositiveButton(R.string.download_notice_ok, null)
            .showSecure()
    }

    /** Muestra un texto largo guardado en assets (política de privacidad, licencias). */
    private fun showTextAsset(title: Int, asset: String) {
        val text = assets.open(Lang.asset(asset)).bufferedReader().use { it.readText() }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(R.string.close, null)
            .showSecure()
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
            .showSecure()
    }

    private fun pinField(hint: Int) = EditText(this).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    private fun explainPrivateDns() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_private_dns)
            .setMessage(R.string.settings_private_dns_help)
            .setPositiveButton(R.string.settings_open_settings) { _, _ ->
                AppLock.skipLockBriefly()
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
            }
            .setNegativeButton(R.string.close, null)
            .showSecure()
    }

    private fun askBackupPassword(confirm: Boolean, onPassword: (CharArray) -> Unit) {
        val first = passwordField(R.string.backup_password)
        val second = passwordField(R.string.backup_password_repeat)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(first)
            if (confirm) addView(second)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_password_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = first.text.toString()
                when {
                    confirm && password.length < 8 ->
                        Toast.makeText(this, R.string.backup_password_short, Toast.LENGTH_LONG).show()
                    confirm && password != second.text.toString() ->
                        Toast.makeText(this, R.string.backup_password_mismatch, Toast.LENGTH_LONG).show()
                    password.isEmpty() -> Unit
                    else -> onPassword(password.toCharArray())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (confirm) builder.setMessage(R.string.backup_password_help)
        builder.showSecure()
    }

    private fun passwordField(hint: Int) = EditText(this).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    private fun runBackup(uri: Uri, export: Boolean, password: CharArray) {
        val progressText = if (export) R.string.backup_working else R.string.backup_importing
        val progress: AlertDialog = MaterialAlertDialogBuilder(this)
            .setMessage(getString(progressText, 0, 0))
            .setCancelable(false)
            .showSecure()
        val onProgress: (Int, Int) -> Unit = { done, total ->
            runOnUiThread { progress.setMessage(getString(progressText, done, total)) }
        }
        thread(name = "backup") {
            val message = try {
                if (export) {
                    val result = contentResolver.openOutputStream(uri, "wt")!!.use {
                        Backup.export(applicationContext, password, it, onProgress)
                    }
                    getString(R.string.backup_done, result.files, result.bookmarks)
                } else {
                    val result = contentResolver.openInputStream(uri)!!.use {
                        Backup.import(applicationContext, password, it, onProgress)
                    }
                    getString(R.string.backup_imported, result.files, result.bookmarks)
                }
            } catch (e: Backup.WrongPasswordException) {
                getString(R.string.backup_wrong_password)
            } catch (e: Exception) {
                if (export) runCatching { contentResolver.delete(uri, null, null) }
                getString(R.string.backup_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                password.fill('\u0000')
            }
            runOnUiThread {
                runCatching { progress.dismiss() }
                if (!isFinishing && !isDestroyed) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .showSecure()
                }
            }
        }
    }
}
