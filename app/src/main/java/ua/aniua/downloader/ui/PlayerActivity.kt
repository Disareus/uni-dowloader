package ua.aniua.downloader.ui

import android.os.Bundle
import android.view.View
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
import ua.security.network.NetworkSecurityManager

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_TITLE = "extra_title"
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

        if (rawUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Посилання на відео відсутнє", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.playerLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            val resolvedStream = withContext(Dispatchers.IO) {
                if (ExtractorUtils.isDirectMediaUrl(rawUrl)) {
                    rawUrl to referer
                } else {
                    val client = NetworkSecurityManager.getInstance(this@PlayerActivity).hardenedHttpClient
                    val streams = GenericPlayerResolver.resolvePlayerStreams(client, rawUrl, referer)
                    if (streams.isNotEmpty()) {
                        val best = streams.first()
                        best.url to (best.headers["Referer"] ?: referer)
                    } else {
                        rawUrl to referer
                    }
                }
            }

            if (isFinishing || isDestroyed) return@launch
            initPlayer(resolvedStream.first, resolvedStream.second)
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun initPlayer(url: String, referer: String?) {
        val cleanUrl = url.substringBefore("#")
        val headers = ExtractorUtils.buildStreamHeaders(cleanUrl, referer)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val mediaItem = MediaItem.fromUri(cleanUrl)
        val mediaSource: MediaSource = if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
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

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
