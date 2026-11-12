package ua.aniua.downloader.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import ua.aniua.downloader.R
import ua.aniua.downloader.extractor.ExtractorRouter
import ua.aniua.downloader.extractor.GenericPlayerResolver
import ua.aniua.downloader.model.DownloadTask
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import ua.aniua.downloader.service.DownloadService
import ua.aniua.downloader.ui.adapter.EpisodeAdapter
import ua.security.hardening.AppSecurityManager
import ua.security.hardening.ThreatLevel
import ua.security.network.NetworkSecurityManager
import java.util.UUID
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), EpisodeAdapter.OnEpisodeActionListener {

    private lateinit var tabLayout: TabLayout
    private lateinit var layoutCatalog: RelativeLayout
    private lateinit var scrollDownloader: NestedScrollView
    private lateinit var swipeRefreshCatalog: SwipeRefreshLayout
    private lateinit var progressWebLoader: ProgressBar
    private lateinit var webView: WebView
    private lateinit var btnWebBack: ImageButton
    private lateinit var btnWebForward: ImageButton
    private lateinit var btnWebHome: ImageButton
    private lateinit var btnWebRefresh: ImageButton
    private lateinit var btnWebDownloadCurrent: MaterialButton
    private lateinit var btnBackToCatalog: MaterialButton

    private lateinit var etUrl: TextInputEditText
    private lateinit var btnAnalyze: MaterialButton
    private lateinit var progressAnalyze: ProgressBar
    private lateinit var cardSecurityBanner: MaterialCardView
    private lateinit var tvSecurityBannerText: TextView

    private lateinit var containerMediaDetail: LinearLayout
    private lateinit var ivBackdrop: ImageView
    private lateinit var ivPoster: ImageView
    private lateinit var tvBadgeSite: TextView
    private lateinit var tvBadgeEpCount: TextView
    private lateinit var tvBadgeRating: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvOrigTitle: TextView
    private lateinit var tvDesc: TextView
    private lateinit var containerStudiosSection: View
    private lateinit var chipGroupStudios: ChipGroup
    private lateinit var containerPlayersBar: View
    private lateinit var chipGroupPlayers: ChipGroup
    private lateinit var containerSingleVideoActions: View
    private lateinit var btnSinglePreview: MaterialButton
    private lateinit var btnSingleDownload: MaterialButton
    private lateinit var containerEpisodesSection: View
    private lateinit var tvEpisodesHeader: TextView
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var rvEpisodes: RecyclerView

    private lateinit var cardBottomDownloadBar: MaterialCardView
    private lateinit var tvSelectedCount: TextView
    private lateinit var spinnerQuality: Spinner
    private lateinit var spinnerFormat: Spinner
    private lateinit var btnDownloadSelected: MaterialButton

    private lateinit var episodeAdapter: EpisodeAdapter
    private var currentMediaInfo: MediaInfo? = null
    private var currentStudio: DubStudio? = null
    private var currentPlayer: PlayerSource? = null
    private lateinit var httpClient: OkHttpClient
    private var allSelected = false

    private val anitubeAnimePattern = Pattern.compile("https?://(?:www\\.)?anitube\\.in\\.ua/(\\d+)-.*\\.html", Pattern.CASE_INSENSITIVE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        httpClient = NetworkSecurityManager.getInstance(this).hardenedHttpClient

        initViews()
        initWebView()
        performSecurityAudit()
        setupListeners()
    }

    private fun initViews() {
        tabLayout = findViewById(R.id.tab_layout)
        layoutCatalog = findViewById(R.id.layout_catalog)
        scrollDownloader = findViewById(R.id.scroll_downloader)
        swipeRefreshCatalog = findViewById(R.id.swipe_refresh_catalog)
        progressWebLoader = findViewById(R.id.progress_web_loader)
        webView = findViewById(R.id.webview_anitube)
        btnWebBack = findViewById(R.id.btn_web_back)
        btnWebForward = findViewById(R.id.btn_web_forward)
        btnWebHome = findViewById(R.id.btn_web_home)
        btnWebRefresh = findViewById(R.id.btn_web_refresh)
        btnWebDownloadCurrent = findViewById(R.id.btn_web_download_current)
        btnBackToCatalog = findViewById(R.id.btn_back_to_catalog)

        etUrl = findViewById(R.id.et_url)
        btnAnalyze = findViewById(R.id.btn_analyze)
        progressAnalyze = findViewById(R.id.progress_analyze)
        cardSecurityBanner = findViewById(R.id.card_security_banner)
        tvSecurityBannerText = findViewById(R.id.tv_security_banner_text)

        containerMediaDetail = findViewById(R.id.container_media_detail)
        ivBackdrop = findViewById(R.id.iv_backdrop)
        ivPoster = findViewById(R.id.iv_poster)
        tvBadgeSite = findViewById(R.id.tv_badge_site)
        tvBadgeEpCount = findViewById(R.id.tv_badge_ep_count)
        tvBadgeRating = findViewById(R.id.tv_badge_rating)
        tvTitle = findViewById(R.id.tv_media_title)
        tvOrigTitle = findViewById(R.id.tv_media_orig_title)
        tvDesc = findViewById(R.id.tv_media_desc)

        containerStudiosSection = findViewById(R.id.container_studios_section)
        chipGroupStudios = findViewById(R.id.chip_group_studios)
        containerPlayersBar = findViewById(R.id.container_players_bar)
        chipGroupPlayers = findViewById(R.id.chip_group_players)
        containerSingleVideoActions = findViewById(R.id.container_single_video_actions)
        btnSinglePreview = findViewById(R.id.btn_single_preview)
        btnSingleDownload = findViewById(R.id.btn_single_download)
        containerEpisodesSection = findViewById(R.id.container_episodes_section)
        tvEpisodesHeader = findViewById(R.id.tv_episodes_header)
        btnSelectAll = findViewById(R.id.btn_select_all)
        rvEpisodes = findViewById(R.id.rv_episodes)

        cardBottomDownloadBar = findViewById(R.id.card_bottom_download_bar)
        tvSelectedCount = findViewById(R.id.tv_selected_count)
        spinnerQuality = findViewById(R.id.spinner_quality)
        spinnerFormat = findViewById(R.id.spinner_format)
        btnDownloadSelected = findViewById(R.id.btn_download_selected)

        findViewById<View>(R.id.btn_downloads_header).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        rvEpisodes.layoutManager = LinearLayoutManager(this)
        episodeAdapter = EpisodeAdapter(this)
        rvEpisodes.adapter = episodeAdapter

        val formatAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("mp4", "mkv"))
        spinnerFormat.adapter = formatAdapter

        val qualityAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("1080p", "720p", "480p", "360p"))
        spinnerQuality.adapter = qualityAdapter
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.aniyomi_bg))
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressWebLoader.visibility = View.VISIBLE
                    progressWebLoader.progress = newProgress
                } else {
                    progressWebLoader.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (isAnitubeAnimePage(url)) {
                    openAnimeDownloadMenu(url)
                    return true
                }
                return false
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && isAnitubeAnimePage(url)) {
                    openAnimeDownloadMenu(url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                swipeRefreshCatalog.isRefreshing = true
                progressWebLoader.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefreshCatalog.isRefreshing = false
                progressWebLoader.visibility = View.GONE

                val isAnime = url != null && isAnitubeAnimePage(url)
                btnWebDownloadCurrent.visibility = if (isAnime) View.VISIBLE else View.GONE
            }
        }

        swipeRefreshCatalog.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent_cyan))
        swipeRefreshCatalog.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.aniyomi_surface_elevated))
        swipeRefreshCatalog.setOnRefreshListener {
            webView.reload()
        }

        btnWebBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        btnWebForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        btnWebHome.setOnClickListener {
            webView.loadUrl("https://anitube.in.ua/")
        }
        btnWebRefresh.setOnClickListener {
            webView.reload()
        }
        btnWebDownloadCurrent.setOnClickListener {
            val currentUrl = webView.url
            if (currentUrl != null && isAnitubeAnimePage(currentUrl)) {
                openAnimeDownloadMenu(currentUrl)
            }
        }

        webView.loadUrl("https://anitube.in.ua/")
    }

    private fun isAnitubeAnimePage(url: String): Boolean {
        return anitubeAnimePattern.matcher(url.trim()).find()
    }

    private fun openAnimeDownloadMenu(url: String) {
        tabLayout.getTabAt(1)?.select()
        etUrl.setText(url)
        startAnalyze(url)
    }

    private fun performSecurityAudit() {
        val secMgr = AppSecurityManager.getInstance(this)
        val audit = secMgr.performAudit()
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        if (audit.overallThreatLevel == ThreatLevel.ENVIRONMENT_RISK) {
            cardSecurityBanner.visibility = View.VISIBLE
            tvSecurityBannerText.text = getString(R.string.security_warning_msg)
        } else if (audit.overallThreatLevel == ThreatLevel.CRITICAL_ATTACK) {
            if (isDebug) {
                cardSecurityBanner.visibility = View.VISIBLE
                tvSecurityBannerText.text = "Режим розробника (Debug): перевірки безпеки адаптовано"
                return
            }
            Toast.makeText(this, getString(R.string.security_critical_msg), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupListeners() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        layoutCatalog.visibility = View.VISIBLE
                        scrollDownloader.visibility = View.GONE
                        cardBottomDownloadBar.visibility = View.GONE
                    }
                    1 -> {
                        layoutCatalog.visibility = View.GONE
                        scrollDownloader.visibility = View.VISIBLE
                        updateBottomBarVisibility()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        btnBackToCatalog.setOnClickListener {
            tabLayout.getTabAt(0)?.select()
        }

        btnAnalyze.setOnClickListener {
            val url = etUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) {
                etUrl.error = getString(R.string.error_empty_url)
                return@setOnClickListener
            }
            startAnalyze(url)
        }

        btnSelectAll.setOnClickListener {
            allSelected = !allSelected
            episodeAdapter.selectAll(allSelected)
            btnSelectAll.text = if (allSelected) "Зняти вибір" else "Вибрати всі"
        }

        btnDownloadSelected.setOnClickListener {
            enqueueSelectedDownloads()
        }
    }

    private fun startAnalyze(url: String) {
        btnAnalyze.isEnabled = false
        progressAnalyze.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    ExtractorRouter.analyzeUrl(httpClient, url)
                }
                btnAnalyze.isEnabled = true
                progressAnalyze.visibility = View.GONE
                displayMediaInfo(info)
            } catch (e: Exception) {
                btnAnalyze.isEnabled = true
                progressAnalyze.visibility = View.GONE
                Toast.makeText(this@MainActivity, getString(R.string.error_analysis_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayMediaInfo(info: MediaInfo) {
        currentMediaInfo = info
        containerMediaDetail.visibility = View.VISIBLE

        tvTitle.text = info.title
        tvOrigTitle.text = info.originalTitle ?: ""
        tvOrigTitle.visibility = if (info.originalTitle != null) View.VISIBLE else View.GONE
        tvBadgeSite.text = info.siteType.uppercase()
        tvDesc.text = info.description ?: "Немає опису"

        // Рейтинг аніме
        if (!info.rating.isNullOrEmpty()) {
            tvBadgeRating.visibility = View.VISIBLE
            tvBadgeRating.text = info.rating
        } else {
            tvBadgeRating.visibility = View.GONE
        }

        if (!info.posterUrl.isNullOrEmpty()) {
            Glide.with(this).load(info.posterUrl).into(ivPoster)
            Glide.with(this).load(info.posterUrl).into(ivBackdrop)
        }

        if (info.availableQualities.isNotEmpty()) {
            val qAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, info.availableQualities)
            spinnerQuality.adapter = qAdapter
        }

        val isSeries = info.isSeries
        containerStudiosSection.visibility = if (isSeries && info.studios.size > 1) View.VISIBLE else View.GONE
        containerEpisodesSection.visibility = if (isSeries) View.VISIBLE else View.GONE
        containerSingleVideoActions.visibility = if (isSeries) View.GONE else View.VISIBLE

        chipGroupStudios.removeAllViews()
        val studios = info.studios

        if (studios.isNotEmpty()) {
            for (i in studios.indices) {
                val studio = studios[i]
                val chip = layoutInflater.inflate(R.layout.item_chip_studio, chipGroupStudios, false) as Chip
                chip.text = studio.name
                chip.id = View.generateViewId()

                chip.setOnClickListener { selectStudio(studio) }
                chipGroupStudios.addView(chip)
                if (i == 0) {
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

        val isSeries = currentMediaInfo?.isSeries == true
        if (players.isNotEmpty()) {
            if (players.size > 1 && isSeries) {
                containerPlayersBar.visibility = View.VISIBLE
                chipGroupPlayers.removeAllViews()
                for (i in players.indices) {
                    val player = players[i]
                    val pChip = layoutInflater.inflate(R.layout.item_chip_studio, chipGroupPlayers, false) as Chip
                    pChip.text = player.name
                    pChip.id = View.generateViewId()

                    pChip.setOnClickListener { selectPlayer(player) }
                    chipGroupPlayers.addView(pChip)
                    if (i == 0) {
                        pChip.isChecked = true
                        selectPlayer(player)
                    }
                }
            } else {
                containerPlayersBar.visibility = View.GONE
                selectPlayer(players[0])
            }
        }
    }

    private fun selectPlayer(player: PlayerSource) {
        currentPlayer = player
        val episodes = player.episodes
        val isSeries = currentMediaInfo?.isSeries ?: (episodes.size > 1)

        if (isSeries) {
            containerEpisodesSection.visibility = View.VISIBLE
            containerSingleVideoActions.visibility = View.GONE
            tvBadgeEpCount.text = "${episodes.size} серій"
            tvEpisodesHeader.text = "СПИСОК СЕРІЙ (${episodes.size})"
            episodeAdapter.setEpisodes(episodes)

            allSelected = false
            btnSelectAll.text = "Вибрати всі"
            cardBottomDownloadBar.visibility = View.GONE
        } else {
            containerEpisodesSection.visibility = View.GONE
            containerSingleVideoActions.visibility = View.VISIBLE
            containerStudiosSection.visibility = View.GONE
            containerPlayersBar.visibility = View.GONE
            tvBadgeEpCount.text = "Відео"

            val singleEp = episodes.firstOrNull() ?: EpisodeItem(
                id = "single_ep",
                number = "1",
                title = currentMediaInfo?.title ?: "Відео",
                season = "1",
                iframeUrl = currentMediaInfo?.url ?: ""
            )

            btnSinglePreview.setOnClickListener {
                onPlayPreview(singleEp)
            }
            btnSingleDownload.setOnClickListener {
                onSingleDownload(singleEp)
            }

            tvSelectedCount.text = "Відео готове"
            cardBottomDownloadBar.visibility = if (tabLayout.selectedTabPosition == 1) View.VISIBLE else View.GONE
        }
    }

    override fun onPlayPreview(episode: EpisodeItem) {
        val title = if (currentMediaInfo?.isSeries == true) {
            "${currentMediaInfo?.title ?: ""} - Серія ${episode.number}"
        } else {
            currentMediaInfo?.title ?: "Відео"
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, episode.iframeUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_REFERER, episode.iframeUrl)
        }
        startActivity(intent)
    }

    override fun onSelectionChanged(selectedCount: Int) {
        tvSelectedCount.text = "Вибрано: $selectedCount серій"
        updateBottomBarVisibility()
    }

    private fun updateBottomBarVisibility() {
        val isSingle = currentMediaInfo?.isSeries == false
        val hasSelection = isSingle || episodeAdapter.getSelectedEpisodes().isNotEmpty()
        cardBottomDownloadBar.visibility = if (hasSelection && tabLayout.selectedTabPosition == 1) View.VISIBLE else View.GONE
    }

    override fun onSingleDownload(episode: EpisodeItem) {
        val quality = spinnerQuality.selectedItem?.toString() ?: "1080p"
        val format = spinnerFormat.selectedItem?.toString() ?: "mp4"
        val studioName = currentStudio?.name ?: "Оригінал"
        val mediaTitle = currentMediaInfo?.title ?: "Відео"

        val isSingle = currentMediaInfo?.isSeries == false
        val animeFolder = if (isSingle) {
            "Downloads"
        } else if (studioName.isNotEmpty() && studioName != "Оригінал") {
            "$mediaTitle [$studioName]"
        } else {
            mediaTitle
        }

        lifecycleScope.launch {
            try {
                val streams = withContext(Dispatchers.IO) {
                    GenericPlayerResolver.resolvePlayerStreams(httpClient, episode.iframeUrl, episode.iframeUrl)
                }
                val streamUrl = if (streams.isNotEmpty()) streams[0].url else episode.iframeUrl

                val task = DownloadTask(
                    id = UUID.randomUUID().toString(),
                    mediaTitle = mediaTitle,
                    studioName = studioName,
                    episodeNumber = if (isSingle) "1" else episode.number,
                    episodeTitle = episode.title,
                    iframeUrl = episode.iframeUrl,
                    streamUrl = streamUrl,
                    referer = episode.iframeUrl,
                    quality = quality,
                    format = format,
                    folderName = animeFolder
                )

                val svcIntent = Intent(this@MainActivity, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_START_DOWNLOAD
                    putExtra(DownloadService.EXTRA_TASK, task)
                }
                ContextCompat.startForegroundService(this@MainActivity, svcIntent)

                val msg = if (isSingle) "Додано до черги: $mediaTitle" else "Додано до черги: Серія ${episode.number}"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Помилка завантаження: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueSelectedDownloads() {
        if (currentMediaInfo?.isSeries == false) {
            val singleEp = currentPlayer?.episodes?.firstOrNull() ?: currentMediaInfo?.studios?.firstOrNull()?.players?.firstOrNull()?.episodes?.firstOrNull()
            if (singleEp != null) {
                onSingleDownload(singleEp)
                startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
                return
            }
        }

        val selected = episodeAdapter.getSelectedEpisodes()
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_episodes_selected), Toast.LENGTH_SHORT).show()
            return
        }

        val quality = spinnerQuality.selectedItem?.toString() ?: "1080p"
        val format = spinnerFormat.selectedItem?.toString() ?: "mp4"
        val studioName = currentStudio?.name ?: "Оригінал"
        val mediaTitle = currentMediaInfo?.title ?: "Відео"

        val animeFolder = if (studioName.isNotEmpty() && studioName != "Оригінал") "$mediaTitle [$studioName]" else mediaTitle

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                for (ep in selected) {
                    try {
                        val streams = GenericPlayerResolver.resolvePlayerStreams(httpClient, ep.iframeUrl, ep.iframeUrl)
                        val streamUrl = if (streams.isNotEmpty()) streams[0].url else ep.iframeUrl

                        val task = DownloadTask(
                            id = UUID.randomUUID().toString(),
                            mediaTitle = mediaTitle,
                            studioName = studioName,
                            episodeNumber = ep.number,
                            episodeTitle = ep.title,
                            iframeUrl = ep.iframeUrl,
                            streamUrl = streamUrl,
                            referer = ep.iframeUrl,
                            quality = quality,
                            format = format,
                            folderName = animeFolder
                        )

                        val svcIntent = Intent(this@MainActivity, DownloadService::class.java).apply {
                            action = DownloadService.ACTION_START_DOWNLOAD
                            putExtra(DownloadService.EXTRA_TASK, task)
                        }
                        ContextCompat.startForegroundService(this@MainActivity, svcIntent)
                    } catch (ignored: Exception) {}
                }
            }

            Toast.makeText(this@MainActivity, "Додано до черги: ${selected.size} серій", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
        }
    }

    override fun onBackPressed() {
        if (tabLayout.selectedTabPosition == 1) {
            tabLayout.getTabAt(0)?.select()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
