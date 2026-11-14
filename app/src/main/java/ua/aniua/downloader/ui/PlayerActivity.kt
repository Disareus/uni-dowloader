package ua.aniua.downloader.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.aniua.downloader.databinding.ActivityPlayerBinding
import ua.aniua.downloader.extractor.ExtractorUtils
import ua.aniua.downloader.extractor.GenericPlayerResolver
import ua.aniua.downloader.extractor.WebViewStreamResolver
import ua.aniua.downloader.model.StreamQuality
import ua.security.network.NetworkSecurityManager

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_FALLBACK_URLS = "extra_fallback_urls"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveMode()

        binding.btnPlayerBack.setOnClickListener { finish() }
        binding.tvPlayerTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: ""

        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.layoutPlayerTopBar.visibility = visibility
            }
        )

        val rawUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val fallbackUrls = intent.getStringArrayListExtra(EXTRA_FALLBACK_URLS).orEmpty()
        val candidateUrls = (listOfNotNull(rawUrl) + fallbackUrls)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (candidateUrls.isEmpty()) {
            Toast.makeText(this, "Посилання на відео відсутнє", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.playerLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            var resolvedStream = withContext(Dispatchers.IO) {
                val client = NetworkSecurityManager.getInstance(this@PlayerActivity).hardenedHttpClient
                var found: StreamQuality? = null
                for (candidate in candidateUrls) {
                    found = if (ExtractorUtils.isDirectMediaUrl(candidate)) {
                        StreamQuality("Auto", 720, candidate, ExtractorUtils.isHlsUrl(candidate)).apply {
                            if (!referer.isNullOrEmpty()) headers["Referer"] = referer
                        }
                    } else {
                        GenericPlayerResolver.resolvePlayerStreams(client, candidate, referer).firstOrNull()
                    }
                    if (found != null) break
                }
                found
            }

            if (resolvedStream == null) {
                for (candidate in candidateUrls) {
                    if (ExtractorUtils.isDirectMediaUrl(candidate)) continue
                    resolvedStream = WebViewStreamResolver.resolveStreamViaWebView(this@PlayerActivity, candidate, referer).firstOrNull()
                    if (resolvedStream != null) break
                }
            }

            if (isFinishing || isDestroyed) return@launch
            if (resolvedStream == null) {
                val webCandidates = candidateUrls.filter {
                    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
                }
                if (webCandidates.isNotEmpty()) {
                    initWebPlayerFallback(webCandidates, referer)
                } else {
                    binding.playerLoading.visibility = View.GONE
                    Toast.makeText(
                        this@PlayerActivity,
                        "Не вдалося знайти відеопотік у плеєрі",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
                return@launch
            }
            val effectiveReferer = resolvedStream.headers["Referer"] ?: referer
            initPlayer(resolvedStream.url, effectiveReferer, resolvedStream.headers, resolvedStream.isHls)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebPlayerFallback(urls: List<String>, referer: String?) {
        binding.playerView.visibility = View.GONE
        binding.playerWebview.visibility = View.VISIBLE
        binding.layoutPlayerTopBar.visibility = View.VISIBLE

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
            userAgentString = ExtractorUtils.DEFAULT_UA
        }

        var currentIdx = 0
        fun loadCandidateAt(idx: Int) {
            val targetUrl = urls[idx]
            if (!referer.isNullOrBlank()) {
                binding.playerWebview.loadUrl(targetUrl, mapOf("Referer" to referer))
            } else {
                binding.playerWebview.loadUrl(targetUrl)
            }
        }

        binding.playerWebview.webChromeClient = WebChromeClient()
        binding.playerWebview.webViewClient = object : WebViewClient() {
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
                    currentIdx++
                    if (currentIdx < urls.size) {
                        view?.loadUrl("about:blank")
                        view?.post { loadCandidateAt(currentIdx) }
                    } else {
                        binding.playerLoading.visibility = View.GONE
                        view?.loadUrl("about:blank")
                        Toast.makeText(
                            this@PlayerActivity,
                            "Не вдалося завантажити веб-плеєр",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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

    private fun initPlayer(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        isHlsHint: Boolean = false
    ) {
        val cleanUrl = url.substringBefore("#")
        val headers = ExtractorUtils.buildStreamHeaders(cleanUrl, referer)
        for ((k, v) in extraHeaders) {
            if (v.isNotEmpty()) headers[k] = v
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val mediaItem = MediaItem.fromUri(cleanUrl)
        val isHls = isHlsHint || ExtractorUtils.isHlsUrl(cleanUrl)
        val mediaSource: MediaSource = if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        player = ExoPlayer.Builder(this).build().apply {
            setMediaSource(mediaSource)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.playerLoading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.playerLoading.visibility = View.GONE
                    Toast.makeText(this@PlayerActivity, "Помилка відтворення: ${error.message}", Toast.LENGTH_LONG).show()
                }
            })
            prepare()
            playWhenReady = true
        }

        binding.playerView.player = player
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
        player?.release()
        player = null
        binding.playerWebview.destroy()
    }
}
