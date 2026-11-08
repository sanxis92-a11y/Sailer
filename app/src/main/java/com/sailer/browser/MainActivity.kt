package com.sailer.browser

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.BaseAdapter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.WindowManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.widget.EditText
import android.webkit.WebResourceRequest
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sailer.browser.lock.showSecure
import com.sailer.browser.adblock.CosmeticFilter
import com.sailer.browser.diag.CrashLog
import com.sailer.browser.databinding.ItemImageBinding
import com.sailer.browser.databinding.ItemMediaBinding
import com.sailer.browser.databinding.SheetImagesBinding
import com.sailer.browser.databinding.SheetMediaBinding
import com.sailer.browser.media.MediaElementInfo
import com.sailer.browser.media.MediaInspector
import com.sailer.browser.media.StorePolicy
import com.sailer.browser.media.MediaOption
import com.sailer.browser.media.GrabItem
import com.sailer.browser.media.PageImage
import com.sailer.browser.media.LinkGrabber
import com.sailer.browser.media.PageMediaInfo
import com.sailer.browser.adblock.AdBlocker
import com.sailer.browser.databinding.ActivityMainBinding
import com.sailer.browser.download.DownloadCenter
import com.sailer.browser.download.DownloadRequest
import com.sailer.browser.lock.SecureActivity
import com.sailer.browser.media.DetectedMedia
import com.sailer.browser.media.MediaKind
import com.sailer.browser.media.MediaSniffer
import com.sailer.browser.privacy.PrivacyManager
import com.sailer.browser.settings.Prefs
import com.sailer.browser.settings.SettingsActivity
import com.sailer.browser.tabs.Tab
import com.sailer.browser.tabs.TabManager
import com.sailer.browser.web.BrowserWebViewFactory
import com.sailer.browser.web.DownloadHandler
import com.sailer.browser.web.SailerChromeClient
import com.sailer.browser.web.SailerWebViewClient
import com.sailer.browser.web.UrlUtils
import com.sailer.browser.vault.Bookmarks
import com.sailer.browser.vault.BookmarksActivity
import com.sailer.browser.vault.Vault
import com.sailer.browser.vault.VaultActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : SecureActivity(),
    TabManager.Listener,
    SailerWebViewClient.Callbacks,
    SailerChromeClient.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabManager: TabManager
    private lateinit var webViewFactory: BrowserWebViewFactory
    private val chromeClient by lazy { SailerChromeClient(AdBlocker, this) }

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val bookmarksLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringExtra(BookmarksActivity.EXTRA_URL)?.let { url ->
                (tabManager.current ?: tabManager.newTab()).webView.loadUrl(url)
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webViewFactory = BrowserWebViewFactory(
            context = this,
            requestFilter = AdBlocker,
            downloadHandler = DownloadHandler { url, _, contentDisposition, mimeType, _ ->
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                startDownload(url, MediaSniffer.kindForMime(mimeType) ?: MediaKind.FILE, mimeType, name)
            },
        )
        val homeHtml = assets.open(Lang.asset("home.html")).bufferedReader().use { it.readText() }
        tabManager = TabManager({ createWebView() }, homeHtml, this)

        setUpAddressBar()
        binding.tabCount.setOnClickListener { showTabs() }
        binding.menuButton.setOnClickListener { showMenu(it) }
        binding.mediaButton.setOnClickListener { showMedia() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        val startUrl = intentUrl(intent)
        tabManager.newTab(startUrl)
        if (startUrl == null) focusAddressBar()
        if (savedInstanceState == null) CrashLog.takeLast(this)?.let { showCrashReport(it) }
    }

    /** La app se cerró por un error la última vez: se ofrece copiar el informe para enviarlo. */
    private fun showCrashReport(report: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_message)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Nauvel", report))
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
            .showSecure()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentUrl(intent)?.let { tabManager.newTab(it) }
    }

    override fun onResume() {
        super.onResume()
        AdBlocker.enabled = Prefs.get(this).adBlockEnabled
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setForceShowIcon(true)
            menu.findItem(R.id.action_forward).isEnabled = tabManager.current?.webView?.canGoForward() == true
            val client = tabManager.current?.webView?.webViewClient as? SailerWebViewClient
            val siteItem = menu.findItem(R.id.action_adblock_site)
            menu.findItem(R.id.action_grab_links).isVisible = BuildConfig.GRABBER
            siteItem.isVisible = AdBlocker.enabled && client?.pageHost != null
            siteItem.title = if (AdBlocker.isAllowedSite(client?.pageHost)) {
                getString(R.string.adblock_site_on)
            } else {
                getString(R.string.adblock_site_off, client?.blockedCount?.get() ?: 0)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_new_tab -> { tabManager.newTab(); focusAddressBar() }
                    R.id.action_close_tab -> closeCurrentTab()
                    R.id.action_reload -> tabManager.current?.webView?.reload()
                    R.id.action_forward -> tabManager.current?.webView?.goForward()
                    R.id.action_tabs -> showTabs()
                    R.id.action_adblock_site -> toggleAdBlockForSite()
                    R.id.action_media -> showMedia()
                    R.id.action_grab_links -> showLinkCollector()
                    R.id.action_add_bookmark -> addBookmark()
                    R.id.action_bookmarks ->
                        bookmarksLauncher.launch(Intent(this@MainActivity, BookmarksActivity::class.java))
                    R.id.action_vault -> startActivity(Intent(this@MainActivity, VaultActivity::class.java))
                    R.id.action_settings -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
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
            .showSecure()
    }

    private fun addBookmark() {
        val tab = tabManager.current ?: return
        val url = UrlUtils.forDisplay(tab.url).ifEmpty { return }
        thread {
            Bookmarks.get(this).add(tab.title, url)
            runOnUiThread { Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun toggleAdBlockForSite() {
        val webView = tabManager.current?.webView ?: return
        val host = (webView.webViewClient as? SailerWebViewClient)?.pageHost ?: return
        AdBlocker.setAllowedSite(this, host, !AdBlocker.isAllowedSite(host))
        thread { CosmeticFilter.rebuildScript() }
        webView.reload()
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
        updateMediaButton()
    }

    override fun onTabsChanged(tabs: List<Tab>) {
        binding.tabCount.text = tabs.size.toString()
    }

    // ---- SailerWebViewClient.Callbacks ----

    override fun onPageStarted(view: WebView, url: String) {
        val tab = tabManager.findByWebView(view) ?: return
        if (tab.url != url) tab.media.clear()
        tab.url = url
        if (tab === tabManager.current) updateMediaButton()
        if (tab === tabManager.current) showUrl(tab)
    }

    override fun onPageFinished(view: WebView, url: String) {
        CosmeticFilter.applyLate(view)
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

    override fun onResourceRequest(view: WebView, request: WebResourceRequest) {
        val url = request.url.toString()
        val kind = MediaSniffer.classify(url) ?: return
        runOnUiThread { addMedia(view, DetectedMedia(url, kind)) }
    }

    private var lastCrashUrl: String? = null
    private var lastCrashAt = 0L

    override fun onRendererGone(view: WebView) {
        runOnUiThread {
            val tab = tabManager.findByWebView(view) ?: return@runOnUiThread
            if (fullscreenView != null) onExitFullscreen()
            // Si la misma web vuelve a fallar enseguida, no se recarga para no entrar en bucle.
            val now = System.currentTimeMillis()
            val reload = !(tab.url == lastCrashUrl && now - lastCrashAt < 20_000)
            lastCrashUrl = tab.url
            lastCrashAt = now
            val wasCurrent = tab === tabManager.current
            if (tabManager.replaceCrashed(view, reload) != null && wasCurrent) {
                Toast.makeText(this, if (reload) R.string.page_crashed else R.string.page_crashed_again, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- SailerChromeClient.Callbacks ----

    override fun onProgress(view: WebView, progress: Int) {
        val tab = tabManager.findByWebView(view) ?: return
        tab.progress = progress
        if (tab === tabManager.current) updateProgress(tab)
    }

    override fun onOpenInNewTab(url: String) {
        tabManager.newTab(url)
    }

    private var lastPopupToast = 0L

    override fun onPopupBlocked(view: WebView) {
        (view.webViewClient as? SailerWebViewClient)?.blockedCount?.incrementAndGet()
        val now = System.currentTimeMillis()
        if (view === tabManager.current?.webView && now - lastPopupToast > 4000) {
            lastPopupToast = now
            Toast.makeText(this, R.string.popup_blocked, Toast.LENGTH_SHORT).show()
        }
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

    // ---- Descargas ----

    private fun createWebView(): WebView {
        val webView = webViewFactory.create(this, chromeClient)
        webView.setOnLongClickListener { onLongPress(webView) }
        return webView
    }

    private fun addMedia(view: WebView, media: DetectedMedia) {
        val tab = tabManager.findByWebView(view) ?: return
        if (!StorePolicy.allowsKind(media.kind)) return
        // Si ya hay una lista HLS, no hace falta listar también sus trozos.
        if (tab.media.containsKey(media.url)) return
        tab.media[media.url] = media
        if (tab === tabManager.current) updateMediaButton()
    }

    private fun updateMediaButton() {
        val hasMedia = tabManager.current?.media?.isNotEmpty() == true
        val color = ContextCompat.getColor(this, if (hasMedia) R.color.accent else R.color.text_dim)
        binding.mediaButton.imageTintList = ColorStateList.valueOf(color)
    }

    /** Busca vídeos/audios en la página y muestra una lista clara para elegir. */
    private fun showMedia() {
        val tab = tabManager.current ?: return
        tab.webView.evaluateJavascript(MediaSniffer.SCAN_SCRIPT) { result ->
            val info = parsePageInfo(result) ?: PageMediaInfo(tab.title, "", emptyList())
            info.elements.forEach { element ->
                if (element.src.startsWith("https://")) {
                    val kind = MediaSniffer.classify(element.src)
                        ?: if (element.isAudio) MediaKind.AUDIO else MediaKind.VIDEO
                    addMedia(tab.webView, DetectedMedia(element.src, kind))
                }
            }
            showMediaSheet(tab, info)
        }
    }

    private fun parsePageInfo(result: String?): PageMediaInfo? = runCatching {
        val json = JSONObject(JSONTokener(result).nextValue() as String)
        val items = json.optJSONArray("items") ?: JSONArray()
        val elements = (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            MediaElementInfo(
                src = o.optString("src"),
                width = o.optInt("w"),
                height = o.optInt("h"),
                duration = o.optDouble("d", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                poster = o.optString("poster"),
                isAudio = o.optBoolean("audio"),
            )
        }
        val imageArray = json.optJSONArray("images") ?: JSONArray()
        val images = (0 until imageArray.length()).map { i ->
            val o = imageArray.getJSONObject(i)
            PageImage(o.optString("src"), o.optInt("w"), o.optInt("h"))
        }
        val detailArray = json.optJSONArray("details") ?: JSONArray()
        val details = (0 until detailArray.length()).map { detailArray.getString(it) }
        PageMediaInfo(json.optString("title"), json.optString("image"), elements, images, details)
    }.getOrNull()

    private fun showMediaSheet(tab: Tab, info: PageMediaInfo) {
        val sheet = BottomSheetDialog(this)
        sheet.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val view = SheetMediaBinding.inflate(layoutInflater)
        sheet.setContentView(view.root)
        val pageTitle = info.title.ifBlank { tab.title }.ifBlank { UrlUtils.forDisplay(tab.url) }
        view.pageTitle.text = pageTitle
        sheet.show()

        val images = if (BuildConfig.GRABBER) {
            info.images.filter { StorePolicy.allowsDownload(it.url, tab.url) }
                .map { GrabItem(it.url, MediaKind.IMAGE, it.width, it.height, tab.url, pageTitle) }
                .let { LinkGrabber.dropSmallVariants(it) }
        } else {
            emptyList()
        }
        if (images.isNotEmpty()) {
            view.options.addView(imagesRow(view, images.size) {
                sheet.dismiss()
                showImagePicker(images, pageTitle, tab.url.takeIf { it.startsWith("https://") }, info.details)
            })
        }

        if (!StorePolicy.allowsDownload(tab.url, tab.url)) {
            view.loading.visibility = View.GONE
            view.empty.visibility = View.VISIBLE
            view.empty.setText(R.string.media_not_allowed)
            return
        }
        val detected = tab.media.values.filter { StorePolicy.allowsDownload(it.url, tab.url) }
        if (detected.isEmpty()) {
            view.loading.visibility = View.GONE
            view.empty.visibility = View.VISIBLE
            return
        }
        val userAgent = tab.webView.settings.userAgentString
        val referer = tab.url
        val inspector = MediaInspector(this)
        val job = lifecycleScope.launch {
            val thumbUrl = info.elements.firstOrNull { it.poster.isNotBlank() }?.poster ?: info.image
            launch {
                if (thumbUrl.isNotBlank()) {
                    inspector.loadThumbnail(thumbUrl, referer, userAgent)?.let { view.thumbnail.setImageBitmap(it) }
                }
            }
            val options = inspector.inspect(detected, info.elements, referer, userAgent)
            view.loading.visibility = View.GONE
            if (options.isEmpty()) view.empty.visibility = View.VISIBLE
            var bestMarked = false
            options.forEach { option ->
                val isBest = !bestMarked && !option.drm && !option.isAudio && !option.looksLikePreview
                if (isBest) bestMarked = true
                view.options.addView(mediaRow(view, option, isBest, pageTitle) { sheet.dismiss() })
            }
        }
        sheet.setOnDismissListener { job.cancel() }
    }

    private fun mediaRow(
        sheet: SheetMediaBinding,
        option: MediaOption,
        isBest: Boolean,
        pageTitle: String,
        onChosen: () -> Unit,
    ): View {
        val row = ItemMediaBinding.inflate(layoutInflater, sheet.options, false)
        row.icon.text = if (option.isAudio) "🎵" else "🎬"
        val type = getString(if (option.isAudio) R.string.media_audio else R.string.media_video)
        row.title.text = listOfNotNull(type, option.qualityLabel).joinToString(" · ")
        val size = when {
            option.sizeBytes > 0 -> (if (option.sizeIsEstimate) "≈ " else "") +
                Formatter.formatShortFileSize(this, option.sizeBytes)
            else -> getString(R.string.media_unknown_size)
        }
        row.details.text = listOfNotNull(
            option.format,
            size,
            option.durationSec.takeIf { it >= 1 }?.let { formatDuration(it) },
            option.host.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        val (note, color) = when {
            option.drm -> getString(R.string.media_drm) to R.color.text_dim
            isBest -> getString(R.string.media_best) to R.color.accent
            option.looksLikePreview -> getString(R.string.media_preview) to R.color.text_dim
            else -> null to R.color.text_dim
        }
        if (note != null) {
            row.note.visibility = View.VISIBLE
            row.note.text = note
            row.note.setTextColor(ContextCompat.getColor(this, color))
        }
        row.download.isEnabled = !option.drm
        row.download.setOnClickListener {
            startDownload(
                option.url, option.kind,
                titleSuffix = option.qualityLabel,
                audioUrl = option.audioUrl,
                videoId = option.videoId,
            )
            onChosen()
        }
        return row.root
    }

    private fun imagesRow(sheet: SheetMediaBinding, count: Int, onOpen: () -> Unit): View {
        val row = ItemMediaBinding.inflate(layoutInflater, sheet.options, false)
        row.icon.text = "🖼️"
        row.title.setText(R.string.images_row)
        row.details.text = getString(R.string.images_count, count)
        row.download.setText(R.string.images_view)
        row.download.setOnClickListener { onOpen() }
        row.root.setOnClickListener { onOpen() }
        return row.root
    }

    /**
     * Cuadrícula con lo encontrado (imágenes, y vídeos/audios del recolector):
     * se marcan las que se quieren y se bajan todas juntas. Con [crawlFrom], el
     * botón «Buscar en más páginas» recorre la galería siguiendo «Siguiente».
     */
    private fun showImagePicker(
        initial: List<GrabItem>,
        pageTitle: String,
        crawlFrom: String?,
        initialDetails: List<String> = emptyList(),
    ) {
        val sheet = BottomSheetDialog(this)
        sheet.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val view = SheetImagesBinding.inflate(layoutInflater)
        sheet.setContentView(view.root)

        val items = initial.distinctBy { it.url }.toMutableList()
        val selected = linkedSetOf<String>()
        val userAgent = tabManager.current?.webView?.settings?.userAgentString ?: WebSettings.getDefaultUserAgent(this)
        val inspector = MediaInspector(this)
        val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        val loading = mutableMapOf<String, Job>()
        val limit = Semaphore(4)
        var crawlJob: Job? = null
        var deepJob: Job? = null
        val details = linkedSetOf<String>().apply { addAll(initialDetails) }
        val grabber = LinkGrabber(this, userAgent)

        fun addFound(found: List<GrabItem>) {
            // Juntar con lo que ya hay y quitar las versiones pequeñas de la misma foto.
            val merged = LinkGrabber.dropSmallVariants(items + found.filter { StorePolicy.allowsDownload(it.url, it.referer) })
            val kept = merged.mapTo(HashSet()) { it.url }
            selected.retainAll(kept)
            items.clear()
            items.addAll(merged)
        }

        fun updateDeepButton() {
            view.deepScan.visibility = if (details.isEmpty() && deepJob == null) View.GONE else View.VISIBLE
            if (deepJob == null) view.deepScan.text = getString(R.string.grab_deep, minOf(details.size, LinkGrabber.MAX_DETAILS))
        }

        fun refresh() {
            view.title.text = getString(R.string.images_count, items.size)
            view.download.isEnabled = selected.isNotEmpty()
            view.download.text = if (selected.isEmpty()) getString(R.string.images_download_none)
            else getString(R.string.images_download, selected.size)
            view.selectAll.setText(if (selected.size == items.size && items.isNotEmpty()) R.string.images_select_none else R.string.images_select_all)
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = convertView?.let { ItemImageBinding.bind(it) } ?: ItemImageBinding.inflate(layoutInflater, parent, false)
                val item = items[position]
                val isImage = item.kind == MediaKind.IMAGE
                cell.size.text = when {
                    item.width > 0 && item.height > 0 -> "${item.width}×${item.height}"
                    !isImage -> URLUtil.guessFileName(item.url, null, null).take(24)
                    else -> ""
                }
                cell.size.visibility = if (cell.size.text.isEmpty()) View.GONE else View.VISIBLE
                cell.icon.visibility = if (isImage) View.GONE else View.VISIBLE
                cell.icon.text = if (item.kind == MediaKind.AUDIO) "🎵" else "🎬"
                val isSelected = item.url in selected
                cell.check.visibility = if (isSelected) View.VISIBLE else View.GONE
                cell.frame.setBackgroundColor(ContextCompat.getColor(this@MainActivity, if (isSelected) R.color.accent else R.color.field))
                val bitmap = if (isImage) cache.get(item.url) else null
                cell.image.setImageBitmap(bitmap)
                if (isImage && bitmap == null && item.url !in loading) {
                    loading[item.url] = lifecycleScope.launch {
                        val loaded = limit.withPermit { inspector.loadThumbnail(item.url, item.referer, userAgent) }
                        loading.remove(item.url)
                        if (loaded != null) {
                            cache.put(item.url, loaded)
                            notifyDataSetChanged()
                        }
                    }
                }
                return cell.root
            }
        }
        view.grid.adapter = adapter
        view.grid.setOnItemClickListener { _, _, position, _ ->
            val url = items[position].url
            if (!selected.remove(url)) selected.add(url)
            adapter.notifyDataSetChanged()
            refresh()
        }
        view.selectAll.setOnClickListener {
            if (selected.size == items.size) selected.clear() else selected.addAll(items.map { it.url })
            adapter.notifyDataSetChanged()
            refresh()
        }
        if (crawlFrom != null) {
            view.morePages.visibility = View.VISIBLE
            view.morePages.setOnClickListener {
                view.morePages.isEnabled = false
                crawlJob = lifecycleScope.launch {
                    grabber.crawl(crawlFrom) { number, page ->
                        withContext(Dispatchers.Main) {
                            addFound(page.items)
                            details.addAll(page.details)
                            view.morePages.text = getString(R.string.grab_searching_page, number)
                            updateDeepButton()
                            adapter.notifyDataSetChanged()
                            refresh()
                        }
                    }
                    view.morePages.text = getString(R.string.grab_done)
                }
            }
        }
        // Análisis a fondo: abre la página de cada miniatura y coge su foto grande.
        updateDeepButton()
        view.deepScan.setOnClickListener {
            if (deepJob != null) return@setOnClickListener
            val pages = details.toList()
            view.deepScan.isEnabled = false
            deepJob = lifecycleScope.launch {
                grabber.deep(pages) { done, item ->
                    withContext(Dispatchers.Main) {
                        if (item != null) addFound(listOf(item))
                        view.deepScan.text = getString(R.string.grab_deep_progress, done, minOf(pages.size, LinkGrabber.MAX_DETAILS))
                        adapter.notifyDataSetChanged()
                        refresh()
                    }
                }
                view.deepScan.text = getString(R.string.grab_deep_done)
            }
        }
        view.download.setOnClickListener {
            downloadItems(items.filter { it.url in selected }, pageTitle)
            sheet.dismiss()
        }
        refresh()
        sheet.setOnDismissListener {
            crawlJob?.cancel()
            deepJob?.cancel()
            loading.values.forEach { it.cancel() }
        }
        sheet.show()
    }

    private fun downloadItems(chosen: List<GrabItem>, pageTitle: String) {
        val tab = tabManager.current
        val allowed = chosen.filter { StorePolicy.allowsDownload(it.url, it.referer ?: tab?.url) && StorePolicy.allowsKind(it.kind) }
        if (allowed.isEmpty()) return
        withDownloadNotice {
            val used = mutableSetOf<String>()
            allowed.forEachIndexed { i, item ->
                // Galería automática: carpeta de la web y, dentro, una con el nombre de la página.
                val folder = Vault.galleryFolder(item.referer ?: tab?.url, item.album ?: pageTitle)
                // Nombre del archivo en la web si lo tiene; si no, el título de la página numerado.
                val fromUrl = URLUtil.guessFileName(item.url, null, null)
                    .takeIf { MediaSniffer.kindForExtension(MediaSniffer.extensionOf(item.url)) == item.kind }
                val name = fromUrl?.takeIf { used.add(it) }
                DownloadCenter.enqueue(
                    DownloadRequest(
                        url = UrlUtils.upgradeToHttps(item.url),
                        title = if (name == null && allowed.size > 1) "$pageTitle ${i + 1}" else pageTitle,
                        kind = item.kind,
                        referer = item.referer ?: tab?.url,
                        userAgent = tab?.webView?.settings?.userAgentString ?: "",
                        fileName = name,
                        folder = folder,
                        album = item.album ?: pageTitle,
                    )
                )
            }
            askNotificationPermission()
            Toast.makeText(this, getString(R.string.images_started, allowed.size), Toast.LENGTH_SHORT).show()
        }
    }

    /** Recolector de enlaces: se pegan direcciones y se saca todo lo descargable de cada una. */
    private fun showLinkCollector() {
        val clip = runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull().orEmpty()
        val input = EditText(this).apply {
            hint = getString(R.string.grab_hint)
            minLines = 3
            maxLines = 8
            setText(LinkGrabber.urlsIn(clip).joinToString("\n"))
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.grab_title)
            .setMessage(R.string.grab_message)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.grab_search) { _, _ -> collectLinks(LinkGrabber.urlsIn(input.text.toString())) }
            .showSecure()
    }

    private fun collectLinks(urls: List<String>) {
        if (urls.isEmpty()) {
            Toast.makeText(this, R.string.grab_no_links, Toast.LENGTH_SHORT).show()
            return
        }
        val userAgent = tabManager.current?.webView?.settings?.userAgentString ?: WebSettings.getDefaultUserAgent(this)
        val grabber = LinkGrabber(this, userAgent)
        Toast.makeText(this, R.string.grab_working, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val found = mutableListOf<GrabItem>()
            val details = mutableListOf<String>()
            var title = ""
            urls.map { UrlUtils.upgradeToHttps(it) }.forEach { url ->
                val direct = grabber.directItem(url)
                if (direct != null) {
                    found.add(direct)
                } else {
                    grabber.crawl(url, maxPages = 1) { _, page ->
                        found.addAll(page.items)
                        details.addAll(page.details)
                        if (title.isEmpty()) title = page.title
                    }
                }
            }
            val allowed = LinkGrabber.dropSmallVariants(found.distinctBy { it.url })
                .filter { StorePolicy.allowsDownload(it.url, it.referer) }
            if (allowed.isEmpty() && details.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.grab_nothing, Toast.LENGTH_LONG).show()
            } else {
                // Si es una sola página, se puede seguir por sus páginas siguientes (galerías).
                val single = urls.singleOrNull()?.takeIf { grabber.directItem(it) == null }?.let { UrlUtils.upgradeToHttps(it) }
                showImagePicker(allowed, title.ifBlank { getString(R.string.app_name) }, single, details.distinct())
            }
        }
    }

    /** La primera vez que se descarga algo se muestra el aviso de derechos de autor. */
    private fun withDownloadNotice(action: () -> Unit) {
        val prefs = Prefs.get(this)
        if (prefs.downloadNoticeAccepted) {
            action()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_notice_title)
            .setMessage(R.string.download_notice)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_notice_ok) { _, _ ->
                prefs.downloadNoticeAccepted = true
                action()
            }
            .showSecure()
    }

    private fun askNotificationPermission() {
        // Para que la descarga siga con la app cerrada, Android 13+ pide permiso de notificaciones.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    private fun onLongPress(webView: WebView): Boolean {
        val hit = webView.hitTestResult
        val url = hit.extra ?: return false
        return when (hit.type) {
            WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.media_image)
                    .setItems(arrayOf(getString(R.string.download_image), getString(R.string.open_new_tab))) { _, which ->
                        if (which == 0) startDownload(url, MediaKind.IMAGE) else tabManager.newTab(UrlUtils.fromUserInput(url))
                    }
                    .showSecure()
                true
            }
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(url.take(120))
                    .setItems(arrayOf(getString(R.string.open_new_tab), getString(R.string.download_link))) { _, which ->
                        if (which == 0) {
                            tabManager.newTab(UrlUtils.fromUserInput(url))
                        } else {
                            startDownload(url, MediaSniffer.classify(url) ?: MediaKind.FILE)
                        }
                    }
                    .showSecure()
                true
            }
            else -> false
        }
    }

    private fun startDownload(
        url: String,
        kind: MediaKind,
        mimeType: String? = null,
        fileName: String? = null,
        titleSuffix: String? = null,
        audioUrl: String? = null,
        videoId: String? = null,
    ) {
        val tab = tabManager.current
        if (!StorePolicy.allowsDownload(url, tab?.url) || !StorePolicy.allowsKind(kind)) {
            Toast.makeText(this, R.string.media_not_allowed, Toast.LENGTH_LONG).show()
            return
        }
        withDownloadNotice { enqueueDownload(url, kind, mimeType, fileName, titleSuffix, audioUrl, videoId) }
    }

    private fun enqueueDownload(
        url: String,
        kind: MediaKind,
        mimeType: String?,
        fileName: String?,
        titleSuffix: String?,
        audioUrl: String?,
        videoId: String?,
    ) {
        val tab = tabManager.current
        val baseTitle = tab?.title?.ifBlank { null } ?: getString(R.string.app_name)
        val secureUrl = if (url.startsWith("data:")) url else UrlUtils.upgradeToHttps(url)
        DownloadCenter.enqueue(
            DownloadRequest(
                url = secureUrl,
                title = if (titleSuffix != null) "$baseTitle ($titleSuffix)" else baseTitle,
                kind = kind,
                referer = tab?.url,
                userAgent = tab?.webView?.settings?.userAgentString ?: "",
                mimeType = mimeType,
                fileName = fileName,
                audioUrl = audioUrl,
                videoId = videoId,
                // Las imágenes van a la galería «web/página»; vídeos y audios, a la carpeta de la web.
                folder = Vault.galleryFolder(tab?.url, if (kind == MediaKind.IMAGE) baseTitle else null),
                album = baseTitle,
            )
        )
        askNotificationPermission()
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
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
