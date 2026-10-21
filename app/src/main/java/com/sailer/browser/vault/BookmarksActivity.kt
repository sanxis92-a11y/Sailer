package com.sailer.browser.vault

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sailer.browser.R
import com.sailer.browser.databinding.ActivityBookmarksBinding
import com.sailer.browser.databinding.ItemVaultBinding
import com.sailer.browser.lock.AppLock
import com.sailer.browser.lock.LockActivity
import com.sailer.browser.lock.SecureActivity

/** Favoritos privados. Si hay PIN, se pide cada vez que se abren. */
class BookmarksActivity : SecureActivity() {

    private lateinit var binding: ActivityBookmarksBinding
    private lateinit var bookmarks: Bookmarks
    private var items: List<Bookmarks.Bookmark> = emptyList()
    private var verified = false

    private val verifyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            verified = true
            showList()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        bookmarks = Bookmarks.get(this)

        binding.list.adapter = adapter
        binding.list.emptyView = binding.empty
        binding.list.setOnItemClickListener { _, _, position, _ ->
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URL, items[position].url))
            finish()
        }
        binding.list.setOnItemLongClickListener { _, _, position, _ ->
            showOptions(items[position])
            true
        }

        if (AppLock.isConfigured(this)) {
            binding.list.visibility = View.GONE
            verifyLauncher.launch(Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_VERIFY, true))
        } else {
            binding.noPinHint.visibility = View.VISIBLE
            verified = true
            showList()
        }
    }

    private fun showList() {
        binding.list.visibility = View.VISIBLE
        items = bookmarks.list()
        adapter.notifyDataSetChanged()
    }

    private fun showOptions(bookmark: Bookmarks.Bookmark) {
        MaterialAlertDialogBuilder(this)
            .setTitle(bookmark.title)
            .setItems(arrayOf(getString(R.string.vault_rename), getString(R.string.vault_delete))) { _, which ->
                if (which == 0) {
                    val field = EditText(this).apply { setText(bookmark.title) }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.vault_rename)
                        .setView(field)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            bookmarks.rename(bookmark, field.text.toString().trim().ifEmpty { bookmark.title })
                            showList()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    bookmarks.remove(bookmark)
                    showList()
                }
            }
            .show()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = if (verified) items.size else 0
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView?.let { ItemVaultBinding.bind(it) }
                ?: ItemVaultBinding.inflate(layoutInflater, parent, false)
            val item = items[position]
            row.icon.text = "⭐"
            row.title.text = item.title
            row.subtitle.text = Uri.parse(item.url).host ?: item.url
            return row.root
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
