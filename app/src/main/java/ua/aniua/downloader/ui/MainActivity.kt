package ua.aniua.downloader.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import ua.aniua.downloader.R
import ua.aniua.downloader.data.ShortcutRepository
import ua.aniua.downloader.databinding.ActivityMainBinding
import ua.aniua.downloader.databinding.DialogAddShortcutBinding
import ua.aniua.downloader.extractor.ExtractorRouter
import ua.aniua.downloader.extractor.ExtractorUtils
import ua.aniua.downloader.extractor.GenericPlayerResolver
import ua.aniua.downloader.extractor.WebViewStreamResolver
import ua.aniua.downloader.extractor.YouTubeExtractor
import ua.aniua.downloader.model.DownloadTask
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import ua.aniua.downloader.model.SiteShortcut
import ua.aniua.downloader.service.DownloadQueueManager
import ua.aniua.downloader.service.DownloadService
import ua.aniua.downloader.ui.adapter.EpisodeAdapter
import ua.aniua.downloader.ui.adapter.ShortcutAdapter
import ua.security.hardening.AppSecurityManager
import ua.security.hardening.ThreatLevel
import ua.security.network.NetworkSecurityManager
import java.util.UUID
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), EpisodeAdapter.OnEpisodeActionListener, ShortcutAdapter.OnShortcutClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var shortcutAdapter: ShortcutAdapter
    private lateinit var shortcutRepository: ShortcutRepository
    private lateinit var httpClient: OkHttpClient
    private lateinit var queueManager: DownloadQueueManager

    private var currentMediaInfo: MediaInfo?
        get() = viewModel.currentMediaInfo
        set(value) { viewModel.currentMediaInfo = value }
    private var currentStudio: DubStudio?
        get() = viewModel.currentStudio
        set(value) {
            viewModel.currentStudio = value
            viewModel.currentStudioId = value?.id
        }
    private var currentPlayer: PlayerSource?
        get() = viewModel.currentPlayer
        set(value) {
            viewModel.currentPlayer = value
            viewModel.currentPlayerId = value?.id
        }
    private var allSelected: Boolean
        get() = viewModel.allSelected
        set(value) { viewModel.allSelected = value }
    private var isDescExpanded: Boolean
        get() = viewModel.isDescExpanded
        set(value) { viewModel.isDescExpanded = value }
    private var analyzeJob: Job? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission granted or denied; foreground service still runs */ }

    private val interceptedBrowserMediaUrls = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val urlInTextPattern = Pattern.compile("https?://\\S+")

    private val downloadBadgeListener: (DownloadTask) -> Unit = {
        if (!isFinishing && !isDestroyed) {
            updateActiveDownloadsBadge()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        httpClient = NetworkSecurityManager.getInstance(this).hardenedHttpClient
        queueManager = DownloadQueueManager.getInstance(this)
        shortcutRepository = ShortcutRepository(this)

        initViews()
        initShortcuts()
        initWebView()
        setupListeners()
        setupBackNavigation()
        performSecurityAuditAsync()
        restoreStateFromViewModel()
        handleIncomingIntent(intent)
    }

    private fun restoreStateFromViewModel() {
        if (viewModel.lastUrl.isNotEmpty() && binding.etUrl.text.isNullOrEmpty()) {
            binding.etUrl.setText(viewModel.lastUrl)
        }
        viewModel.currentMediaInfo?.let { savedInfo ->
            displayMediaInfo(savedInfo, preserveSelection = true)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun extractFirstUrlOrTrimmed(rawText: String?): String {
        val trimmed = rawText?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val matcher = urlInTextPattern.matcher(trimmed)
        return if (matcher.find()) matcher.group() else trimmed
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val extractedUrl = extractFirstUrlOrTrimmed(intent.getStringExtra(Intent.EXTRA_TEXT))
        if (extractedUrl.isNotEmpty()) {
            openDownloadMenu(extractedUrl)
        }
    }

    private fun setupSpinnerAdapter(spinner: android.widget.Spinner, items: List<String>) {
        val adapter = ArrayAdapter(this, R.layout.item_spinner_selected, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun initViews() {
        binding.btnDownloadsHeader.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        episodeAdapter = EpisodeAdapter(this)
        binding.rvEpisodes.adapter = episodeAdapter

        setupSpinnerAdapter(binding.spinnerFormat, listOf("mp4", "mkv"))
        setupSpinnerAdapter(binding.spinnerQuality, listOf("1080p", "720p", "480p", "360p"))

        updateActiveDownloadsBadge()
        queueManager.addListener(downloadBadgeListener)
    }

    private fun initShortcuts() {
        binding.rvShortcuts.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        shortcutAdapter = ShortcutAdapter(this)
        binding.rvShortcuts.adapter = shortcutAdapter
        refreshShortcuts()
    }

    private fun refreshShortcuts() {
        val list = shortcutRepository.getShortcuts()
        shortcutAdapter.setShortcuts(list)
        binding.tvShortcutsHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onAddShortcutClick() {
        showShortcutDialog(null)
    }

    override fun onShortcutClick(shortcut: SiteShortcut) {
        openShortcutInBrowser(shortcut)
    }

    override fun onShortcutLongClick(shortcut: SiteShortcut) {
        val options = arrayOf("Перейти на сайт", "Редагувати", "Видалити ярлик")
        MaterialAlertDialogBuilder(this)
            .setTitle(shortcut.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openShortcutInBrowser(shortcut)
                    1 -> showShortcutDialog(shortcut)
                    2 -> {
                        shortcutRepository.removeShortcut(shortcut.id)
                        refreshShortcuts()
                    }
                }
            }
            .show()
    }

    private fun showShortcutDialog(existing: SiteShortcut?) {
        val dialogBinding = DialogAddShortcutBinding.inflate(LayoutInflater.from(this))
        if (existing != null) {
            dialogBinding.etShortcutUrl.setText(existing.url)
            dialogBinding.etShortcutName.setText(existing.name)
        }

        dialogBinding.tilShortcutUrl.setEndIconOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.let { extractFirstUrlOrTrimmed(it) }
                .orEmpty()
            if (clipText.isEmpty()) {
                Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
            } else {
                dialogBinding.etShortcutUrl.setText(clipText)
                dialogBinding.tilShortcutUrl.error = null
            }
        }

        val titleRes = if (existing == null) R.string.shortcut_dialog_title_add else R.string.shortcut_dialog_title_edit
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.shortcut_cancel, null)
            .setPositiveButton(R.string.shortcut_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawUrl = dialogBinding.etShortcutUrl.text?.toString()?.trim() ?: ""
                val customName = dialogBinding.etShortcutName.text?.toString()?.trim()
                val saved = if (existing == null) {
                    shortcutRepository.addShortcut(rawUrl, customName)
                } else {
                    shortcutRepository.updateShortcut(existing.id, rawUrl, customName)
                }
                if (saved == null) {
                    dialogBinding.tilShortcutUrl.error = getString(R.string.shortcut_error_invalid_url)
                } else {
                    refreshShortcuts()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun openShortcutInBrowser(shortcut: SiteShortcut) {
        openBrowserUrl(shortcut.url, shortcut.name)
    }

    private fun openBrowserUrl(url: String, title: String? = null) {
        val safeUrl = ExtractorUtils.ensureHttpsScheme(url)
        if (!safeUrl.startsWith("https://", ignoreCase = true) && !safeUrl.startsWith("http://", ignoreCase = true)) {
            Toast.makeText(this, getString(R.string.shortcut_error_invalid_url), Toast.LENGTH_SHORT).show()
            return
        }
        binding.scrollDownloader.visibility = View.GONE
        binding.cardBottomDownloadBar.visibility = View.GONE
        binding.layoutBrowser.visibility = View.VISIBLE
        binding.toolbar.title = title ?: safeUrl
        binding.webviewShortcut.loadUrl(safeUrl)
    }

    private fun closeBrowser() {
        binding.layoutBrowser.visibility = View.GONE
        binding.scrollDownloader.visibility = View.VISIBLE
        binding.toolbar.title = getString(R.string.app_name)
        binding.progressWebLoader.visibility = View.GONE
        binding.swipeRefreshCatalog.isRefreshing = false
        updateBottomBarVisibility()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webviewShortcut, true)

        val webView = binding.webviewShortcut
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.aniyomi_bg))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = ExtractorUtils.DEFAULT_UA
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressWebLoader.visibility = View.VISIBLE
                    binding.progressWebLoader.progress = newProgress
                } else {
                    binding.progressWebLoader.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString()
                if (WebViewStreamResolver.isInterceptableMediaUrl(reqUrl) && reqUrl != null && !interceptedBrowserMediaUrls.contains(reqUrl)) {
                    interceptedBrowserMediaUrls.add(reqUrl)
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                interceptedBrowserMediaUrls.clear()
                binding.swipeRefreshCatalog.isRefreshing = true
                binding.progressWebLoader.visibility = View.VISIBLE
                updateWebNavButtons()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                binding.swipeRefreshCatalog.isRefreshing = false
                binding.progressWebLoader.visibility = View.GONE
                updateWebNavButtons()

                val isDownloadable = url != null &&
                    (url.startsWith("http://") || url.startsWith("https://"))
                binding.btnWebDownloadCurrent.visibility = if (isDownloadable) View.VISIBLE else View.GONE
            }
        }

        binding.swipeRefreshCatalog.apply {
            setColorSchemeColors(ContextCompat.getColor(this@MainActivity, R.color.primary))
            setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this@MainActivity, R.color.aniyomi_surface))
            setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
            setOnRefreshListener { webView.reload() }
        }

        binding.btnWebClose.setOnClickListener {
            closeBrowser()
        }
        binding.btnWebBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        binding.btnWebForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        binding.btnWebRefresh.setOnClickListener {
            webView.reload()
        }
        binding.btnWebDownloadCurrent.setOnClickListener {
            val currentUrl = webView.url
            if (currentUrl != null && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) {
                webView.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})();") { rawHtml ->
                    CookieManager.getInstance().flush()
                    val html = WebViewStreamResolver.unescapeEvalResult(rawHtml).takeIf { it.isNotBlank() }
                    openDownloadMenu(currentUrl, html)
                }
            }
        }

        updateWebNavButtons()
    }

    private fun updateWebNavButtons() {
        val canBack = binding.webviewShortcut.canGoBack()
        val canForward = binding.webviewShortcut.canGoForward()
        binding.btnWebBack.isEnabled = canBack
        binding.btnWebBack.alpha = if (canBack) 1.0f else 0.35f
        binding.btnWebForward.isEnabled = canForward
        binding.btnWebForward.alpha = if (canForward) 1.0f else 0.35f
    }

    private fun openDownloadMenu(url: String, preloadedHtml: String? = null) {
        closeBrowser()
        binding.etUrl.setText(url)
        startAnalyze(url, preloadedHtml)
    }

    private fun performSecurityAuditAsync() {
        lifecycleScope.launch {
            val audit = withContext(Dispatchers.Default) {
                AppSecurityManager.getInstance(this@MainActivity).performAudit()
            }
            if (isFinishing || isDestroyed) return@launch

            val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (audit.overallThreatLevel == ThreatLevel.ENVIRONMENT_RISK) {
                binding.cardSecurityBanner.visibility = View.VISIBLE
                binding.tvSecurityBannerText.text = getString(R.string.security_warning_msg)
            } else if (audit.overallThreatLevel == ThreatLevel.CRITICAL_ATTACK) {
                if (isDebug) {
                    binding.cardSecurityBanner.visibility = View.VISIBLE
                    binding.tvSecurityBannerText.text = "Режим розробника (Debug): перевірки безпеки адаптовано"
                    return@launch
                }
                Toast.makeText(this@MainActivity, getString(R.string.security_critical_msg), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupListeners() {
        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearUrl.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                triggerAnalyzeFromInput()
                true
            } else {
                false
            }
        }

        binding.btnClearUrl.setOnClickListener {
            binding.etUrl.setText("")
            binding.etUrl.error = null
        }

        binding.btnPasteUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.let { extractFirstUrlOrTrimmed(it) }
                .orEmpty()
            if (clipText.isEmpty()) {
                Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
            } else {
                binding.etUrl.setText(clipText)
                startAnalyze(clipText)
            }
        }

        binding.btnAnalyze.setOnClickListener {
            triggerAnalyzeFromInput()
        }

        binding.containerDescription.setOnClickListener {
            isDescExpanded = !isDescExpanded
            binding.tvMediaDesc.maxLines = if (isDescExpanded) Int.MAX_VALUE else 3
            binding.tvDescToggle.text = getString(
                if (isDescExpanded) R.string.desc_collapse else R.string.desc_expand_more
            )
        }

        binding.btnSelectAll.setOnClickListener {
            allSelected = !allSelected
            episodeAdapter.selectAll(allSelected)
            binding.btnSelectAll.text = getString(
                if (allSelected) R.string.btn_deselect_all else R.string.btn_select_all
            )
        }

        binding.btnDownloadSelected.setOnClickListener {
            enqueueSelectedDownloads()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.layoutBrowser.visibility == View.VISIBLE && binding.webviewShortcut.canGoBack() -> {
                        binding.webviewShortcut.goBack()
                    }
                    binding.layoutBrowser.visibility == View.VISIBLE -> {
                        closeBrowser()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun triggerAnalyzeFromInput() {
        val raw = binding.etUrl.text?.toString()?.trim() ?: ""
        val url = extractFirstUrlOrTrimmed(raw)
        if (url.isEmpty()) {
            binding.etUrl.error = getString(R.string.error_empty_url)
            return
        }
        if (url != raw) {
            binding.etUrl.setText(url)
        }
        hideKeyboard()
        startAnalyze(url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
    }

    private fun startAnalyze(url: String, preloadedHtml: String? = null) {
        viewModel.lastUrl = url
        analyzeJob?.cancel()
        binding.btnAnalyze.isEnabled = false
        binding.progressAnalyze.visibility = View.VISIBLE

        analyzeJob = lifecycleScope.launch {
            try {
                var info = withContext(Dispatchers.IO) {
                    ExtractorRouter.analyzeUrl(httpClient, url, preloadedHtml)
                }

                if (info.siteType == "youtube" && YouTubeExtractor.needsWebViewRescue(url)) {
                    val rescued = WebViewStreamResolver.resolveYouTubeStreamsViaWebView(this@MainActivity, url, httpClient)
                    if (isFinishing || isDestroyed) return@launch
                    if (rescued.isNotEmpty()) {
                        val labels = rescued.map { it.label }.distinct().toMutableList()
                        info = info.copy(availableQualities = labels)
                        val ytId = YouTubeExtractor.extractVideoId(url)
                        val ep = info.studios.firstOrNull()?.players?.firstOrNull()?.episodes?.firstOrNull()
                        if (ep != null && ep.id.startsWith("yt_fallback_") && ytId != null) {
                            ep.id = "yt_$ytId"
                        }
                    }
                }

                val allEpsBefore = info.studios.flatMap { it.players }.flatMap { it.episodes }
                val isYoutubeResolved = info.siteType == "youtube" &&
                    allEpsBefore.firstOrNull()?.id?.startsWith("yt_fallback_") != true
                val needsWebViewFallback = preloadedHtml == null && !isYoutubeResolved && (
                    allEpsBefore.size <= 1 && (
                        info.title == "Онлайн Відео" ||
                            allEpsBefore.firstOrNull()?.iframeUrl == info.url ||
                            allEpsBefore.firstOrNull()?.iframeUrl.isNullOrEmpty()
                        )
                    )

                if (needsWebViewFallback) {
                    val rendered = WebViewStreamResolver.fetchRenderedHtml(this@MainActivity, url)
                    if (isFinishing || isDestroyed) return@launch

                    val reanalyzed = if (rendered.html.isNotBlank() && !rendered.isChallengePage) {
                        withContext(Dispatchers.IO) {
                            ExtractorRouter.analyzeUrl(httpClient, rendered.finalUrl.ifBlank { url }, rendered.html)
                        }
                    } else {
                        null
                    }

                    val reanalyzedEpsCount = reanalyzed?.studios?.flatMap { it.players }?.sumOf { it.episodes.size } ?: 0
                    if (reanalyzed != null && (reanalyzedEpsCount > 1 || reanalyzed.title != "Онлайн Відео")) {
                        info = reanalyzed
                    } else if (rendered.interceptedMediaUrls.isNotEmpty()) {
                        val fallbackEp = info.studios.firstOrNull()?.players?.firstOrNull()?.episodes?.firstOrNull()
                        if (fallbackEp != null && !ExtractorUtils.isDirectMediaUrl(fallbackEp.iframeUrl)) {
                            fallbackEp.iframeUrl = rendered.interceptedMediaUrls.first()
                        }
                    } else if (rendered.isChallengePage && allEpsBefore.size <= 1) {
                        binding.btnAnalyze.isEnabled = true
                        binding.progressAnalyze.visibility = View.GONE
                        openBrowserUrl(url, info.title.takeIf { it != "Онлайн Відео" } ?: url)
                        Toast.makeText(
                            this@MainActivity,
                            "Сайт потребує перевірки у браузері. Зачекайте завантаження сторінки та натисніть кнопку скачування внизу.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                } else if (preloadedHtml != null && interceptedBrowserMediaUrls.isNotEmpty()) {
                    val singleEp = allEpsBefore.singleOrNull()
                    if (singleEp != null && singleEp.iframeUrl == info.url) {
                        singleEp.iframeUrl = interceptedBrowserMediaUrls.first()
                    }
                }

                binding.btnAnalyze.isEnabled = true
                binding.progressAnalyze.visibility = View.GONE
                displayMediaInfo(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.btnAnalyze.isEnabled = true
                binding.progressAnalyze.visibility = View.GONE
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_analysis_failed, e.message ?: "невідома помилка"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun displayMediaInfo(info: MediaInfo, preserveSelection: Boolean = false) {
        val savedStudioId = if (preserveSelection) viewModel.currentStudioId else null
        currentMediaInfo = info
        binding.containerEmptyState.visibility = View.GONE
        binding.containerMediaDetail.visibility = View.VISIBLE

        binding.tvMediaTitle.text = info.title
        binding.tvMediaOrigTitle.text = info.originalTitle ?: ""
        binding.tvMediaOrigTitle.visibility = if (info.originalTitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.tvBadgeSite.text = info.siteType.uppercase()

        val descText = info.description?.takeIf { it.isNotBlank() } ?: "Немає опису"
        if (!preserveSelection) {
            isDescExpanded = false
        }
        binding.tvMediaDesc.maxLines = if (isDescExpanded) Int.MAX_VALUE else 3
        binding.tvMediaDesc.text = descText
        binding.tvMediaDesc.post {
            val isEllipsized = (binding.tvMediaDesc.layout?.lineCount ?: 0) > 3 || descText.length > 140
            binding.tvDescToggle.visibility = if (isEllipsized) View.VISIBLE else View.GONE
            binding.tvDescToggle.text = getString(
                if (isDescExpanded) R.string.desc_collapse else R.string.desc_expand_more
            )
        }

        if (!info.rating.isNullOrEmpty()) {
            binding.tvBadgeRating.visibility = View.VISIBLE
            binding.tvBadgeRating.text = info.rating
        } else {
            binding.tvBadgeRating.visibility = View.GONE
        }

        if (!info.posterUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(info.posterUrl)
                .placeholder(R.drawable.ic_movie_placeholder)
                .error(R.drawable.ic_movie_placeholder)
                .into(binding.ivPoster)
            Glide.with(this)
                .load(info.posterUrl)
                .into(binding.ivBackdrop)
        } else {
            Glide.with(this).clear(binding.ivPoster)
            Glide.with(this).clear(binding.ivBackdrop)
            binding.ivPoster.setImageResource(R.drawable.ic_movie_placeholder)
            binding.ivBackdrop.setImageDrawable(null)
        }

        if (info.availableQualities.isNotEmpty()) {
            setupSpinnerAdapter(binding.spinnerQuality, info.availableQualities)
        }

        val studios = info.studios
        binding.containerStudiosSection.visibility = if (studios.size > 1) View.VISIBLE else View.GONE

        binding.chipGroupStudios.removeAllViews()
        if (studios.isNotEmpty()) {
            val targetIdx = studios.indexOfFirst { it.id == savedStudioId }.takeIf { it >= 0 } ?: 0
            for (i in studios.indices) {
                val studio = studios[i]
                val chip = layoutInflater.inflate(R.layout.item_chip_studio, binding.chipGroupStudios, false) as Chip
                chip.text = studio.name
                chip.id = View.generateViewId()
                chip.setOnClickListener { selectStudio(studio) }
                binding.chipGroupStudios.addView(chip)
                if (i == targetIdx) {
                    chip.isChecked = true
                    selectStudio(studio)
                }
            }
        } else {
            val directStudio = DubStudio(
                id = "direct",
                name = "Оригінальне відео",
                players = mutableListOf(
                    PlayerSource("direct", "Плеєр", mutableListOf(EpisodeItem("ep_1", "1", info.title, "1", info.url)))
                )
            )
            selectStudio(directStudio)
        }
    }

    private fun selectStudio(studio: DubStudio) {
        currentStudio = studio
        val players = studio.players

        if (players.isNotEmpty()) {
            if (players.size > 1) {
                binding.containerPlayersBar.visibility = View.VISIBLE
                binding.chipGroupPlayers.removeAllViews()
                for (i in players.indices) {
                    val player = players[i]
                    val pChip = layoutInflater.inflate(R.layout.item_chip_studio, binding.chipGroupPlayers, false) as Chip
                    pChip.text = player.name
                    pChip.id = View.generateViewId()
                    pChip.setOnClickListener { selectPlayer(player) }
                    binding.chipGroupPlayers.addView(pChip)
                    if (i == 0) {
                        pChip.isChecked = true
                        selectPlayer(player)
                    }
                }
            } else {
                binding.containerPlayersBar.visibility = View.GONE
                selectPlayer(players[0])
            }
        } else {
            binding.containerPlayersBar.visibility = View.GONE
            currentPlayer = null
            episodeAdapter.setEpisodes(emptyList())
            updateBottomBarVisibility()
        }
    }

    private fun selectPlayer(player: PlayerSource) {
        currentPlayer = player
        val episodes = player.episodes
        val isSeries = (currentMediaInfo?.isSeries == true) || episodes.size > 1

        if (isSeries) {
            binding.containerEpisodesSection.visibility = View.VISIBLE
            binding.containerSingleVideoActions.visibility = View.GONE
            binding.tvBadgeEpCount.text = "${episodes.size} серій"
            binding.tvEpisodesHeader.text = "СПИСОК СЕРІЙ (${episodes.size})"
            episodeAdapter.setEpisodes(episodes)

            allSelected = false
            binding.btnSelectAll.text = getString(R.string.btn_select_all)
            updateBottomBarVisibility()
        } else {
            binding.containerEpisodesSection.visibility = View.GONE
            binding.containerSingleVideoActions.visibility = View.VISIBLE
            binding.tvBadgeEpCount.text = "Відео"

            val singleEp = episodes.firstOrNull() ?: EpisodeItem(
                id = "single_ep",
                number = "1",
                title = currentMediaInfo?.title ?: "Відео",
                season = "1",
                iframeUrl = currentMediaInfo?.url ?: ""
            )

            binding.btnSinglePreview.setOnClickListener { onPlayPreview(singleEp) }
            binding.btnSingleDownload.setOnClickListener { onSingleDownload(singleEp) }

            binding.tvSelectedCount.text = "Відео готове"
            updateBottomBarVisibility()
        }
    }

    private fun collectCandidatePlayerUrls(episode: EpisodeItem): List<String> {
        val candidateUrls = mutableListOf<String>()
        if (episode.iframeUrl.isNotBlank()) {
            candidateUrls.add(episode.iframeUrl)
        }
        val extraPlayers = currentStudio?.players.orEmpty()
        for (ps in extraPlayers) {
            if (ps.id == currentPlayer?.id) continue
            val matching = ps.episodes.firstOrNull { it.season == episode.season && it.number == episode.number }
            if (matching != null && matching.iframeUrl.isNotBlank() && !candidateUrls.contains(matching.iframeUrl)) {
                candidateUrls.add(matching.iframeUrl)
            }
        }
        return candidateUrls
    }

    override fun onPlayPreview(episode: EpisodeItem) {
        val title = if (currentMediaInfo?.isSeries == true) {
            "${currentMediaInfo?.title ?: ""} - Серія ${episode.number}"
        } else {
            currentMediaInfo?.title ?: "Відео"
        }
        val candidates = collectCandidatePlayerUrls(episode)
        val fallbackUrls = candidates.filter { it != episode.iframeUrl }
        val selectedQuality = binding.spinnerQuality.selectedItem?.toString() ?: "720p"
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, episode.iframeUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_REFERER, currentMediaInfo?.url ?: episode.iframeUrl)
            putExtra(PlayerActivity.EXTRA_QUALITY, selectedQuality)
            if (fallbackUrls.isNotEmpty()) {
                putStringArrayListExtra(PlayerActivity.EXTRA_FALLBACK_URLS, ArrayList(fallbackUrls))
            }
        }
        startActivity(intent)
    }

    override fun onSelectionChanged(selectedCount: Int) {
        binding.tvSelectedCount.text = "Вибрано: $selectedCount серій"
        updateBottomBarVisibility()
    }

    private fun isCurrentMediaSingle(): Boolean {
        val episodesCount = currentPlayer?.episodes?.size
            ?: currentMediaInfo?.studios?.firstOrNull()?.players?.firstOrNull()?.episodes?.size
            ?: 0
        return currentMediaInfo?.isSeries == false && episodesCount <= 1
    }

    private fun updateBottomBarVisibility() {
        val isSingle = isCurrentMediaSingle()
        val hasSelection = isSingle || episodeAdapter.getSelectedEpisodes().isNotEmpty()
        binding.cardBottomDownloadBar.visibility =
            if (hasSelection && binding.layoutBrowser.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun updateActiveDownloadsBadge() {
        val active = queueManager.getActiveCount()
        if (active > 0) {
            binding.tvActiveDownloadsBadge.visibility = View.VISIBLE
            binding.tvActiveDownloadsBadge.text = if (active > 9) "9+" else active.toString()
        } else {
            binding.tvActiveDownloadsBadge.visibility = View.GONE
        }
    }

    override fun onSingleDownload(episode: EpisodeItem) {
        val quality = binding.spinnerQuality.selectedItem?.toString() ?: "1080p"
        val format = binding.spinnerFormat.selectedItem?.toString() ?: "mp4"
        val studioName = currentStudio?.name ?: "Оригінал"
        val mediaTitle = currentMediaInfo?.title ?: "Відео"
        val isSingle = isCurrentMediaSingle()
        val animeFolder = resolveFolderName(isSingle, mediaTitle, studioName)

        lifecycleScope.launch {
            try {
                val task = withContext(Dispatchers.IO) {
                    buildDownloadTask(episode, mediaTitle, studioName, isSingle, quality, format, animeFolder)
                }
                startDownloadService(task)

                val msg = if (isSingle) "Додано до черги: $mediaTitle" else "Додано до черги: Серія ${episode.number}"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Помилка завантаження: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueSelectedDownloads() {
        if (isCurrentMediaSingle()) {
            val singleEp = currentPlayer?.episodes?.firstOrNull()
                ?: currentMediaInfo?.studios?.firstOrNull()?.players?.firstOrNull()?.episodes?.firstOrNull()
            if (singleEp != null) {
                val quality = binding.spinnerQuality.selectedItem?.toString() ?: "1080p"
                val format = binding.spinnerFormat.selectedItem?.toString() ?: "mp4"
                val studioName = currentStudio?.name ?: "Оригінал"
                val mediaTitle = currentMediaInfo?.title ?: "Відео"
                val animeFolder = resolveFolderName(true, mediaTitle, studioName)
                lifecycleScope.launch {
                    try {
                        val task = withContext(Dispatchers.IO) {
                            buildDownloadTask(singleEp, mediaTitle, studioName, true, quality, format, animeFolder)
                        }
                        startDownloadService(task)
                        Toast.makeText(this@MainActivity, "Додано до черги: $mediaTitle", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Помилка завантаження: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
        }

        val selected = episodeAdapter.getSelectedEpisodes()
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_episodes_selected), Toast.LENGTH_SHORT).show()
            return
        }

        val quality = binding.spinnerQuality.selectedItem?.toString() ?: "1080p"
        val format = binding.spinnerFormat.selectedItem?.toString() ?: "mp4"
        val studioName = currentStudio?.name ?: "Оригінал"
        val mediaTitle = currentMediaInfo?.title ?: "Відео"
        val animeFolder = resolveFolderName(false, mediaTitle, studioName)

        lifecycleScope.launch {
            var enqueuedCount = 0
            withContext(Dispatchers.IO) {
                for (ep in selected) {
                    try {
                        val task = buildDownloadTask(ep, mediaTitle, studioName, false, quality, format, animeFolder)
                        startDownloadService(task)
                        enqueuedCount++
                    } catch (_: Exception) {}
                }
            }

            Toast.makeText(this@MainActivity, "Додано до черги: $enqueuedCount серій", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
        }
    }

    private fun resolveFolderName(isSingle: Boolean, mediaTitle: String, studioName: String): String {
        return when {
            isSingle -> "Downloads"
            studioName.isNotEmpty() && studioName != "Оригінал" -> "$mediaTitle [$studioName]"
            else -> mediaTitle
        }
    }

    private suspend fun buildDownloadTask(
        episode: EpisodeItem,
        mediaTitle: String,
        studioName: String,
        isSingle: Boolean,
        quality: String,
        format: String,
        folderName: String
    ): DownloadTask {
        val pageReferer = currentMediaInfo?.url ?: episode.iframeUrl
        val rawCandidates = collectCandidatePlayerUrls(episode)
        val desiredHeight = ExtractorUtils.extractQualityHeight(quality) ?: 1080
        val candidateUrls = if (currentMediaInfo?.siteType == "tiktok" && rawCandidates.size > 1 && desiredHeight < 1080) {
            listOf(rawCandidates[1]) + rawCandidates.filterIndexed { index, _ -> index != 1 }
        } else {
            rawCandidates
        }

        var chosenStream: ua.aniua.downloader.model.StreamQuality? = null
        var chosenTargetUrl = episode.iframeUrl

        for (targetUrl in candidateUrls) {
            var streams = GenericPlayerResolver.resolvePlayerStreams(httpClient, targetUrl, pageReferer)
            if (YouTubeExtractor.isYouTubeUrl(targetUrl)) {
                val maxResolvedHeight = streams.maxOfOrNull { it.height } ?: 0
                if (streams.isEmpty() || (desiredHeight > 360 && maxResolvedHeight <= 360 && YouTubeExtractor.needsWebViewRescue(targetUrl))) {
                    val rescued = WebViewStreamResolver.resolveYouTubeStreamsViaWebView(this@MainActivity, targetUrl, httpClient)
                    if (rescued.isNotEmpty()) {
                        streams = rescued
                    }
                }
            } else if (streams.isEmpty() && !ExtractorUtils.isDirectMediaUrl(targetUrl)) {
                streams = WebViewStreamResolver.resolveStreamViaWebView(this@MainActivity, targetUrl, pageReferer)
            }
            if (streams.isNotEmpty()) {
                chosenStream = ExtractorUtils.selectBestStream(streams, quality)
                chosenTargetUrl = targetUrl
                break
            }
        }

        val streamUrl = chosenStream?.url ?: chosenTargetUrl
        val referer = chosenStream?.headers?.get("Referer") ?: pageReferer
        val effectiveFormat = if (format.equals("mp4", ignoreCase = true) &&
            chosenStream != null &&
            !chosenStream.isHls &&
            ExtractorUtils.isWebmStreamUrl(streamUrl)
        ) {
            "webm"
        } else {
            format
        }

        return DownloadTask(
            id = UUID.randomUUID().toString(),
            mediaTitle = mediaTitle,
            studioName = studioName,
            episodeNumber = if (isSingle) "1" else episode.number,
            episodeTitle = episode.title,
            iframeUrl = chosenTargetUrl,
            streamUrl = streamUrl,
            referer = referer,
            quality = quality,
            format = effectiveFormat,
            folderName = folderName,
            audioUrl = chosenStream?.audioUrl,
            isSingle = isSingle,
            headers = chosenStream?.headers?.toMutableMap() ?: mutableMapOf()
        )
    }

    private fun startDownloadService(task: DownloadTask) {
        ensureNotificationPermission()
        val svcIntent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_TASK, task)
        }
        ContextCompat.startForegroundService(this, svcIntent)
    }

    override fun onResume() {
        super.onResume()
        binding.webviewShortcut.onResume()
        updateActiveDownloadsBadge()
    }

    override fun onPause() {
        binding.webviewShortcut.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        analyzeJob?.cancel()
        queueManager.removeListener(downloadBadgeListener)
        val wv = binding.webviewShortcut
        wv.stopLoading()
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
        super.onDestroy()
    }
}
