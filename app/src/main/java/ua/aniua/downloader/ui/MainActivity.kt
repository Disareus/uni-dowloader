package ua.aniua.downloader.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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

class MainActivity : AppCompatActivity(), EpisodeAdapter.OnEpisodeActionListener {

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
    private lateinit var tvTitle: TextView
    private lateinit var tvOrigTitle: TextView
    private lateinit var tvDesc: TextView
    private lateinit var chipGroupStudios: ChipGroup
    private lateinit var containerPlayersBar: View
    private lateinit var chipGroupPlayers: ChipGroup
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        httpClient = NetworkSecurityManager.getInstance(this).hardenedHttpClient

        initViews()
        performSecurityAudit()
        setupListeners()
    }

    private fun initViews() {
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
        tvTitle = findViewById(R.id.tv_media_title)
        tvOrigTitle = findViewById(R.id.tv_media_orig_title)
        tvDesc = findViewById(R.id.tv_media_desc)

        chipGroupStudios = findViewById(R.id.chip_group_studios)
        containerPlayersBar = findViewById(R.id.container_players_bar)
        chipGroupPlayers = findViewById(R.id.chip_group_players)
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

        if (!info.posterUrl.isNullOrEmpty()) {
            Glide.with(this).load(info.posterUrl).into(ivPoster)
            Glide.with(this).load(info.posterUrl).into(ivBackdrop)
        }

        if (info.availableQualities.isNotEmpty()) {
            val qAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, info.availableQualities)
            spinnerQuality.adapter = qAdapter
        }

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

        if (players.isNotEmpty()) {
            if (players.size > 1) {
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
        episodeAdapter.setEpisodes(episodes)

        val epText = "${episodes.size} " + (if (episodes.size == 1) "серія" else "серій")
        tvBadgeEpCount.text = epText
        tvEpisodesHeader.text = "Серії (${episodes.size})"
        allSelected = false
        btnSelectAll.text = "Вибрати всі"
    }

    override fun onSelectionChanged(selectedCount: Int) {
        if (selectedCount > 0) {
            cardBottomDownloadBar.visibility = View.VISIBLE
            val label = "Вибрано: $selectedCount " + (if (selectedCount == 1) "серія" else "серій")
            tvSelectedCount.text = label
        } else {
            cardBottomDownloadBar.visibility = View.GONE
        }
    }

    override fun onPlayPreview(episode: EpisodeItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, episode.iframeUrl)
            putExtra(PlayerActivity.EXTRA_REFERER, episode.iframeUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, episode.title)
        }
        startActivity(intent)
    }

    override fun onSingleDownload(episode: EpisodeItem) {
        val quality = spinnerQuality.selectedItem?.toString() ?: "1080p"
        val format = spinnerFormat.selectedItem?.toString() ?: "mp4"
        val studioName = currentStudio?.name ?: "Оригінал"
        val mediaTitle = currentMediaInfo?.title ?: "Відео"

        lifecycleScope.launch {
            try {
                val streamUrl = withContext(Dispatchers.IO) {
                    val streams = GenericPlayerResolver.resolvePlayerStreams(httpClient, episode.iframeUrl, episode.iframeUrl)
                    if (streams.isNotEmpty()) streams[0].url else episode.iframeUrl
                }

                val animeFolder = if (studioName.isNotEmpty() && studioName != "Оригінал") "$mediaTitle [$studioName]" else mediaTitle

                val task = DownloadTask(
                    id = UUID.randomUUID().toString(),
                    mediaTitle = mediaTitle,
                    studioName = studioName,
                    episodeNumber = episode.number,
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

                Toast.makeText(this@MainActivity, "Почалося завантаження: Серія ${episode.number}", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Помилка завантаження: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueSelectedDownloads() {
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
}
