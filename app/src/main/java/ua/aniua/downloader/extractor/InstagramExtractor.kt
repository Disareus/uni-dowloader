package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import java.util.regex.Pattern

object InstagramExtractor {

    private const val IG_APP_ID = "936619743392459"

    private val IG_SHORTCODE_PATTERN = Pattern.compile(
        "(?:instagram\\.com|instagr\\.am)/(?:p|reel|tv|reels)/([a-zA-Z0-9_-]+)",
        Pattern.CASE_INSENSITIVE
    )

    fun isInstagramUrl(url: String): Boolean {
        val clean = url.trim()
        return clean.contains("instagram.com") || clean.contains("instagr.am")
    }

    fun extractShortcode(url: String): String? {
        val clean = url.trim()
        val matcher = IG_SHORTCODE_PATTERN.matcher(clean)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun parseVideo(client: OkHttpClient, pageUrl: String): MediaInfo {
        val shortcode = extractShortcode(pageUrl)
            ?: throw IllegalArgumentException("Не вдалося визначити код публікації Instagram: $pageUrl")

        // 1. Спроба через Instagram Web API з X-IG-App-ID
        val apiResult = fetchFromInstagramApi(client, shortcode, pageUrl)
        if (apiResult != null) {
            return apiResult
        }

        // 2. Спроба через публічну сторінку /embed/captioned/
        val embedResult = fetchFromEmbedPage(client, shortcode, pageUrl)
        if (embedResult != null) {
            return embedResult
        }

        // 3. Fallback MediaInfo
        return buildFallbackMediaInfo(shortcode, pageUrl)
    }

    private fun fetchFromInstagramApi(client: OkHttpClient, shortcode: String, pageUrl: String): MediaInfo? {
        return try {
            val apiUrl = "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis"

            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("Accept", "*/*")
                .header("Referer", "https://www.instagram.com/")
                .build()

            val body = ExtractorUtils.executeForString(client, req) ?: return null
            val json = JSONObject(body)
            val items = json.optJSONArray("items")
            val mediaObj = if (items != null && items.length() > 0) {
                items.getJSONObject(0)
            } else {
                json.optJSONObject("graphql")?.optJSONObject("shortcode_media")
            } ?: return null

            val isVideo = mediaObj.optBoolean("is_video", true) || mediaObj.optInt("media_type", 1) == 2
            if (!isVideo) return null

            var videoUrl = mediaObj.optString("video_url", "")
            if (videoUrl.isEmpty()) {
                val videoVersions = mediaObj.optJSONArray("video_versions")
                if (videoVersions != null && videoVersions.length() > 0) {
                    videoUrl = videoVersions.getJSONObject(0).optString("url", "")
                }
            }

            if (videoUrl.isEmpty()) return null

            var caption = ""
            val captionObj = mediaObj.optJSONObject("caption")
            if (captionObj != null) {
                caption = captionObj.optString("text", "")
            } else {
                val edgeCaption = mediaObj.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")
                if (edgeCaption != null && edgeCaption.length() > 0) {
                    caption = edgeCaption.getJSONObject(0).optJSONObject("node")?.optString("text", "") ?: ""
                }
            }

            val userObj = mediaObj.optJSONObject("user") ?: mediaObj.optJSONObject("owner")
            val username = userObj?.optString("username", "") ?: ""
            val title = if (caption.isNotEmpty()) {
                val firstLine = caption.lines().firstOrNull()?.trim() ?: "Instagram Відео"
                if (firstLine.length > 80) firstLine.take(80) + "..." else firstLine
            } else {
                "Instagram Reel"
            }

            val imageVersions = mediaObj.optJSONObject("image_versions2")?.optJSONArray("candidates")
            val coverUrl = if (imageVersions != null && imageVersions.length() > 0) {
                imageVersions.getJSONObject(0).optString("url", "")
            } else {
                mediaObj.optString("display_url", "")
            }

            val finalTitle = if (username.isNotEmpty()) "$title (@$username)" else title

            val studio = DubStudio("instagram", "Оригінал")
            val player = PlayerSource("instagram_player", "Instagram")
            player.episodes.add(
                EpisodeItem(
                    id = "ig_$shortcode",
                    number = "1",
                    title = finalTitle,
                    season = "1",
                    iframeUrl = videoUrl
                )
            )
            studio.players.add(player)

            MediaInfo(
                url = pageUrl,
                siteType = "instagram",
                title = finalTitle,
                originalTitle = username,
                posterUrl = coverUrl,
                description = caption.ifEmpty { null },
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = mutableListOf("1080p (MP4)", "Original")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFromEmbedPage(client: OkHttpClient, shortcode: String, pageUrl: String): MediaInfo? {
        return try {
            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"

            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val html = ExtractorUtils.executeForString(client, req) ?: return null
            val doc = Jsoup.parse(html, embedUrl)

            // Пошук прямого тегу video в embed розмітці
            var videoSrc = doc.selectFirst("video.EmbeddedVideo, video[src]")?.attr("src")?.trim() ?: ""

            // Якщо немає в тегу, шукаємо у вбудованому JS
            if (videoSrc.isEmpty()) {
                val pattern = Pattern.compile("video_url[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
                val m = pattern.matcher(html)
                if (m.find()) {
                    videoSrc = m.group(1)?.replace("\\u0026", "&") ?: ""
                }
            }

            if (videoSrc.isEmpty()) return null

            val captionEl = doc.selectFirst(".Caption, .CaptionComments")
            val caption = captionEl?.text()?.trim() ?: "Instagram Reel"
            val username = doc.selectFirst(".UsernameText, .Avatar")?.text()?.trim() ?: ""

            val title = if (caption.isNotEmpty()) {
                val firstLine = caption.lines().firstOrNull()?.trim() ?: "Instagram Відео"
                if (firstLine.length > 80) firstLine.take(80) + "..." else firstLine
            } else {
                "Instagram Reel"
            }

            val poster = doc.selectFirst("img.EmbeddedMediaImage, img[src]")?.attr("src")

            val finalTitle = if (username.isNotEmpty()) "$title (@$username)" else title

            val studio = DubStudio("instagram", "Оригінал")
            val player = PlayerSource("instagram_player", "Instagram")
            player.episodes.add(
                EpisodeItem(
                    id = "ig_$shortcode",
                    number = "1",
                    title = finalTitle,
                    season = "1",
                    iframeUrl = videoSrc
                )
            )
            studio.players.add(player)

            MediaInfo(
                url = pageUrl,
                siteType = "instagram",
                title = finalTitle,
                originalTitle = username,
                posterUrl = poster,
                description = caption,
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = mutableListOf("1080p (MP4)")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildFallbackMediaInfo(shortcode: String, pageUrl: String): MediaInfo {
        val studio = DubStudio("instagram", "Оригінал")
        val player = PlayerSource("instagram_player", "Instagram")
        player.episodes.add(EpisodeItem("ig_$shortcode", "1", "Instagram Відео", "1", pageUrl))
        studio.players.add(player)

        return MediaInfo(
            url = pageUrl,
            siteType = "instagram",
            title = "Instagram Відео",
            isSeries = false,
            studios = mutableListOf(studio),
            availableQualities = mutableListOf("1080p", "720p")
        )
    }
}
