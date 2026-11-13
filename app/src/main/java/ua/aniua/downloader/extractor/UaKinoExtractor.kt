package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import java.util.Locale
import java.util.regex.Pattern

object UaKinoExtractor {

    private val JS_PLAYER_FILE_PATTERN = Pattern.compile("(?:file|src)\\s*:\\s*[\"']([^\"']+)[\"']")

    fun isUaKinoUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("uakino")
    }

    fun parsePage(client: OkHttpClient, pageUrl: String): MediaInfo {
        val normalizedPageUrl = ExtractorUtils.ensureHttpsScheme(pageUrl)
        val baseOrigin = ExtractorUtils.extractBaseOrigin(normalizedPageUrl, "https://uakino.me")

        val request = Request.Builder()
            .url(normalizedPageUrl)
            .header("User-Agent", ExtractorUtils.DEFAULT_UA)
            .header("Referer", "$baseOrigin/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "uk,en-US;q=0.9,en;q=0.8")
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) {
                throw IllegalStateException("HTTP error ${response.code}")
            }
            response.body?.string() ?: ""
        }
        val doc = Jsoup.parse(html, normalizedPageUrl)

        val titleEl = doc.selectFirst("h1 span.solototle")
            ?: doc.selectFirst("h1[itemprop='name']")
            ?: doc.selectFirst(".solotitle")
            ?: doc.selectFirst("h1")
        val title = titleEl?.text()?.trim() ?: "UAKino Відео"

        val origEl = doc.selectFirst("span.origintitle, .orig-title, span.origin-title")
        val originalTitle = origEl?.text()?.trim()

        val posterEl = doc.selectFirst(".fposter img, .film-poster img, .mov-desc img, [itemprop='image'], .fimg img")
        val posterSrc = posterEl?.let {
            when {
                it.hasAttr("data-src") && it.attr("data-src").isNotEmpty() -> it.attr("data-src")
                it.hasAttr("data-lazy-src") && it.attr("data-lazy-src").isNotEmpty() -> it.attr("data-lazy-src")
                else -> it.attr("src")
            }
        }
        val posterUrl = posterSrc?.let { DlePlaylistParser.normalizeUrl(it, baseOrigin) }

        val descEl = doc.selectFirst("div[itemprop='description'], .ftext, .full-text")
        val description = descEl?.text()?.trim()

        val newsId = DlePlaylistParser.extractNewsId(normalizedPageUrl, html, doc)
        val xfname = DlePlaylistParser.extractXfname(doc)
        val userHash = DlePlaylistParser.extractLoginHash(html)

        val studios = mutableListOf<DubStudio>()

        // 1. DLE Ajax Playlists
        if (newsId != null) {
            try {
                studios.addAll(DlePlaylistParser.fetchDlePlaylists(client, baseOrigin, newsId, xfname, userHash, normalizedPageUrl))
            } catch (_: Exception) {}
        }

        // 2. Пошук серіалу Ashdi у вбудованих iframe
        if (studios.isEmpty()) {
            val allIframes = doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
            for (ifr in allIframes) {
                val src = getIframeSource(ifr)
                if (src.contains("ashdi") && src.contains("/serial/")) {
                    val fullSerialUrl = DlePlaylistParser.normalizeUrl(src, baseOrigin)
                    val serialStudios = AshdiExtractor.parseAshdiSerial(client, fullSerialUrl)
                    if (serialStudios.isNotEmpty()) {
                        studios.addAll(serialStudios)
                        break
                    }
                }
            }
        }

        // 3. Пошук табів/кнопок із плеєрами (селектори DLE та кастомних шаблонів UAKino)
        if (studios.isEmpty()) {
            val playerTabs = doc.select(".tabs-sel a, .tabs-block a, .player-section [data-file], [data-player], .video-box [data-src]")
            val playersFromTabs = mutableListOf<PlayerSource>()
            for (tab in playerTabs) {
                var file = tab.attr("data-file").ifEmpty { tab.attr("data-src").ifEmpty { tab.attr("href") } }.trim()
                if (file.isNotEmpty() && !file.startsWith("#") && !file.startsWith("javascript")) {
                    val normalizedFile = DlePlaylistParser.normalizeUrl(file, baseOrigin)
                    if (isKnownPlayerUrl(normalizedFile)) {
                        val tabName = tab.text().trim().ifEmpty { "Плеєр ${playersFromTabs.size + 1}" }
                        val ps = PlayerSource("tab_player_${playersFromTabs.size}", tabName)
                        ps.episodes.add(EpisodeItem("ep_${playersFromTabs.size + 1}", "1", title, "1", normalizedFile))
                        playersFromTabs.add(ps)
                    }
                }
            }
            if (playersFromTabs.isNotEmpty()) {
                studios.add(DubStudio("default_tabs", "Українська озвучка", playersFromTabs))
            }
        }

        // 4. Пошук стандартних iframe (Ashdi, Tortuga, Moon, Fenix, PeerTube, Sibnet тощо)
        if (studios.isEmpty()) {
            val allIframes = doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
            val players = mutableListOf<PlayerSource>()
            for (ifr in allIframes) {
                val src = getIframeSource(ifr)
                if (isKnownPlayerUrl(src)) {
                    val normalizedSrc = DlePlaylistParser.normalizeUrl(src, baseOrigin)
                    val pName = identifyPlayerName(normalizedSrc, players.size + 1)
                    val ps = PlayerSource("player_${players.size}", pName)
                    ps.episodes.add(EpisodeItem("ep_single", "1", title, "1", normalizedSrc))
                    players.add(ps)
                }
            }
            if (players.isNotEmpty()) {
                val defaultStudio = DubStudio("default", "Українська озвучка", players)
                studios.add(defaultStudio)
            }
        }

        // 5. Пошук плеєрів у JavaScript (var player_url = ..., file: "...")
        if (studios.isEmpty()) {
            val jsMatcher = JS_PLAYER_FILE_PATTERN.matcher(html)
            val playersFromJs = mutableListOf<PlayerSource>()
            while (jsMatcher.find()) {
                val fileUrl = jsMatcher.group(1)?.trim() ?: ""
                if (isKnownPlayerUrl(fileUrl)) {
                    val normalized = DlePlaylistParser.normalizeUrl(fileUrl, baseOrigin)
                    val pName = identifyPlayerName(normalized, playersFromJs.size + 1)
                    val ps = PlayerSource("js_player_${playersFromJs.size}", pName)
                    ps.episodes.add(EpisodeItem("ep_js", "1", title, "1", normalized))
                    playersFromJs.add(ps)
                }
            }
            if (playersFromJs.isNotEmpty()) {
                studios.add(DubStudio("default_js", "Українська озвучка", playersFromJs))
            }
        }

        // 5.5 Універсальний fallback пошуку серій, якщо знайдено <= 1 серії
        val maxExistingEps = studios.flatMap { it.players }.maxOfOrNull { it.episodes.size } ?: 0
        if (maxExistingEps <= 1) {
            val genericStudios = GenericSeriesExtractor.extractEpisodesFromDocument(client, normalizedPageUrl, html, doc, baseOrigin)
            val maxGenericEps = genericStudios.flatMap { it.players }.maxOfOrNull { it.episodes.size } ?: 0
            if (maxGenericEps > maxExistingEps) {
                studios.clear()
                studios.addAll(genericStudios)
            }
        }

        // 6. Визначення чи це серіал чи одиночний фільм
        val hasMultipleEpisodes = studios.any { s -> s.players.any { p -> p.episodes.size > 1 } }
        val titleLower = (title + " " + (description ?: "")).lowercase(Locale.ROOT)
        val hasSeriesKeywords = titleLower.contains("серіал") ||
                titleLower.contains("сезон") ||
                titleLower.contains("серія") ||
                titleLower.contains("дорама")

        val isSeries = hasMultipleEpisodes || (hasSeriesKeywords && studios.isNotEmpty())

        val rateEl = doc.selectFirst(".rate-imdb span, .rate-kp span, meta[itemprop='ratingValue'], [itemprop='ratingValue']")
        val rVal = if (rateEl?.tagName() == "meta") rateEl.attr("content") else rateEl?.text()?.trim()
        val rating = if (!rVal.isNullOrEmpty()) "★ $rVal" else null

        return MediaInfo(
            url = normalizedPageUrl,
            siteType = "uakino",
            title = title,
            originalTitle = originalTitle,
            posterUrl = posterUrl,
            description = description,
            isSeries = isSeries,
            studios = studios,
            availableQualities = mutableListOf("1080p", "720p", "480p", "360p"),
            rating = rating
        )
    }

    private fun getIframeSource(ifr: org.jsoup.nodes.Element): String {
        return when {
            ifr.hasAttr("src") && ifr.attr("src").isNotEmpty() -> ifr.attr("src").trim()
            ifr.hasAttr("data-src") && ifr.attr("data-src").isNotEmpty() -> ifr.attr("data-src").trim()
            ifr.hasAttr("data-lazy-src") && ifr.attr("data-lazy-src").isNotEmpty() -> ifr.attr("data-lazy-src").trim()
            else -> ""
        }
    }

    fun isKnownPlayerUrl(src: String): Boolean {
        val lower = src.lowercase(Locale.ROOT)
        return lower.contains("ashdi") ||
                lower.contains("tortuga") ||
                lower.contains("moonanime") ||
                lower.contains("mooncdn") ||
                lower.contains("fenix") ||
                lower.contains("peertube") ||
                lower.contains("sibnet") ||
                lower.contains("kodik") ||
                lower.contains("ustore") ||
                lower.contains("alloha") ||
                lower.contains("collaps") ||
                lower.contains(".m3u8") ||
                lower.contains(".mp4")
    }

    fun identifyPlayerName(url: String, index: Int): String {
        return ExtractorUtils.identifyPlayer(url, index).second
    }
}
