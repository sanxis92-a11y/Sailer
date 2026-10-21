package com.sailer.browser.vault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sailer.browser.R
import com.sailer.browser.databinding.ActivityVaultBinding
import com.sailer.browser.databinding.ItemVaultBinding
import com.sailer.browser.download.DownloadCenter
import com.sailer.browser.lock.AppLock
import com.sailer.browser.lock.SecureActivity
import com.sailer.browser.media.MediaKind
import kotlin.concurrent.thread

/** Carpeta segura: descargas en curso y archivos guardados (cifrados). */
class VaultActivity : SecureActivity(), Vault.Listener, DownloadCenter.Listener {

    private lateinit var binding: ActivityVaultBinding
    private lateinit var vault: Vault
    private val adapter = Adapter()
    private var pendingExport: Vault.Entry? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { target -> pendingExport?.let { export(it, target) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        vault = Vault.get(this)
        binding.list.adapter = adapter
        binding.list.emptyView = binding.empty
        binding.list.setOnItemClickListener { _, _, position, _ -> onItemClick(adapter.getItem(position)) }
        binding.list.setOnItemLongClickListener { _, _, position, _ ->
            (adapter.getItem(position) as? Vault.Entry)?.let { showEntryOptions(it) }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        vault.addListener(this)
        DownloadCenter.addListener(this)
        adapter.refresh()
    }

    override fun onStop() {
        vault.removeListener(this)
        DownloadCenter.removeListener(this)
        super.onStop()
    }

    override fun onVaultChanged() = adapter.refresh()

    override fun onDownloadsChanged() = adapter.refresh()

    private fun onItemClick(item: Any) {
        when (item) {
            is DownloadCenter.Task -> showTaskOptions(item)
            is Vault.Entry -> when (item.kind) {
                MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE, MediaKind.HLS, MediaKind.DASH ->
                    startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_ID, item.id))
                MediaKind.FILE -> showEntryOptions(item)
            }
        }
    }

    private fun showTaskOptions(task: DownloadCenter.Task) {
        val builder = MaterialAlertDialogBuilder(this).setTitle(task.request.title.ifBlank { task.request.url })
        when (task.status) {
            DownloadCenter.Status.FAILED ->
                builder.setMessage(getString(R.string.vault_failed, task.error ?: ""))
                    .setPositiveButton(R.string.vault_retry) { _, _ -> DownloadCenter.resume(task) }
                    .setNegativeButton(R.string.vault_remove) { _, _ -> DownloadCenter.cancel(task) }
            DownloadCenter.Status.PAUSED ->
                builder.setPositiveButton(R.string.vault_resume) { _, _ -> DownloadCenter.resume(task) }
                    .setNegativeButton(R.string.vault_cancel_download) { _, _ -> DownloadCenter.cancel(task) }
                    .setNeutralButton(R.string.close, null)
            else ->
                builder.setPositiveButton(R.string.vault_pause) { _, _ -> DownloadCenter.pause(task) }
                    .setNegativeButton(R.string.vault_cancel_download) { _, _ -> DownloadCenter.cancel(task) }
                    .setNeutralButton(R.string.close, null)
        }
        builder.show()
    }

    private fun showEntryOptions(entry: Vault.Entry) {
        val options = arrayOf(
            getString(R.string.vault_rename),
            getString(R.string.vault_export),
            getString(R.string.vault_delete),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> rename(entry)
                    1 -> confirmExport(entry)
                    2 -> confirmDelete(entry)
                }
            }
            .show()
    }

    private fun rename(entry: Vault.Entry) {
        val field = EditText(this).apply { setText(entry.name) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_rename)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) vault.rename(entry, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmExport(entry: Vault.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_export)
            .setMessage(R.string.vault_export_warning)
            .setPositiveButton(R.string.vault_export) { _, _ ->
                pendingExport = entry
                AppLock.skipNextLock = true
                exportLauncher.launch(entry.name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun export(entry: Vault.Entry, target: Uri) {
        thread {
            val ok = runCatching {
                contentResolver.openOutputStream(target)!!.use { out ->
                    vault.openInput(entry).use { it.copyTo(out, 128 * 1024) }
                }
            }.isSuccess
            runOnUiThread {
                Toast.makeText(this, if (ok) R.string.vault_exported else R.string.vault_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(entry: Vault.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_delete)
            .setMessage(getString(R.string.vault_delete_confirm, entry.name))
            .setPositiveButton(R.string.vault_delete) { _, _ -> vault.delete(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        private var items: List<Any> = emptyList()

        fun refresh() {
            items = DownloadCenter.tasks + vault.list()
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let { ItemVaultBinding.bind(it) }
                ?: ItemVaultBinding.inflate(layoutInflater, parent, false)
            when (val item = items[position]) {
                is DownloadCenter.Task -> {
                    row.icon.text = when (item.status) {
                        DownloadCenter.Status.FAILED -> "⚠️"
                        DownloadCenter.Status.PAUSED -> "⏸️"
                        else -> "⬇️"
                    }
                    row.title.text = item.request.title.ifBlank { item.request.url }
                    row.subtitle.text = when (item.status) {
                        DownloadCenter.Status.QUEUED -> getString(R.string.vault_queued)
                        DownloadCenter.Status.RUNNING -> when {
                            item.retrying -> getString(R.string.vault_retrying)
                            item.progress >= 0 -> getString(R.string.vault_progress, item.progress)
                            else -> getString(R.string.vault_downloading)
                        }
                        DownloadCenter.Status.PAUSED -> getString(R.string.vault_paused, maxOf(item.progress, 0))
                        DownloadCenter.Status.FAILED -> getString(R.string.vault_failed, item.error ?: "")
                    }
                }
                is Vault.Entry -> {
                    row.icon.text = when (item.kind) {
                        MediaKind.VIDEO, MediaKind.HLS, MediaKind.DASH -> "🎬"
                        MediaKind.AUDIO -> "🎵"
                        MediaKind.IMAGE -> "🖼️"
                        MediaKind.FILE -> "📄"
                    }
                    row.title.text = item.name
                    val date = DateFormat.getMediumDateFormat(this@VaultActivity).format(item.created)
                    row.subtitle.text = "${Formatter.formatShortFileSize(this@VaultActivity, item.size)} · $date"
                }
            }
            return row.root
        }
    }
}
