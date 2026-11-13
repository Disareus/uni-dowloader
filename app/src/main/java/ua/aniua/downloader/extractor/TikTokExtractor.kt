package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import java.net.URLEncoder
import java.util.regex.Pattern

object TikTokExtractor {

    private val TIKTOK_URL_PATTERN = Pattern.compile(
        "(?:https?://)?(?:www\\.|m\\.|vm\\.|vt\\.)?tiktok\\.com/(?:@[a-zA-Z0-9_.-]+/video/\\d+|[a-zA-Z0-9_-]+|t/[a-zA-Z0-9_-]+)",
        Pattern.CASE_INSENSITIVE
    )

    fun isTikTokUrl(url: String): Boolean {
        val clean = url.trim()
        return clean.contains("tiktok.com") || TIKTOK_URL_PATTERN.matcher(clean).find()
    }

    fun parseVideo(client: OkHttpClient, pageUrl: String): MediaInfo {
        val cleanUrl = pageUrl.trim()

        // 1. Спроба вилучення через TikWM API (HD без водяного знака)
        val tikWmResult = fetchFromTikWm(client, cleanUrl)
        if (tikWmResult != null) {
            return tikWmResult
        }

        // 2. Спроба через офіційний TikTok oEmbed API
        val oEmbedResult = fetchFromOEmbed(client, cleanUrl)
        if (oEmbedResult != null) {
            return oEmbedResult
        }

        // 3. Fallback MediaInfo
        return buildFallbackMediaInfo(cleanUrl)
    }

    private fun fetchFromTikWm(client: OkHttpClient, url: String): MediaInfo? {
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"

            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Accept", "application/json")
                .build()

            val body = ExtractorUtils.executeForString(client, req) ?: return null
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) return null

            val data = json.optJSONObject("data") ?: return null

            val title = data.optString("title", "TikTok Відео").ifEmpty { "TikTok Відео" }
            val cover = data.optString("cover", "")
            val playUrl = data.optString("hdplay", "").ifEmpty { data.optString("play", "") }
            val authorObj = data.optJSONObject("author")
            val author = authorObj?.optString("nickname", "")?.ifEmpty { authorObj.optString("unique_id", "") } ?: ""

            if (playUrl.isEmpty()) return null

            val finalTitle = if (author.isNotEmpty()) "$title (@$author)" else title

            val studio = DubStudio("tiktok", "Оригінал")
            val player = PlayerSource("tiktok_player", "TikTok")
            player.episodes.add(
                EpisodeItem(
                    id = "tt_${data.optString("id", "video")}",
                    number = "1",
                    title = finalTitle,
                    season = "1",
                    iframeUrl = playUrl
                )
            )
            studio.players.add(player)

            MediaInfo(
                url = url,
                siteType = "tiktok",
                title = finalTitle,
                originalTitle = author,
                posterUrl = cover,
                description = title,
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = mutableListOf("HD (No WM)", "Original")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFromOEmbed(client: OkHttpClient, url: String): MediaInfo? {
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val oEmbedUrl = "https://www.tiktok.com/oembed?url=$encoded"

            val req = Request.Builder()
                .url(oEmbedUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .build()

            val body = ExtractorUtils.executeForString(client, req) ?: return null
            val json = JSONObject(body)
            val title = json.optString("title", "TikTok Відео")
            val author = json.optString("author_name", "")
            val thumbnail = json.optString("thumbnail_url", "")

            val studio = DubStudio("tiktok", "Оригінал")
            val player = PlayerSource("tiktok_player", "TikTok")
            player.episodes.add(EpisodeItem("tt_oembed", "1", title, "1", url))
            studio.players.add(player)

            MediaInfo(
                url = url,
                siteType = "tiktok",
                title = if (author.isNotEmpty()) "$title (@$author)" else title,
                originalTitle = author,
                posterUrl = thumbnail,
                description = title,
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = mutableListOf("720p (MP4)")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildFallbackMediaInfo(url: String): MediaInfo {
        val studio = DubStudio("tiktok", "Оригінал")
        val player = PlayerSource("tiktok_player", "TikTok")
        player.episodes.add(EpisodeItem("tt_fallback", "1", "TikTok Відео", "1", url))
        studio.players.add(player)

        return MediaInfo(
            url = url,
            siteType = "tiktok",
            title = "TikTok Відео",
            isSeries = false,
            studios = mutableListOf(studio),
            availableQualities = mutableListOf("720p")
        )
    }
}

