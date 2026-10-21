package com.sailer.browser

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sailer.browser.databinding.ActivityMainBinding
import com.sailer.browser.privacy.PrivacyManager
import com.sailer.browser.tabs.Tab
import com.sailer.browser.tabs.TabManager
import com.sailer.browser.web.BrowserWebViewFactory
import com.sailer.browser.web.DownloadHandler
import com.sailer.browser.web.RequestFilter
import com.sailer.browser.web.SailerChromeClient
import com.sailer.browser.web.SailerWebViewClient
import com.sailer.browser.web.UrlUtils

class MainActivity : AppCompatActivity(),
    TabManager.Listener,
    SailerWebViewClient.Callbacks,
    SailerChromeClient.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabManager: TabManager
    private lateinit var webViewFactory: BrowserWebViewFactory
    private val chromeClient by lazy { SailerChromeClient(this) }

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Impide capturas de pantalla, grabaciones y la vista previa en "recientes".
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webViewFactory = BrowserWebViewFactory(
            context = this,
            requestFilter = RequestFilter.ALLOW_ALL,
            downloadHandler = DownloadHandler.notYetAvailable(this),
        )
        tabManager = TabManager({ webViewFactory.create(this, chromeClient) }, this)

        setUpAddressBar()
        binding.tabCount.setOnClickListener { showTabs() }
        binding.menuButton.setOnClickListener { showMenu(it) }
        onBackPressedDispatcher.addCallback(this, backCallback)

        val startUrl = intentUrl(intent)
        tabManager.newTab(startUrl)
        if (startUrl == null) focusAddressBar()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentUrl(intent)?.let { tabManager.newTab(it) }
    }

    override fun onResume() {
        super.onResume()
        tabManager.current?.webView?.onResume()
    }

    override fun onPause() {
        tabManager.current?.webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        val webViews = tabManager.detachAll()
        if (isFinishing) PrivacyManager.wipeSession(this, webViews)
        webViews.forEach { TabManager.destroy(it) }
        super.onDestroy()
    }

    // ---- Barra de direcciones ----

    private fun setUpAddressBar() {
        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                navigate(binding.addressBar.text.toString())
                true
            } else {
                false
            }
        }
        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) showUrl(tabManager.current)
        }
    }

    private fun navigate(input: String) {
        val url = UrlUtils.fromUserInput(input)
        if (url.isEmpty()) return
        val tab = tabManager.current ?: tabManager.newTab()
        tab.webView.loadUrl(url)
        hideKeyboard()
        tab.webView.requestFocus()
    }

    private fun showUrl(tab: Tab?) {
        if (!binding.addressBar.hasFocus()) {
            binding.addressBar.setText(UrlUtils.forDisplay(tab?.url))
        }
    }

    private fun focusAddressBar() {
        binding.addressBar.requestFocus()
        binding.addressBar.post {
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(binding.addressBar, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
        binding.addressBar.clearFocus()
    }

    // ---- Menú y pestañas ----

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            menu.findItem(R.id.action_forward).isEnabled = tabManager.current?.webView?.canGoForward() == true
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_new_tab -> { tabManager.newTab(); focusAddressBar() }
                    R.id.action_close_tab -> closeCurrentTab()
                    R.id.action_reload -> tabManager.current?.webView?.reload()
                    R.id.action_forward -> tabManager.current?.webView?.goForward()
                    R.id.action_tabs -> showTabs()
                    R.id.action_wipe_exit -> wipeAndExit()
                }
                true
            }
        }.show()
    }

    private fun showTabs() {
        val tabs = tabManager.tabs
        val titles = tabs.map { tab ->
            val title = tab.title.ifBlank { UrlUtils.forDisplay(tab.url) }
                .ifBlank { getString(R.string.new_tab_title) }
            if (tab === tabManager.current) "● $title" else title
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tabs_title)
            .setItems(titles) { _, which -> tabs.getOrNull(which)?.let { tabManager.select(it) } }
            .setPositiveButton(R.string.menu_new_tab) { _, _ -> tabManager.newTab(); focusAddressBar() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun closeCurrentTab() {
        val tab = tabManager.current ?: return
        if (tabManager.tabs.size == 1) {
            // Al cerrar la última pestaña se abre una vacía en su lugar.
            tabManager.newTab()
            focusAddressBar()
        }
        tabManager.close(tab)
    }

    private fun wipeAndExit() {
        Toast.makeText(this, R.string.wiped, Toast.LENGTH_SHORT).show()
        finishAndRemoveTask() // onDestroy se encarga de borrarlo todo
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val tab = tabManager.current
            when {
                fullscreenView != null -> onExitFullscreen()
                tab?.webView?.canGoBack() == true -> tab.webView.goBack()
                tabManager.tabs.size > 1 && tab != null -> tabManager.close(tab)
                else -> finish() // salir = borrar todo
            }
        }
    }

    // ---- TabManager.Listener ----

    override fun onCurrentTabChanged(tab: Tab?) {
        binding.webContainer.removeAllViews()
        if (tab != null) {
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            binding.webContainer.addView(
                tab.webView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        showUrl(tab)
        updateProgress(tab)
    }

    override fun onTabsChanged(tabs: List<Tab>) {
        binding.tabCount.text = tabs.size.toString()
    }

    // ---- SailerWebViewClient.Callbacks ----

    override fun onPageStarted(view: WebView, url: String) {
        val tab = tabManager.findByWebView(view) ?: return
        tab.url = url
        if (tab === tabManager.current) showUrl(tab)
    }

    override fun onPageFinished(view: WebView, url: String) {
        val tab = tabManager.findByWebView(view) ?: return
        tab.url = url
        if (tab === tabManager.current) showUrl(tab)
    }

    override fun onBlocked(view: WebView, reason: SailerWebViewClient.BlockReason) {
        if (tabManager.findByWebView(view) !== tabManager.current) return
        val message = when (reason) {
            SailerWebViewClient.BlockReason.INSECURE_HTTP -> R.string.http_blocked
            SailerWebViewClient.BlockReason.BAD_CERTIFICATE -> R.string.ssl_error
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---- SailerChromeClient.Callbacks ----

    override fun onProgress(view: WebView, progress: Int) {
        val tab = tabManager.findByWebView(view) ?: return
        tab.progress = progress
        if (tab === tabManager.current) updateProgress(tab)
    }

    override fun onTitle(view: WebView, title: String) {
        tabManager.findByWebView(view)?.title = title
    }

    override fun onEnterFullscreen(view: View, onExit: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            onExit.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = onExit
        view.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
        (window.decorView as FrameLayout).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onExitFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as FrameLayout).removeView(view)
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
    }

    // ---- Utilidades ----

    private fun updateProgress(tab: Tab?) {
        val progress = tab?.progress ?: 100
        binding.progress.progress = progress
        binding.progress.visibility = if (progress in 0..99) View.VISIBLE else View.GONE
    }

    private fun intentUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.dataString?.let { UrlUtils.fromUserInput(it) }
    }
}
