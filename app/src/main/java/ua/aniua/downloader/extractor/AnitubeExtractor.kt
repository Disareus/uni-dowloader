package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import java.net.URI

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
                if (src.contains("ashdi") || src.contains("tortuga") || src.contains("moonanime")) {
                    val normalizedSrc = DlePlaylistParser.normalizeUrl(src, baseOrigin)
                    val ps = PlayerSource("player_${players.size}", "Плеєр ${players.size + 1}")
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
            posterUrl = posterUrl,
            description = description,
            isSeries = isSeries,
            studios = studios,
            availableQualities = mutableListOf("1080p", "720p", "480p", "360p")
        )
    }
}
