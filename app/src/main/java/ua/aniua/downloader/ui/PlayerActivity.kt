package ua.aniua.downloader.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.aniua.downloader.R
import ua.aniua.downloader.extractor.GenericPlayerResolver
import ua.security.network.NetworkSecurityManager

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_TITLE = "extra_title"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var loadingProgress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        loadingProgress = findViewById(R.id.player_loading)
        val btnBack = findViewById<ImageButton>(R.id.btn_player_back)
        btnBack.setOnClickListener { finish() }

        val rawUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val referer = intent.getStringExtra(EXTRA_REFERER)

        if (rawUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Посилання на відео відсутнє", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val playUrl = withContext(Dispatchers.IO) {
                if (rawUrl.contains(".m3u8") || rawUrl.contains(".mp4") || rawUrl.startsWith("content://") || rawUrl.startsWith("file://")) {
                    rawUrl
                } else {
                    val client = NetworkSecurityManager.getInstance(this@PlayerActivity).hardenedHttpClient
                    val streams = GenericPlayerResolver.resolvePlayerStreams(client, rawUrl, referer)
                    if (streams.isNotEmpty()) streams[0].url else rawUrl
                }
            }

            if (isFinishing || isDestroyed) return@launch
            initPlayer(playUrl, referer)
        }
    }

    private fun initPlayer(url: String, referer: String?) {
        val cleanUrl = url.substringBefore("#")
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        if (!referer.isNullOrEmpty()) {
            headers["Referer"] = referer
        }
        if (cleanUrl.contains("fenix") || referer?.contains("fenix") == true) {
            headers["Origin"] = "https://fenixplay.xyz"
        } else if (cleanUrl.contains("moon") || referer?.contains("moon") == true) {
            headers["Origin"] = "https://moonanime.net"
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val mediaItem = MediaItem.fromUri(cleanUrl)
        val mediaSource: MediaSource = if (cleanUrl.contains(".m3u8")) {
            HlsMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        }

        player = ExoPlayer.Builder(this).build().apply {
            setMediaSource(mediaSource)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    loadingProgress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    loadingProgress.visibility = View.GONE
                    Toast.makeText(this@PlayerActivity, "Помилка відтворення: ${error.message}", Toast.LENGTH_LONG).show()
                }
            })
            prepare()
            playWhenReady = true
        }

        playerView.player = player
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
