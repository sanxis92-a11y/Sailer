package com.sailer.browser.vault

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sailer.browser.R
import com.sailer.browser.databinding.ActivityVaultBinding
import com.sailer.browser.databinding.ItemVaultBinding
import com.sailer.browser.download.DownloadCenter
import com.sailer.browser.lock.AppLock
import com.sailer.browser.lock.SecureActivity
import com.sailer.browser.lock.showSecure
import com.sailer.browser.media.MediaKind
import com.sailer.browser.media.MediaSniffer
import com.sailer.browser.settings.Prefs
import java.io.File
import kotlin.concurrent.thread

/**
 * Carpeta segura con gestor de archivos: carpetas, mover, renombrar, borrar,
 * selección múltiple, ordenar, buscar, importar del móvil (se cifra al
 * entrar) y exportar o compartir (sale sin cifrar, con aviso).
 */
class VaultActivity : SecureActivity(), Vault.Listener, DownloadCenter.Listener {

    /** Una fila de la lista: descarga en curso, carpeta o archivo. */
    private sealed class Row {
        data class Task(val task: DownloadCenter.Task) : Row()
        data class Folder(val path: String, val count: Int) : Row()
        data class File(val entry: Vault.Entry) : Row()
    }

    private lateinit var binding: ActivityVaultBinding
    private lateinit var vault: Vault
    private lateinit var prefs: Prefs
    private val adapter = Adapter()

    /** Carpeta que se está viendo ("" = principal). */
    private var folder = ""
    private var query = ""
    private var searchView: SearchView? = null

    /** Elementos seleccionados: ids de archivo y rutas de carpeta. */
    private val selectedFiles = linkedSetOf<String>()
    private val selectedFolders = linkedSetOf<String>()
    private val selecting get() = selectedFiles.isNotEmpty() || selectedFolders.isNotEmpty()

    private var pendingExport: List<Row> = emptyList()

    private val exportOneLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { target -> (pendingExport.singleOrNull() as? Row.File)?.let { exportOne(it.entry, target) } } }

    private val exportTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { exportToTree(pendingExport, it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        vault = Vault.get(this)
        prefs = Prefs.get(this)
        folder = savedInstanceState?.getString(STATE_FOLDER).orEmpty()

        binding.toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.list.adapter = adapter
        binding.list.emptyView = binding.empty
        binding.list.setOnItemClickListener { _, _, position, _ -> onRowClick(adapter.getItem(position)) }
        binding.list.setOnItemLongClickListener { _, _, position, _ ->
            when (val row = adapter.getItem(position)) {
                is Row.Task -> showTaskOptions(row.task)
                else -> toggle(row)
            }
            true
        }
        binding.importButton.setOnClickListener { startImport() }
        updateToolbar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FOLDER, folder)
    }

    override fun onStart() {
        super.onStart()
        vault.addListener(this)
        DownloadCenter.addListener(this)
        refresh()
    }

    override fun onStop() {
        vault.removeListener(this)
        DownloadCenter.removeListener(this)
        super.onStop()
    }

    override fun onVaultChanged() = refresh()

    override fun onDownloadsChanged() = refresh()

    // ---- Navegación y barra superior ----

    private fun goBack() {
        when {
            selecting -> clearSelection()
            query.isNotEmpty() -> searchView?.let { it.setQuery("", false); it.isIconified = true } ?: run { query = ""; refresh() }
            folder.isNotEmpty() -> openFolder(Vault.parentOf(folder))
            else -> finish()
        }
    }

    private fun openFolder(path: String) {
        folder = path
        clearSelection()
        binding.list.setSelection(0)
    }

    private fun updateToolbar() {
        val toolbar = binding.toolbar
        toolbar.menu.clear()
        if (selecting) {
            toolbar.inflateMenu(R.menu.vault_selection_menu)
            toolbar.title = getString(R.string.vault_selected, selectedFiles.size + selectedFolders.size)
            toolbar.subtitle = null
            toolbar.setNavigationIcon(R.drawable.ic_close)
            val single = selectedFiles.size + selectedFolders.size == 1
            toolbar.menu.findItem(R.id.action_rename).isVisible = single
            toolbar.menu.findItem(R.id.action_share).isVisible = selectedFolders.isEmpty()
        } else {
            toolbar.inflateMenu(R.menu.vault_menu)
            toolbar.title = if (folder.isEmpty()) getString(R.string.menu_vault) else Vault.nameOf(folder)
            toolbar.subtitle = if (folder.isEmpty()) getString(R.string.vault_subtitle) else "/" + folder
            toolbar.setNavigationIcon(R.drawable.ic_back)
            setUpSearch(toolbar.menu.findItem(R.id.action_search))
        }
        toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, R.color.text))
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> chooseSort()
                R.id.action_new_folder -> askFolderName(R.string.vault_new_folder, "") { vault.createFolder(folder, it) }
                R.id.action_import -> startImport()
                R.id.action_move -> chooseMoveTarget()
                R.id.action_share -> confirmShare(selectedRows())
                R.id.action_delete -> confirmDelete(selectedRows())
                R.id.action_select_all -> selectAll()
                R.id.action_rename -> selectedRows().singleOrNull()?.let { rename(it) }
                R.id.action_export -> confirmExport(selectedRows())
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        binding.importButton.visibility = if (selecting || query.isNotEmpty()) View.GONE else View.VISIBLE
    }

    private fun setUpSearch(item: android.view.MenuItem) {
        val view = item.actionView as? SearchView ?: return
        searchView = view
        view.queryHint = getString(R.string.vault_search)
        view.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(ContextCompat.getColor(context, R.color.text))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_dim))
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        if (query.isNotEmpty()) {
            item.expandActionView()
            view.setQuery(query, false)
        }
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?) = true
            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty().trim()
                adapter.refresh()
                binding.importButton.visibility = if (query.isNotEmpty()) View.GONE else View.VISIBLE
                return true
            }
        })
    }

    private fun refresh() {
        // Quitar de la selección lo que ya no existe.
        selectedFiles.retainAll(vault.list().map { it.id }.toSet())
        selectedFolders.retainAll(vault.allFolders().toSet())
        if (folder.isNotEmpty() && folder !in vault.allFolders()) folder = ""
        updateToolbar()
        adapter.refresh()
        updateEmptyText()
    }

    private fun updateEmptyText() {
        when {
            query.isNotEmpty() -> {
                binding.emptyTitle.setText(R.string.vault_no_results)
                binding.emptyBody.text = ""
            }
            folder.isNotEmpty() -> {
                binding.emptyTitle.setText(R.string.vault_empty_folder_title)
                binding.emptyBody.setText(R.string.vault_empty_folder)
            }
            else -> {
                binding.emptyTitle.setText(R.string.vault_empty_title)
                binding.emptyBody.setText(R.string.vault_empty)
            }
        }
    }

    // ---- Selección ----

    private fun onRowClick(row: Row) {
        if (selecting && row !is Row.Task) {
            toggle(row)
            return
        }
        when (row) {
            is Row.Task -> showTaskOptions(row.task)
            is Row.Folder -> {
                searchView?.let { if (query.isNotEmpty()) { it.setQuery("", false); it.isIconified = true } }
                query = ""
                openFolder(row.path)
            }
            is Row.File -> when (row.entry.kind) {
                MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE, MediaKind.HLS, MediaKind.DASH ->
                    startActivity(Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_ID, row.entry.id))
                MediaKind.FILE -> showFileOptions(row)
            }
        }
    }

    private fun toggle(row: Row) {
        when (row) {
            is Row.File -> if (!selectedFiles.remove(row.entry.id)) selectedFiles.add(row.entry.id)
            is Row.Folder -> if (!selectedFolders.remove(row.path)) selectedFolders.add(row.path)
            is Row.Task -> return
        }
        updateToolbar()
        adapter.notifyDataSetChanged()
    }

    private fun selectAll() {
        adapter.rows.forEach {
            when (it) {
                is Row.File -> selectedFiles.add(it.entry.id)
                is Row.Folder -> selectedFolders.add(it.path)
                is Row.Task -> Unit
            }
        }
        updateToolbar()
        adapter.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selectedFiles.clear()
        selectedFolders.clear()
        refresh()
    }

    private fun selectedRows(): List<Row> {
        val files = vault.list().filter { it.id in selectedFiles }.map { Row.File(it) }
        val folders = selectedFolders.map { Row.Folder(it, vault.countIn(it)) }
        return folders + files
    }

    private fun isSelected(row: Row) = when (row) {
        is Row.File -> row.entry.id in selectedFiles
        is Row.Folder -> row.path in selectedFolders
        is Row.Task -> false
    }

    // ---- Acciones ----

    private fun showFileOptions(row: Row.File) {
        val options = arrayOf(
            getString(R.string.vault_rename),
            getString(R.string.vault_move),
            getString(R.string.vault_export),
            getString(R.string.vault_share),
            getString(R.string.vault_delete),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(row.entry.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> rename(row)
                    1 -> chooseMoveTarget(listOf(row))
                    2 -> confirmExport(listOf(row))
                    3 -> confirmShare(listOf(row))
                    4 -> confirmDelete(listOf(row))
                }
            }
            .showSecure()
    }

    private fun chooseSort() {
        val options = arrayOf(
            getString(R.string.sort_date),
            getString(R.string.sort_name),
            getString(R.string.sort_size),
            getString(R.string.sort_type),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_sort)
            .setSingleChoiceItems(options, prefs.vaultSort) { dialog, which ->
                prefs.vaultSort = which
                adapter.refresh()
                dialog.dismiss()
            }
            .showSecure()
    }

    private fun askFolderName(title: Int, initial: String, onName: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(initial)
            hint = getString(R.string.vault_folder_name)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine()
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) {
                    runCatching { onName(name) }.onFailure { toast(it.message ?: "") }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    private fun rename(row: Row) {
        when (row) {
            is Row.File -> askFolderName(R.string.vault_rename, row.entry.name) { vault.rename(row.entry, it) }
            is Row.Folder -> askFolderName(R.string.vault_rename, Vault.nameOf(row.path)) { name ->
                val newPath = vault.renameFolder(row.path, name)
                selectedFolders.remove(row.path)
                if (Vault.isInside(folder, row.path)) folder = newPath + folder.removePrefix(row.path)
                refresh()
            }
            is Row.Task -> Unit
        }
    }

    private fun chooseMoveTarget(rows: List<Row> = selectedRows()) {
        if (rows.isEmpty()) return
        val movingFolders = rows.filterIsInstance<Row.Folder>().map { it.path }
        // No se puede mover una carpeta dentro de sí misma.
        val targets = listOf("") + vault.allFolders().filter { candidate -> movingFolders.none { Vault.isInside(candidate, it) } }
        val labels = targets.map { if (it.isEmpty()) getString(R.string.vault_root) else "📁 $it" } +
            ("＋ " + getString(R.string.vault_new_folder))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_move_to)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == targets.size) {
                    askFolderName(R.string.vault_new_folder, "") { name ->
                        val created = vault.createFolder(folder, name)
                        move(rows, created)
                    }
                } else {
                    move(rows, targets[which])
                }
            }
            .showSecure()
    }

    private fun move(rows: List<Row>, target: String) {
        vault.move(
            rows.filterIsInstance<Row.File>().map { it.entry },
            rows.filterIsInstance<Row.Folder>().map { it.path },
            target,
        )
        clearSelection()
    }

    private fun confirmDelete(rows: List<Row>) {
        if (rows.isEmpty()) return
        val message = (rows.singleOrNull() as? Row.File)?.let { getString(R.string.vault_delete_confirm, it.entry.name) }
            ?: getString(R.string.vault_delete_many, rows.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_delete)
            .setMessage(message)
            .setPositiveButton(R.string.vault_delete) { _, _ ->
                thread {
                    vault.delete(rows.filterIsInstance<Row.File>().map { it.entry })
                    rows.filterIsInstance<Row.Folder>().forEach { vault.deleteFolder(it.path) }
                    runOnUiThread { clearSelection() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    // ---- Exportar y compartir (los archivos salen sin cifrar) ----

    private fun confirmExport(rows: List<Row>) {
        if (rows.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_export)
            .setMessage(R.string.vault_export_warning)
            .setPositiveButton(R.string.vault_export) { _, _ ->
                pendingExport = rows
                AppLock.skipLockBriefly()
                val single = rows.singleOrNull() as? Row.File
                if (single != null) exportOneLauncher.launch(single.entry.name) else exportTreeLauncher.launch(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    private fun exportOne(entry: Vault.Entry, target: Uri) {
        thread {
            val ok = runCatching {
                contentResolver.openOutputStream(target)!!.use { out ->
                    vault.openInput(entry).use { it.copyTo(out, 128 * 1024) }
                }
            }.isSuccess
            runOnUiThread {
                toast(getString(if (ok) R.string.vault_exported else R.string.vault_export_failed))
                if (ok) clearSelection()
            }
        }
    }

    /** Exporta archivos y carpetas (con sus subcarpetas) a la carpeta del móvil elegida. */
    private fun exportToTree(rows: List<Row>, tree: Uri) {
        toast(getString(R.string.vault_preparing))
        thread {
            var done = 0
            val result = runCatching {
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                fun writeFile(parent: Uri, entry: Vault.Entry) {
                    val doc = DocumentsContract.createDocument(contentResolver, parent, entry.mime ?: "application/octet-stream", entry.name)
                        ?: return
                    contentResolver.openOutputStream(doc)!!.use { out -> vault.openInput(entry).use { it.copyTo(out, 128 * 1024) } }
                    done++
                }
                fun writeFolder(parent: Uri, path: String) {
                    val dir = DocumentsContract.createDocument(contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, Vault.nameOf(path))
                        ?: return
                    vault.filesIn(path).forEach { writeFile(dir, it) }
                    vault.foldersIn(path).forEach { writeFolder(dir, it) }
                }
                rows.forEach { row ->
                    when (row) {
                        is Row.File -> writeFile(root, row.entry)
                        is Row.Folder -> writeFolder(root, row.path)
                        is Row.Task -> Unit
                    }
                }
            }
            runOnUiThread {
                toast(if (result.isSuccess) getString(R.string.vault_exported_many, done) else getString(R.string.vault_export_failed))
                if (result.isSuccess) clearSelection()
            }
        }
    }

    private fun confirmShare(rows: List<Row>) {
        val files = rows.filterIsInstance<Row.File>().map { it.entry }
        if (files.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_share)
            .setMessage(R.string.vault_share_warning)
            .setPositiveButton(R.string.vault_share) { _, _ -> share(files) }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    /** Descifra en la caché (que se borra al cerrar la app) y abre el menú de compartir de Android. */
    private fun share(files: List<Vault.Entry>) {
        toast(getString(R.string.vault_preparing))
        thread {
            val uris = runCatching {
                val dir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
                files.mapIndexed { i, entry ->
                    val sub = File(dir, i.toString()).apply { mkdirs() }
                    val file = File(sub, entry.name.replace('/', '-'))
                    file.outputStream().use { out -> vault.openInput(entry).use { it.copyTo(out, 128 * 1024) } }
                    FileProvider.getUriForFile(this, "$packageName.share", file)
                }
            }.getOrNull()
            runOnUiThread {
                if (uris == null) {
                    toast(getString(R.string.vault_export_failed))
                    return@runOnUiThread
                }
                val mime = files.map { it.mime ?: "*/*" }.distinct().singleOrNull() ?: "*/*"
                val intent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
                intent.type = mime
                intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                AppLock.skipLockBriefly()
                startActivity(Intent.createChooser(intent, getString(R.string.vault_share)))
                clearSelection()
            }
        }
    }

    // ---- Importar del móvil (se cifra al entrar) ----

    private fun startImport() {
        AppLock.skipLockBriefly()
        importLauncher.launch(arrayOf("*/*"))
    }

    private fun importFiles(uris: List<Uri>) {
        val target = folder
        toast(getString(R.string.vault_importing, uris.size))
        thread {
            val imported = mutableListOf<Uri>()
            uris.forEach { uri ->
                val writer = vault.newWriter()
                try {
                    val name = displayName(uri)
                    val mime = contentResolver.getType(uri)
                    val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { null }
                    val kind = (MediaSniffer.kindForMime(mime) ?: MediaSniffer.kindForExtension(ext))
                        ?.takeIf { it != MediaKind.HLS && it != MediaKind.DASH } ?: MediaKind.FILE
                    val finalMime = mime ?: ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                    contentResolver.openInputStream(uri)!!.use { it.copyTo(writer.output, 128 * 1024) }
                    writer.commit(name, kind, finalMime, folder = target)
                    imported += uri
                } catch (e: Exception) {
                    writer.abort()
                }
            }
            runOnUiThread {
                val failed = uris.size - imported.size
                toast(
                    getString(R.string.vault_imported, imported.size) +
                        if (failed > 0) "\n" + getString(R.string.vault_import_failed, failed) else ""
                )
                if (imported.isNotEmpty()) offerDeleteOriginals(imported)
            }
        }
    }

    private fun offerDeleteOriginals(uris: List<Uri>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_delete_originals_title)
            .setMessage(R.string.vault_delete_originals)
            .setPositiveButton(R.string.vault_delete) { _, _ ->
                thread {
                    val failed = uris.count { uri ->
                        !runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)
                    }
                    if (failed > 0) runOnUiThread { toast(getString(R.string.vault_delete_originals_failed, failed)) }
                }
            }
            .setNegativeButton(R.string.vault_keep, null)
            .showSecure()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    // ---- Descargas en curso ----

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
        builder.showSecure()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- Lista ----

    private inner class Adapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
            private set

        fun refresh() {
            rows = if (query.isNotEmpty()) {
                // Buscar en todas las carpetas.
                val q = query.lowercase()
                vault.allFolders().filter { Vault.nameOf(it).lowercase().contains(q) }.sorted()
                    .map { Row.Folder(it, vault.countIn(it)) } +
                    sorted(vault.list().filter { it.name.lowercase().contains(q) }).map { Row.File(it) }
            } else {
                val tasks = if (folder.isEmpty()) DownloadCenter.tasks.map { Row.Task(it) } else emptyList()
                val folders = vault.foldersIn(folder).sortedBy { it.lowercase() }.map { Row.Folder(it, vault.countIn(it)) }
                tasks + folders + sorted(vault.filesIn(folder)).map { Row.File(it) }
            }
            notifyDataSetChanged()
        }

        private fun sorted(list: List<Vault.Entry>): List<Vault.Entry> = when (prefs.vaultSort) {
            1 -> list.sortedBy { it.name.lowercase() }
            2 -> list.sortedByDescending { it.size }
            3 -> list.sortedWith(compareBy<Vault.Entry> { it.kind.ordinal }.thenBy { it.name.lowercase() })
            else -> list.sortedByDescending { it.created }
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView?.let { ItemVaultBinding.bind(it) }
                ?: ItemVaultBinding.inflate(layoutInflater, parent, false)
            val row = rows[position]
            val selected = isSelected(row)
            view.root.setBackgroundColor(
                if (selected) ContextCompat.getColor(this@VaultActivity, R.color.accent_container) else Color.TRANSPARENT
            )
            when (row) {
                is Row.Task -> {
                    val item = row.task
                    view.icon.text = when (item.status) {
                        DownloadCenter.Status.FAILED -> "⚠️"
                        DownloadCenter.Status.PAUSED -> "⏸️"
                        else -> "⬇️"
                    }
                    view.title.text = item.request.title.ifBlank { item.request.url }
                    view.subtitle.text = when (item.status) {
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
                is Row.Folder -> {
                    view.icon.text = if (selected) "✅" else "📁"
                    view.title.text = Vault.nameOf(row.path)
                    val count = getString(R.string.vault_folder_items, row.count)
                    view.subtitle.text = if (query.isNotEmpty() && Vault.parentOf(row.path).isNotEmpty()) {
                        "$count · /${Vault.parentOf(row.path)}"
                    } else {
                        count
                    }
                }
                is Row.File -> {
                    val item = row.entry
                    view.icon.text = if (selected) "✅" else when (item.kind) {
                        MediaKind.VIDEO, MediaKind.HLS, MediaKind.DASH -> "🎬"
                        MediaKind.AUDIO -> "🎵"
                        MediaKind.IMAGE -> "🖼️"
                        MediaKind.FILE -> "📄"
                    }
                    view.title.text = item.name
                    val date = DateFormat.getMediumDateFormat(this@VaultActivity).format(item.created)
                    val details = "${Formatter.formatShortFileSize(this@VaultActivity, item.size)} · $date"
                    view.subtitle.text = if (query.isNotEmpty() && item.folder.isNotEmpty()) "$details · /${item.folder}" else details
                }
            }
            return view.root
        }
    }

    companion object {
        private const val STATE_FOLDER = "folder"
    }
}
