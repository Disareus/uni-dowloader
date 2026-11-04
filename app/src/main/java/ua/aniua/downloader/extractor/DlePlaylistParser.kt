package ua.aniua.downloader.extractor

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.PlayerSource
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

object DlePlaylistParser {

    private val NEWS_ID_PATTERN = Pattern.compile("dle_news_id\\s*=\\s*['\"]?(\\d+)['\"]?")
    private val URL_ID_PATTERN = Pattern.compile("/(\\d+)-[a-zA-Z0-9_-]+\\.html")
    private val LOGIN_HASH_PATTERN = Pattern.compile("dle_login_hash\\s*=\\s*['\"]([^'\"]+)['\"]")
    private val SEASON_PATTERN = Pattern.compile("(?:сезон|season)\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
    private val NUMBER_PATTERN = Pattern.compile("(\\d+)")

    private val PLAYER_KEYWORDS = listOf(
        "плеєр", "player", "ashdi", "tortuga", "moonanime", "fenix", "sibnet", "kodik", "moon"
    )

    fun extractNewsId(url: String, html: String, doc: Document?): String? {
        if (doc != null) {
            val el = doc.selectFirst("[data-news_id]")
            if (el != null && el.attr("data-news_id").isNotEmpty()) {
                return el.attr("data-news_id").trim()
            }
        }
        val m1 = NEWS_ID_PATTERN.matcher(html)
        if (m1.find()) return m1.group(1)

        val m2 = URL_ID_PATTERN.matcher(url)
        if (m2.find()) return m2.group(1)

        return null
    }

    fun extractXfname(doc: Document?): String {
        if (doc != null) {
            val el = doc.selectFirst("[data-xfname]")
            if (el != null && el.attr("data-xfname").isNotEmpty()) {
                return el.attr("data-xfname").trim()
            }
        }
        return "playlist"
    }

    fun extractLoginHash(html: String): String {
        val m = LOGIN_HASH_PATTERN.matcher(html)
        return if (m.find()) m.group(1).trim() else ""
    }

    fun fetchDlePlaylists(
        client: OkHttpClient,
        baseOrigin: String,
        newsId: String,
        xfield: String,
        userHash: String?,
        referer: String
    ): List<DubStudio> {
        var ajaxUrl = "$baseOrigin/engine/ajax/playlists.php?news_id=$newsId&xfield=$xfield"
        if (!userHash.isNullOrEmpty()) {
            ajaxUrl += "&user_hash=$userHash"
        }

        return try {
            val request = Request.Builder()
                .url(ajaxUrl)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) return emptyList()

            val raw = response.body?.string()?.trim() ?: return emptyList()
            var playlistHtml = raw

            if (raw.startsWith("{")) {
                try {
                    val json = JsonParser.parseString(raw).asJsonObject
                    if (json.has("response")) {
                        playlistHtml = json.get("response").asString
                    }
                } catch (ignored: Exception) {}
            }

            if (playlistHtml.length < 20) return emptyList()

            parseDleHtml(playlistHtml, baseOrigin)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseDleHtml(html: String, baseOrigin: String): List<DubStudio> {
        val doc = Jsoup.parse(html)
        val tabNames = mutableMapOf<String, String>()

        val tabs = doc.select(".playlists-lists li[data-id], .playlists-tabs li[data-id]")
        for (tab in tabs) {
            val tid = tab.attr("data-id").trim()
            val name = tab.text().trim()
            if (tid.isNotEmpty() && name.isNotEmpty()) {
                tabNames[tid] = name
            }
        }

        val epNodes = doc.select(".playlists-videos li[data-file], li[data-file]")
        if (epNodes.isEmpty()) return emptyList()

        val grouped = linkedMapOf<String, LinkedHashMap<String, PlayerSource>>()

        for (idx in epNodes.indices) {
            val node = epNodes[idx]
            var rawFile = node.attr("data-file").trim()
            if (rawFile.isEmpty()) rawFile = node.attr("data-src").trim()
            if (rawFile.isEmpty()) continue

            val iframeUrl = normalizeUrl(rawFile, baseOrigin)
            var epTitle = node.text().trim()
            if (epTitle.isEmpty()) epTitle = "Серія ${idx + 1}"

            val dataId = node.attr("data-id").trim()
            val dataVoice = node.attr("data-voice").trim()

            var seasonNum = "1"
            var typePrefix = ""
            val voiceParts = mutableListOf<String>()
            var playerTabLabel = ""

            if (dataId.isNotEmpty()) {
                val parts = dataId.split("_")
                for (depth in 1..parts.size) {
                    val prefix = parts.take(depth).joinToString("_")
                    val lbl = tabNames[prefix] ?: continue

                    val low = lbl.lowercase(Locale.ROOT)
                    val sm = SEASON_PATTERN.matcher(low)
                    if (sm.find()) {
                        seasonNum = sm.group(1) ?: "1"
                        continue
                    }

                    if (low.contains("озвучення") || low.contains("озвучка") || low.contains("субтитри")) {
                        typePrefix = if (low.contains("субтитр")) "Субтитри" else "Озвучення"
                        continue
                    }

                    var isPlayer = false
                    for (pk in PLAYER_KEYWORDS) {
                        if (low.contains(pk)) {
                            playerTabLabel = lbl
                            isPlayer = true
                            break
                        }
                    }
                    if (isPlayer) continue

                    val cleanedVoice = lbl.replace(Regex("(?i)^(?:озвучення|озвучка)\\s+"), "").trim()
                    voiceParts.add(cleanedVoice.ifEmpty { lbl })
                }
            }

            if (dataVoice.isNotEmpty() && voiceParts.isEmpty()) {
                voiceParts.add(dataVoice)
            }

            var studioName = if (voiceParts.isEmpty()) "Українська озвучка" else voiceParts.joinToString(" / ")
            if (typePrefix == "Субтитри" && !studioName.lowercase(Locale.ROOT).contains("субтитр")) {
                studioName = "$studioName (Субтитри)"
            }

            var pId = "player"
            var pName = "Плеєр"
            if (iframeUrl.contains("ashdi")) {
                pId = "ashdi"; pName = "Ashdi"
            } else if (iframeUrl.contains("tortuga")) {
                pId = "tortuga"; pName = "Tortuga"
            } else if (iframeUrl.contains("moonanime")) {
                pId = "moonanime"; pName = "MoonAnime"
            } else if (iframeUrl.contains("fenix") || iframeUrl.contains("fenixplay")) {
                pId = "fenix"; pName = "Fenix"
            }

            if (playerTabLabel.isNotEmpty()) {
                pName = playerTabLabel
                pId = pId + "_" + playerTabLabel.replace(Regex("[^a-zA-Z0-9]+"), "_").lowercase(Locale.ROOT)
            }

            val studioBucket = grouped.getOrPut(studioName) { LinkedHashMap() }
            val ps = studioBucket.getOrPut(pId) { PlayerSource(pId, pName) }

            val epIdxInPlayer = ps.episodes.size + 1
            val numMatcher = NUMBER_PATTERN.matcher(epTitle)
            val epNum = if (numMatcher.find()) numMatcher.group(1) ?: "$epIdxInPlayer" else "$epIdxInPlayer"

            val episode = EpisodeItem(
                id = "dle_${grouped.size}_${pId}_$epIdxInPlayer",
                number = epNum,
                title = epTitle,
                season = seasonNum,
                iframeUrl = iframeUrl
            )
            ps.episodes.add(episode)
        }

        val result = mutableListOf<DubStudio>()
        var sIdx = 0
        for ((studioName, playersMap) in grouped) {
            val studio = DubStudio("studio_$sIdx", studioName)
            studio.players.addAll(playersMap.values)
            result.add(studio)
            sIdx++
        }

        return result
    }

    fun normalizeUrl(url: String, baseOrigin: String?): String {
        val u = url.trim()
        if (u.startsWith("//")) return "https:$u"
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            if (!baseOrigin.isNullOrEmpty()) {
                try {
                    return URI(baseOrigin).resolve(u).toString()
                } catch (ignored: Exception) {}
            }
            return "https://$u"
        }
        return u
    }
}
