package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource

object ExtractorRouter {

    fun analyzeUrl(client: OkHttpClient, url: String): MediaInfo {
        val cleanUrl = url.trim()
        val lowerUrl = cleanUrl.lowercase()

        if (lowerUrl.contains("uakino")) {
            return UaKinoExtractor.parsePage(client, cleanUrl)
        }

        if (lowerUrl.contains("anitube")) {
            return AnitubeExtractor.parsePage(client, cleanUrl)
        }

        if (lowerUrl.contains("ashdi") && lowerUrl.contains("/serial/")) {
            val studios = AshdiExtractor.parseAshdiSerial(client, cleanUrl)
            return MediaInfo(
                url = cleanUrl,
                siteType = "ashdi",
                title = "Серіал (Ashdi)",
                isSeries = true,
                studios = studios.toMutableList(),
                availableQualities = mutableListOf("1080p", "720p", "480p", "360p")
            )
        }

        // Пряме посилання на плеєр (Ashdi, Tortuga, MoonAnime тощо) або потік .m3u8/.mp4
        val qualities = GenericPlayerResolver.resolvePlayerStreams(client, cleanUrl, cleanUrl)
        val studio = DubStudio("direct_studio", "Оригінальна якість")
        val player = PlayerSource("direct_player", "Плеєр")
        player.episodes.add(EpisodeItem("ep_direct", "1", "Повне відео", "1", cleanUrl))
        studio.players.add(player)

        val labels = if (qualities.isNotEmpty()) {
            qualities.map { it.label }.toMutableList()
        } else {
            mutableListOf("1080p", "720p", "480p", "360p")
        }

        return MediaInfo(
            url = cleanUrl,
            siteType = "player",
            title = "Онлайн Відео",
            isSeries = false,
            studios = mutableListOf(studio),
            availableQualities = labels
        )
    }
}
