package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

object AnitubeExtractor {

    fun parsePage(client: OkHttpClient, pageUrl: String): MediaInfo {
        val uri = URI(pageUrl)
        val baseOrigin = "${uri.scheme}://${uri.host}"

        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "$baseOrigin/")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful || response.body == null) {
            throw IllegalStateException("HTTP error ${response.code}")
        }

        val html = response.body?.string() ?: ""
        val doc = Jsoup.parse(html, pageUrl)

        val titleEl = doc.selectFirst(".story_c h2, h1, meta[property='og:title']")
        val title = if (titleEl != null) {
            if (titleEl.tagName() == "meta") titleEl.attr("content") else titleEl.text().trim()
        } else {
            "Anitube Аніме"
        }

        val posterEl = doc.selectFirst(".story_post img, .fposter img, meta[property='og:image']")
        val posterSrc = posterEl?.let {
            if (it.tagName() == "meta") it.attr("content")
            else if (it.hasAttr("data-src")) it.attr("data-src")
            else it.attr("src")
        }
        val posterUrl = posterSrc?.let { DlePlaylistParser.normalizeUrl(it, baseOrigin) }

        val descEl = doc.selectFirst(".story_c_text, div[itemprop='description'], meta[property='og:description']")
        val description = descEl?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }

        val origTitleEl = doc.selectFirst(".story_c_r dt:contains(Оригінальна), .story_c_r dt:contains(Японська)")?.nextElementSibling()
            ?: doc.selectFirst(".orig-title, span[itemprop='alternateName']")
        val originalTitle = origTitleEl?.text()?.trim()

        val ratingPair = extractRating(doc, html)
        val rating = ratingPair.first
        val ratingVotes = ratingPair.second

        var studios = mutableListOf<DubStudio>()

        val newsId = DlePlaylistParser.extractNewsId(pageUrl, html, doc)
        val xfname = DlePlaylistParser.extractXfname(doc)
        val userHash = DlePlaylistParser.extractLoginHash(html)

        if (newsId != null) {
            studios.addAll(DlePlaylistParser.fetchDlePlaylists(client, baseOrigin, newsId, xfname, userHash, pageUrl))
        }

        if (studios.isEmpty()) {
            studios.addAll(RalodePlayerParser.parseRalodeScript(html, baseOrigin))
        }

        if (studios.isEmpty()) {
            val iframes = doc.select("iframe[src]")
            val players = mutableListOf<PlayerSource>()
            for (ifr in iframes) {
                val src = ifr.attr("src")
                if (src.contains("ashdi") || src.contains("tortuga") || MoonAnimeExtractor.isMoonUrl(src) ||
                    FenixExtractor.isFenixUrl(src) || PeerTubeExtractor.isPeerTubeUrl(src)
                ) {
                    val normalizedSrc = DlePlaylistParser.normalizeUrl(src, baseOrigin)
                    val pName = when {
                        src.contains("ashdi") -> "Ashdi"
                        src.contains("tortuga") -> "Tortuga"
                        MoonAnimeExtractor.isMoonUrl(src) -> "Moon"
                        FenixExtractor.isFenixUrl(src) -> "Fenix"
                        PeerTubeExtractor.isPeerTubeUrl(src) -> "PeerTube"
                        else -> "Плеєр ${players.size + 1}"
                    }
                    val ps = PlayerSource("player_${players.size}", pName)
                    ps.episodes.add(EpisodeItem("ep_single", "1", title, "1", normalizedSrc))
                    players.add(ps)
                }
            }
            if (players.isNotEmpty()) {
                val defaultStudio = DubStudio("default", "Оригінал / Озвучення", players)
                studios.add(defaultStudio)
            }
        }

        val isSeries = studios.any { s -> s.players.any { p -> p.episodes.size > 1 } }

        return MediaInfo(
            url = pageUrl,
            siteType = "anitube",
            title = title,
            originalTitle = originalTitle,
            posterUrl = posterUrl,
            description = description,
            isSeries = isSeries,
            studios = studios,
            availableQualities = mutableListOf("1080p", "720p", "480p", "360p"),
            rating = rating,
            ratingVotes = ratingVotes
        )
    }

    fun extractRating(doc: Document, html: String): Pair<String?, String?> {
        // 1. Lexington box з точною оцінкою та голосами (<span>9.6</span>/<span>10</span> (202))
        val lexEl = doc.selectFirst(".lexington-box")
        if (lexEl != null) {
            val lexText = lexEl.text()
            val m = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*/\\s*10(?:\\s*\\(?\\s*([0-9]+)\\s*\\)?)?").matcher(lexText)
            if (m.find()) {
                val score = m.group(1)?.replace(',', '.') ?: ""
                val votes = m.group(2)
                val label = if (!votes.isNullOrEmpty()) "★ $score/10 ($votes)" else "★ $score/10"
                return Pair(label, votes)
            }
        }

        // 2. Starbar_w ширина (218px = 10.0 балів)
        val starEl = doc.selectFirst(".starbar_w")
        if (starEl != null) {
            val style = starEl.attr("style")
            val sm = Pattern.compile("width:\\s*([0-9]+(?:\\.[0-9]+)?)px").matcher(style)
            if (sm.find()) {
                val px = sm.group(1)?.toDoubleOrNull()
                if (px != null && px > 0) {
                    val score = Math.round((px / 218.0) * 10.0 * 10.0) / 10.0
                    return Pair(String.format(Locale.US, "★ %.1f/10", score), null)
                }
            }
        }

        // 3. Schema.org мікророзмітка
        val valEl = doc.selectFirst("meta[itemprop='ratingValue'], [itemprop='ratingValue']")
        val countEl = doc.selectFirst("meta[itemprop='ratingCount'], [itemprop='ratingCount']")
        val rVal = valEl?.attr("content")?.ifEmpty { valEl.text().trim() }
        val rCount = countEl?.attr("content")?.ifEmpty { countEl.text().trim() }
        if (!rVal.isNullOrEmpty()) {
            val label = if (!rCount.isNullOrEmpty()) "★ $rVal/10 ($rCount)" else "★ $rVal/10"
            return Pair(label, rCount)
        }

        return Pair(null, null)
    }
}
