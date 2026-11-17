package ua.aniua.downloader.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import ua.aniua.downloader.databinding.ActivityPlayerBinding
import ua.aniua.downloader.extractor.ExtractorUtils
import ua.aniua.downloader.extractor.GenericPlayerResolver
import ua.aniua.downloader.extractor.WebViewStreamResolver
import ua.aniua.downloader.extractor.YouTubeExtractor
import ua.aniua.downloader.model.StreamQuality
import ua.aniua.downloader.player.OkHttpDataSource
import ua.security.network.NetworkSecurityManager
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_FALLBACK_URLS = "extra_fallback_urls"
        const val EXTRA_QUALITY = "extra_quality"

        private val AD_REDIRECT_HINTS = listOf(
            "doubleclick", "googlesyndication", "popads", "popcash", "propellerads",
            "exoclick", "adsterra", "juicyads", "trafficjunky", "hilltopads",
            "bet365", "1xbet", "mostbet", "parimatch", "pin-up", "casino"
        )
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var httpClient: OkHttpClient
    private var player: ExoPlayer? = null

    private var candidateUrls: List<String> = emptyList()
    private var pageReferer: String? = null
    private var desiredQuality: String? = null

    private val playbackQueue = ArrayDeque<StreamQuality>()
    private val pendingResolveUrls = ArrayDeque<String>()
    private val attemptedStreamKeys = mutableSetOf<String>()
    private var triedYouTubeFallback = false
    private var lastErrorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        httpClient = NetworkSecurityManager.getInstance(this).hardenedHttpClient
        enableImmersiveMode()

        binding.btnPlayerBack.setOnClickListener { finish() }
        binding.tvPlayerTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: ""

        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.layoutPlayerTopBar.visibility = visibility
            }
        )

        val rawUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        pageReferer = intent.getStringExtra(EXTRA_REFERER)
        desiredQuality = intent.getStringExtra(EXTRA_QUALITY)
        val fallbackUrls = intent.getStringArrayListExtra(EXTRA_FALLBACK_URLS).orEmpty()
        candidateUrls = (listOfNotNull(rawUrl) + fallbackUrls)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (candidateUrls.isEmpty()) {
            Toast.makeText(this, "Посилання на відео відсутнє", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pendingResolveUrls.addAll(candidateUrls)
        binding.playerLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            resolveMoreAndStartPlayback()
        }
    }

    private suspend fun resolveMoreAndStartPlayback() {
        while (playbackQueue.isEmpty() && pendingResolveUrls.isNotEmpty()) {
            val candidate = pendingResolveUrls.removeFirst()
            val resolved = resolveStreamsForCandidate(candidate)
            for (stream in resolved) {
                enqueueIfUnattempted(stream)
            }
        }

        if (playbackQueue.isEmpty() && !triedYouTubeFallback) {
            val ytCandidate = candidateUrls.firstOrNull { YouTubeExtractor.isYouTubeUrl(it) }
            if (ytCandidate != null) {
                triedYouTubeFallback = true
                val fallbacks = withContext(Dispatchers.IO) {
                    YouTubeExtractor.invalidateCache(ytCandidate)
                    YouTubeExtractor.resolveFallbackStreams(httpClient, ytCandidate)
                }
                for (stream in orderStreamsForPlayback(fallbacks, desiredQuality)) {
                    enqueueIfUnattempted(stream)
                }
            }
        }

        if (isFinishing || isDestroyed) return

        val nextStream = playbackQueue.removeFirstOrNull()
        if (nextStream != null) {
            val effectiveReferer = nextStream.headers["Referer"] ?: pageReferer
            initPlayer(
                nextStream.url,
                effectiveReferer,
                nextStream.headers,
                nextStream.isHls,
                nextStream.audioUrl
            )
            return
        }

        // Якщо жоден прямий відеопотік не вдалося відтворити через ExoPlayer, пробуємо веб-плеєр (лише для embed-сторінок)
        val webCandidates = buildWebFallbackCandidates()
        if (webCandidates.isNotEmpty()) {
            initWebPlayerFallback(webCandidates, pageReferer)
        } else {
            binding.playerLoading.visibility = View.GONE
            val errorText = lastErrorMessage?.let { "Помилка відтворення: $it" }
                ?: "Не вдалося знайти робочий відеопотік у плеєрі"
            Toast.makeText(this@PlayerActivity, errorText, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private suspend fun resolveStreamsForCandidate(candidate: String): List<StreamQuality> {
        val isLocal = candidate.startsWith("content://", ignoreCase = true) ||
            candidate.startsWith("file://", ignoreCase = true)
        if (isLocal) {
            return listOf(StreamQuality("Local", 720, candidate, false))
        }

        if (ExtractorUtils.isDirectMediaUrl(candidate)) {
            return listOf(
                StreamQuality("Auto", 720, candidate, ExtractorUtils.isHlsUrl(candidate)).apply {
                    if (!pageReferer.isNullOrEmpty() && !candidate.contains("googlevideo.com", ignoreCase = true)) {
                        headers["Referer"] = pageReferer!!
                    }
                }
            )
        }

        val resolved = withContext(Dispatchers.IO) {
            GenericPlayerResolver.resolvePlayerStreams(httpClient, candidate, pageReferer)
        }
        val targetHeight = ExtractorUtils.extractQualityHeight(desiredQuality) ?: 1080
        val streams = if (YouTubeExtractor.isYouTubeUrl(candidate)) {
            val maxResolvedHeight = resolved.maxOfOrNull { it.height } ?: 0
            if (resolved.isEmpty() || (targetHeight > 360 && maxResolvedHeight <= 360 && YouTubeExtractor.needsWebViewRescue(candidate))) {
                WebViewStreamResolver.resolveYouTubeStreamsViaWebView(this@PlayerActivity, candidate, httpClient)
                    .ifEmpty { resolved }
            } else {
                resolved
            }
        } else {
            resolved.ifEmpty {
                WebViewStreamResolver.resolveStreamViaWebView(this@PlayerActivity, candidate, pageReferer)
            }
        }
        return orderStreamsForPlayback(streams, desiredQuality)
    }

    private fun orderStreamsForPlayback(streams: List<StreamQuality>, targetQuality: String?): List<StreamQuality> {
        if (streams.size <= 1) return streams
        val preferred = if (!targetQuality.isNullOrBlank()) {
            ExtractorUtils.selectBestStream(streams, targetQuality)
        } else {
            streams.first()
        }
        val targetHeight = ExtractorUtils.extractQualityHeight(targetQuality) ?: preferred.height
        val rest = streams.filter { it != preferred }.sortedWith(
            compareBy<StreamQuality> {
                val h = if (it.height > 0) it.height else 720
                kotlin.math.abs(h - targetHeight)
            }.thenBy {
                when {
                    it.isHls -> 0
                    it.audioUrl == null -> 1
                    else -> 2
                }
            }
        )
        return listOf(preferred) + rest
    }

    private fun enqueueIfUnattempted(stream: StreamQuality) {
        val key = "${stream.url}|${stream.audioUrl.orEmpty()}"
        if (!attemptedStreamKeys.contains(key)) {
            playbackQueue.addLast(stream)
        }
    }

    private fun buildWebFallbackCandidates(): List<String> {
        val result = mutableListOf<String>()
        for (raw in candidateUrls) {
            val ytId = YouTubeExtractor.extractVideoId(raw)
            if (ytId != null) {
                result.add("https://www.youtube.com/embed/$ytId?autoplay=1&playsinline=1")
                continue
            }
            if ((raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) &&
                !ExtractorUtils.isDirectMediaUrl(raw)
            ) {
                result.add(raw)
            }
        }
        return result.distinct()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebPlayerFallback(urls: List<String>, referer: String?) {
        releasePlayer()
        binding.playerView.visibility = View.GONE
        binding.playerWebview.visibility = View.VISIBLE
        binding.layoutPlayerTopBar.visibility = View.VISIBLE
        binding.playerWebview.setBackgroundColor(Color.BLACK)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.playerWebview, true)
        }

        binding.playerWebview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(false)
            userAgentString = ExtractorUtils.DEFAULT_UA
        }

        var currentIdx = 0
        fun loadCandidateAt(idx: Int) {
            val targetUrl = urls[idx]
            if (!referer.isNullOrBlank() && !targetUrl.contains("youtube.com", ignoreCase = true)) {
                binding.playerWebview.loadUrl(targetUrl, mapOf("Referer" to referer))
            } else {
                binding.playerWebview.loadUrl(targetUrl)
            }
        }

        fun tryNextWebCandidate() {
            currentIdx++
            if (currentIdx < urls.size) {
                binding.playerWebview.loadUrl("about:blank")
                binding.playerWebview.post { loadCandidateAt(currentIdx) }
            } else {
                binding.playerLoading.visibility = View.GONE
                binding.playerWebview.loadUrl("about:blank")
                Toast.makeText(
                    this@PlayerActivity,
                    "Не вдалося завантажити веб-плеєр",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.playerWebview.webChromeClient = WebChromeClient()
        binding.playerWebview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val navUrl = request?.url?.toString()?.lowercase(Locale.ROOT) ?: return false
                if (!navUrl.startsWith("http://") && !navUrl.startsWith("https://")) {
                    return true
                }
                if (request.isForMainFrame && AD_REDIRECT_HINTS.any { navUrl.contains(it) }) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                if (loadedUrl != "about:blank") {
                    binding.playerLoading.visibility = View.GONE
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    tryNextWebCandidate()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                    tryNextWebCandidate()
                }
            }
        }

        loadCandidateAt(0)
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        isHlsHint: Boolean = false,
        audioUrl: String? = null
    ) {
        releasePlayer()
        attemptedStreamKeys.add("$url|${audioUrl.orEmpty()}")

        binding.playerWebview.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        binding.playerLoading.visibility = View.VISIBLE

        val cleanUrl = ExtractorUtils.stripFragmentHint(url)
        val isLocal = cleanUrl.startsWith("content://", ignoreCase = true) ||
            cleanUrl.startsWith("file://", ignoreCase = true)

        val okHttpFactory = OkHttpDataSource.Factory(httpClient)
        if (!isLocal) {
            val headers = ExtractorUtils.buildStreamHeaders(cleanUrl, referer)
            for ((k, v) in extraHeaders) {
                if (v.isNotEmpty() && !(k.equals("Referer", ignoreCase = true) && cleanUrl.contains("googlevideo.com", ignoreCase = true))) {
                    headers[k] = v
                }
            }
            okHttpFactory.setDefaultRequestProperties(headers)
        }
        val dataSourceFactory = DefaultDataSource.Factory(this, okHttpFactory)

        val mediaItem = MediaItem.fromUri(cleanUrl)
        val isHls = !isLocal && (isHlsHint || ExtractorUtils.isHlsUrl(cleanUrl))
        val videoSource: MediaSource = if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
        val mediaSource: MediaSource = if (!isLocal && !audioUrl.isNullOrBlank() && !isHls) {
            val cleanAudioUrl = ExtractorUtils.stripFragmentHint(audioUrl)
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(cleanAudioUrl))
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }

        player = ExoPlayer.Builder(this).build().apply {
            setMediaSource(mediaSource)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.playerLoading.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    lastErrorMessage = error.message
                    binding.playerLoading.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        resolveMoreAndStartPlayback()
                    }
                }
            })
            prepare()
            playWhenReady = true
        }

        binding.playerView.player = player
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        binding.playerView.player = null
    }

    override fun onResume() {
        super.onResume()
        binding.playerWebview.onResume()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        binding.playerWebview.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        binding.playerWebview.destroy()
    }
}
