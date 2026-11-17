package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import ua.aniua.downloader.model.MediaInfo

object ExtractorRouter {

    fun analyzeUrl(client: OkHttpClient, url: String, preloadedHtml: String? = null): MediaInfo {
        val cleanUrl = ExtractorUtils.ensureHttpsScheme(url)
        val lowerUrl = cleanUrl.lowercase()

        // 1. YouTube (Watch, Shorts, youtu.be, embed)
        if (YouTubeExtractor.isYouTubeUrl(cleanUrl)) {
            return YouTubeExtractor.parseVideo(client, cleanUrl)
        }

        // 2. TikTok (Відео, кліпи, короткі посилання vm.tiktok / vt.tiktok)
        if (TikTokExtractor.isTikTokUrl(cleanUrl)) {
            return TikTokExtractor.parseVideo(client, cleanUrl, preloadedHtml)
        }

        // 3. Instagram (Reels, пости, IGTV, share посилання)
        if (InstagramExtractor.isInstagramUrl(cleanUrl)) {
            return InstagramExtractor.parseVideo(client, cleanUrl, preloadedHtml)
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

        // 9. Інші сайти та універсальні веб-сторінки з відео/серіалами
        return GenericSeriesExtractor.parseGenericPage(client, cleanUrl, preloadedHtml)
    }
}
