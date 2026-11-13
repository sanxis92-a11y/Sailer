package com.sailer.browser.vault

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.sailer.browser.R
import com.sailer.browser.databinding.ActivityCleanBinding
import com.sailer.browser.databinding.ItemImageBinding
import com.sailer.browser.lock.SecureActivity
import com.sailer.browser.lock.showSecure
import com.sailer.browser.settings.Prefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * «Limpiar fotos» de la carpeta segura, con tres pestañas:
 *  - Duplicadas: la misma foto repetida (aunque cambie el tamaño o la compresión).
 *  - Misma escena: fotos casi iguales (ráfagas, la misma pose...).
 *  - Borrosas.
 * En cada grupo se conserva la mejor según la regla elegida y las demás se
 * marcan para borrar. Tocar una foto la marca o desmarca; mantenerla pulsada la abre.
 */
class CleanActivity : SecureActivity() {

    /** Una fila de la lista: un grupo de fotos (o unas cuantas borrosas). */
    private class Row(val title: String, val items: List<PhotoMatch.Info>, val keep: PhotoMatch.Info?)

    private lateinit var binding: ActivityCleanBinding
    private lateinit var vault: Vault
    private lateinit var prefs: Prefs
    private lateinit var index: PhotoIndex
    private val adapter = Adapter()
    private val thumbPool = Executors.newFixedThreadPool(2)
    private val loadingThumbs = HashSet<String>()

    /** Carpeta en la que se busca (y sus subcarpetas). "" = toda la carpeta segura. */
    private var scope = ""
    private var tab = 0
    private var infos: List<PhotoMatch.Info> = emptyList()
    private var rows: List<Row> = emptyList()
    private val marked = LinkedHashSet<String>()
    private var scanning = false
    private val cancelled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCleanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        vault = Vault.get(this)
        prefs = Prefs.get(this)
        scope = intent.getStringExtra(EXTRA_FOLDER).orEmpty()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, R.color.text))
        binding.toolbar.subtitle = if (scope.isEmpty()) getString(R.string.clean_scope_all) else "/$scope"
        binding.toolbar.inflateMenu(R.menu.clean_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_similarity -> chooseSimilarity()
                R.id.action_keep -> chooseKeep()
                R.id.action_rotations -> { prefs.cleanRotations = !prefs.cleanRotations; updateMenu(); regroup() }
                R.id.action_time_limit -> { prefs.cleanTimeLimit = !prefs.cleanTimeLimit; updateMenu(); regroup() }
                R.id.action_mark_all -> { rows.forEach { r -> r.items.forEach { if (it !== r.keep) marked += it.id } }; refreshMarks() }
                R.id.action_unmark_all -> { marked.clear(); refreshMarks() }
                R.id.action_rescan -> scan()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        updateMenu()

        listOf(R.string.clean_tab_duplicates, R.string.clean_tab_scene, R.string.clean_tab_blurry).forEach {
            binding.tabs.addTab(binding.tabs.newTab().setText(it))
        }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { tab = t.position; regroup() }
            override fun onTabUnselected(t: TabLayout.Tab) = Unit
            override fun onTabReselected(t: TabLayout.Tab) = Unit
        })

        binding.list.adapter = adapter
        binding.deleteButton.setOnClickListener { confirmDelete() }
        scan()
    }

    override fun onDestroy() {
        cancelled.set(true)
        thumbPool.shutdownNow()
        super.onDestroy()
    }

    private fun updateMenu() {
        val menu = binding.toolbar.menu
        menu.findItem(R.id.action_rotations)?.isChecked = prefs.cleanRotations
        menu.findItem(R.id.action_time_limit)?.isChecked = prefs.cleanTimeLimit
        // La regla de cuánto se parecen y qué conservar no se usa en Borrosas.
        menu.findItem(R.id.action_rotations)?.isVisible = tab == 0
        menu.findItem(R.id.action_time_limit)?.isVisible = tab == 1
        menu.findItem(R.id.action_keep)?.isVisible = tab != 2
    }

    // ---- Analizar ----

    private fun scan() {
        if (scanning) return
        scanning = true
        val entries = vault.list().filter { Vault.isInside(it.folder, scope) }
        binding.progress.visibility = View.VISIBLE
        binding.status.text = getString(R.string.clean_scanning, 0, 0)
        thread(name = "clean-scan") {
            val idx = if (::index.isInitialized) index else PhotoIndex(vault).also { index = it }
            var last = 0L
            val result = idx.scan(entries, { done, total ->
                val now = System.currentTimeMillis()
                if (now - last > 150 || done == total) {
                    last = now
                    runOnUiThread {
                        binding.status.text = getString(R.string.clean_scanning, done, total)
                        binding.progress.max = maxOf(total, 1)
                        binding.progress.progress = done
                    }
                }
            }, { cancelled.get() })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                scanning = false
                binding.progress.visibility = View.GONE
                infos = result
                regroup()
            }
        }
    }

    private fun regroup() {
        updateMenu()
        if (scanning) return
        val options = PhotoMatch.Options(prefs.cleanLevel, prefs.cleanRotations, prefs.cleanTimeLimit)
        val keep = PhotoMatch.Keep.entries[prefs.cleanKeep.coerceIn(0, PhotoMatch.Keep.entries.size - 1)]
        val list = infos
        val currentTab = tab
        val ignored = index.ignoredPairs()
        binding.status.text = getString(R.string.clean_grouping)
        thread(name = "clean-group") {
            val newRows = when (currentTab) {
                0 -> PhotoMatch.duplicates(list, options, keep, ignored).mapIndexed { i, g ->
                    Row(getString(R.string.clean_group, i + 1, g.items.size), g.items, g.keep)
                }
                1 -> PhotoMatch.sameScene(list, options, keep, ignored).mapIndexed { i, g ->
                    Row(getString(R.string.clean_group, i + 1, g.items.size), g.items, g.keep)
                }
                else -> PhotoMatch.blurry(list, prefs.cleanBlurLevel).chunked(3).map { Row("", it, null) }
            }
            runOnUiThread {
                if (isDestroyed || currentTab != tab) return@runOnUiThread
                rows = newRows
                marked.clear()
                // En duplicadas y misma escena se marcan ya todas menos la mejor de cada grupo.
                if (currentTab != 2) rows.forEach { r -> r.items.forEach { if (it !== r.keep) marked += it.id } }
                refreshMarks()
                binding.status.text = when {
                    list.isEmpty() -> getString(R.string.clean_no_photos)
                    rows.isEmpty() -> getString(if (currentTab == 2) R.string.clean_none_blurry else R.string.clean_none_groups, list.size)
                    currentTab == 2 -> getString(R.string.clean_found_blurry, rows.sumOf { it.items.size }, list.size)
                    else -> getString(R.string.clean_found_groups, rows.size, list.size)
                } + "\n" + getString(if (currentTab == 2) R.string.clean_hint_blurry else R.string.clean_hint)
            }
        }
    }

    private fun refreshMarks() {
        adapter.notifyDataSetChanged()
        val size = rows.flatMap { it.items }.filter { it.id in marked }.distinctBy { it.id }.sumOf { it.bytes }
        binding.deleteButton.isEnabled = marked.isNotEmpty()
        binding.deleteButton.text = if (marked.isEmpty()) getString(R.string.clean_delete_none)
        else getString(R.string.clean_delete, marked.size, Formatter.formatShortFileSize(this, size))
    }

    // ---- Opciones ----

    private fun chooseSimilarity() {
        val options = arrayOf(getString(R.string.clean_level_strict), getString(R.string.clean_level_normal), getString(R.string.clean_level_broad))
        val isBlur = tab == 2
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isBlur) R.string.clean_blur_level else R.string.clean_similarity)
            .setSingleChoiceItems(
                if (isBlur) arrayOf(getString(R.string.clean_blur_very), getString(R.string.clean_blur_normal), getString(R.string.clean_blur_some)) else options,
                if (isBlur) prefs.cleanBlurLevel else prefs.cleanLevel,
            ) { dialog, which ->
                if (isBlur) prefs.cleanBlurLevel = which else prefs.cleanLevel = which
                dialog.dismiss()
                regroup()
            }
            .showSecure()
    }

    private fun chooseKeep() {
        val options = arrayOf(
            getString(R.string.clean_keep_quality),
            getString(R.string.clean_keep_resolution),
            getString(R.string.clean_keep_size),
            getString(R.string.clean_keep_oldest),
            getString(R.string.clean_keep_newest),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clean_keep)
            .setSingleChoiceItems(options, prefs.cleanKeep) { dialog, which ->
                prefs.cleanKeep = which
                dialog.dismiss()
                regroup()
            }
            .showSecure()
    }

    // ---- Borrar ----

    private fun confirmDelete() {
        val entries = marked.mapNotNull { vault.find(it) }
        if (entries.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clean_delete_title)
            .setMessage(getString(R.string.clean_delete_message, entries.size))
            .setPositiveButton(R.string.vault_delete) { _, _ ->
                vault.delete(entries)
                val gone = entries.map { it.id }.toSet()
                infos = infos.filter { it.id !in gone }
                Toast.makeText(this, getString(R.string.clean_deleted, entries.size), Toast.LENGTH_SHORT).show()
                regroup()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
    }

    private fun notTheSame(row: Row) {
        thread {
            index.ignore(row.items.map { it.id })
            runOnUiThread { regroup() }
        }
    }

    // ---- Lista ----

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val density = resources.displayMetrics.density
            val box = LinearLayout(this@CleanActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            }
            if (row.title.isNotEmpty()) {
                val header = LinearLayout(this@CleanActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(TextView(this@CleanActivity).apply {
                    text = row.title
                    setTextColor(ContextCompat.getColor(context, R.color.text))
                    textSize = 14f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                header.addView(TextView(this@CleanActivity).apply {
                    text = getString(R.string.clean_not_same)
                    setTextColor(ContextCompat.getColor(context, R.color.accent))
                    textSize = 13f
                    setPadding((8 * density).toInt(), (6 * density).toInt(), 0, (6 * density).toInt())
                    setOnClickListener { notTheSame(row) }
                })
                box.addView(header)
            }
            val strip = LinearLayout(this@CleanActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.items.forEach { info -> strip.addView(tile(row, info, density)) }
            box.addView(HorizontalScrollView(this@CleanActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            })
            return box
        }

        private fun tile(row: Row, info: PhotoMatch.Info, density: Float): View {
            val item = ItemImageBinding.inflate(layoutInflater)
            val side = (112 * density).toInt()
            item.root.layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = (6 * density).toInt() }
            val isMarked = info.id in marked
            val label = "${info.width}×${info.height} · ${Formatter.formatShortFileSize(this@CleanActivity, info.bytes)}"
            item.size.text = if (info === row.keep) "★ $label" else label
            item.check.visibility = if (isMarked) View.VISIBLE else View.GONE
            item.check.text = "🗑"
            item.image.alpha = if (isMarked) 0.45f else 1f
            item.root.setBackgroundColor(if (info === row.keep) ContextCompat.getColor(this@CleanActivity, R.color.accent) else ContextCompat.getColor(this@CleanActivity, R.color.field))
            val entry = vault.find(info.id)
            val thumb = entry?.let { Thumbnails.cached(it) }
            if (thumb != null) {
                item.image.setImageBitmap(thumb)
            } else {
                item.image.setImageDrawable(null)
                item.image.setBackgroundColor(Color.DKGRAY)
                if (entry != null && !Thumbnails.hasFailed(entry) && loadingThumbs.add(entry.id)) {
                    runCatching {
                        thumbPool.execute {
                            Thumbnails.load(vault, entry, 256)
                            runOnUiThread {
                                loadingThumbs.remove(entry.id)
                                if (!isDestroyed) notifyDataSetChanged()
                            }
                        }
                    }
                }
            }
            item.image.scaleType = ImageView.ScaleType.CENTER_CROP
            item.root.setOnClickListener {
                if (!marked.remove(info.id)) marked += info.id
                refreshMarks()
            }
            item.root.setOnLongClickListener {
                startActivity(Intent(this@CleanActivity, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_ID, info.id))
                true
            }
            return item.root
        }
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
    }
}
