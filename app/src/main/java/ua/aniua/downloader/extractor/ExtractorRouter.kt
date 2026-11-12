package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource

object ExtractorRouter {

    fun analyzeUrl(client: OkHttpClient, url: String): MediaInfo {
        val cleanUrl = url.trim()
        val lowerUrl = cleanUrl.lowercase()

        // 1. YouTube (Watch, Shorts, youtu.be, embed)
        if (YouTubeExtractor.isYouTubeUrl(cleanUrl)) {
            return YouTubeExtractor.parseVideo(client, cleanUrl)
        }

        // 2. TikTok (Відео, кліпи, короткі посилання vm.tiktok / vt.tiktok)
        if (TikTokExtractor.isTikTokUrl(cleanUrl)) {
            return TikTokExtractor.parseVideo(client, cleanUrl)
        }

        // 3. Instagram (Reels, пости, IGTV, share посилання)
        if (InstagramExtractor.isInstagramUrl(cleanUrl)) {
            return InstagramExtractor.parseVideo(client, cleanUrl)
        }

        // 4. UAKino (Фільми, серіали, аніме)
        if (UaKinoExtractor.isUaKinoUrl(cleanUrl)) {
            return UaKinoExtractor.parsePage(client, cleanUrl)
        }

        // 5. AniTube (Аніме, фільми, OVA)
        if (lowerUrl.contains("anitube")) {
            return AnitubeExtractor.parsePage(client, cleanUrl)
        }

        // 6. Прямі серіали Ashdi
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

        // 7. Fenix Player
        if (FenixExtractor.isFenixUrl(cleanUrl)) {
            val fenixMedia = FenixExtractor.parseFenixPage(client, cleanUrl)
            if (fenixMedia != null) return fenixMedia
        }

        // 8. PeerTube
        if (PeerTubeExtractor.isPeerTubeUrl(cleanUrl)) {
            val peerMedia = PeerTubeExtractor.parsePage(client, cleanUrl)
            if (peerMedia != null) return peerMedia
        }

        // 9. Інші сайти та універсальні веб-сторінки з відео
        return parseGenericWebOrStream(client, cleanUrl)
    }

    private fun parseGenericWebOrStream(client: OkHttpClient, cleanUrl: String): MediaInfo {
        var pageTitle = "Онлайн Відео"
        var posterUrl: String? = null
        var description: String? = null

        // Якщо це не прямий медіафайл (.m3u8 / .mp4), намагаємося витягнути метадані сторінки (OpenGraph)
        val isDirectMedia = cleanUrl.contains(".m3u8") || cleanUrl.contains(".mp4")
        if (!isDirectMedia && (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://"))) {
            try {
                val req = Request.Builder()
                    .url(cleanUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful && resp.body != null) {
                    val html = resp.body?.string() ?: ""
                    val doc = Jsoup.parse(html, cleanUrl)

                    val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
                    val tagTitle = doc.title().trim()
                    if (!ogTitle.isNullOrEmpty()) {
                        pageTitle = ogTitle.trim()
                    } else if (tagTitle.isNotEmpty()) {
                        pageTitle = tagTitle
                    }

                    posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")
                    description = doc.selectFirst("meta[property='og:description'], meta[name='description']")?.attr("content")
                }
            } catch (ignored: Exception) {}
        }

        val qualities = GenericPlayerResolver.resolvePlayerStreams(client, cleanUrl, cleanUrl)
        val studio = DubStudio("direct_studio", "Оригінал")
        val player = PlayerSource("direct_player", "Плеєр")
        player.episodes.add(EpisodeItem("ep_direct", "1", pageTitle, "1", cleanUrl))
        studio.players.add(player)

        val labels = if (qualities.isNotEmpty()) {
            qualities.map { it.label }.distinct().toMutableList()
        } else {
            mutableListOf("1080p", "720p", "480p", "360p")
        }

        return MediaInfo(
            url = cleanUrl,
            siteType = "web",
            title = pageTitle,
            posterUrl = posterUrl,
            description = description,
            isSeries = false,
            studios = mutableListOf(studio),
            availableQualities = labels
        )
    }
}
