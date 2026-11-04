package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import java.net.URI

object UaKinoExtractor {

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

        val titleEl = doc.selectFirst("h1 span.solototle")
            ?: doc.selectFirst("h1[itemprop='name']")
            ?: doc.selectFirst("h1")
        val title = titleEl?.text()?.trim() ?: "UAKino Відео"

        val origEl = doc.selectFirst("span.origintitle, .orig-title")
        val originalTitle = origEl?.text()?.trim()

        val posterEl = doc.selectFirst(".fposter img, .film-poster img, .mov-desc img, [itemprop='image']")
        val posterSrc = posterEl?.let { if (it.hasAttr("data-src")) it.attr("data-src") else it.attr("src") }
        val posterUrl = posterSrc?.let { DlePlaylistParser.normalizeUrl(it, baseOrigin) }

        val descEl = doc.selectFirst("div[itemprop='description'], .ftext")
        val description = descEl?.text()?.trim()

        val newsId = DlePlaylistParser.extractNewsId(pageUrl, html, doc)
        val xfname = DlePlaylistParser.extractXfname(doc)
        val userHash = DlePlaylistParser.extractLoginHash(html)

        var studios = mutableListOf<DubStudio>()
        if (newsId != null) {
            studios.addAll(DlePlaylistParser.fetchDlePlaylists(client, baseOrigin, newsId, xfname, userHash, pageUrl))
        }

        if (studios.isEmpty()) {
            val iframes = doc.select("iframe[src]")
            for (ifr in iframes) {
                val src = ifr.attr("src")
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
                val defaultStudio = DubStudio("default", "Українська озвучка", players)
                studios.add(defaultStudio)
            }
        }

        val isSeries = studios.any { s -> s.players.any { p -> p.episodes.size > 1 } }

        return MediaInfo(
            url = pageUrl,
            siteType = "uakino",
            title = title,
            originalTitle = originalTitle,
            posterUrl = posterUrl,
            description = description,
            isSeries = isSeries,
            studios = studios,
            availableQualities = mutableListOf("1080p", "720p", "480p", "360p")
        )
    }
}
