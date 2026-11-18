package ua.aniua.downloader.extractor

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import java.util.Locale
import java.util.regex.Pattern

object InstagramExtractor {

    private const val IG_APP_ID = "936619743392459"

    private val IG_SHORTCODE_PATTERN = Pattern.compile(
        "(?:instagram\\.com|instagr\\.am)/(?:[^/]+/)?(?:p|reel|tv|reels)/([a-zA-Z0-9_-]+)",
        Pattern.CASE_INSENSITIVE
    )
    private val EMBED_VIDEO_JSON_PATTERNS = listOf(
        Pattern.compile("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\""),
        Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\""),
        Pattern.compile("\"playable_url\"\\s*:\\s*\"([^\"]+)\""),
        Pattern.compile("\"playback_url\"\\s*:\\s*\"([^\"]+)\""),
        Pattern.compile("\"video_versions\"\\s*:\\s*\\[\\s*\\{[^{}]*\"url\"\\s*:\\s*\"([^\"]+)\"")
    )
    private val LSD_TOKEN_PATTERN = Pattern.compile("\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"")

    fun isInstagramUrl(url: String): Boolean {
        val clean = url.trim().lowercase(Locale.ROOT)
        return clean.contains("instagram.com") || clean.contains("instagr.am")
    }

    fun extractShortcode(url: String): String? {
        val clean = url.trim()
        val matcher = IG_SHORTCODE_PATTERN.matcher(clean)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun parseVideo(client: OkHttpClient, pageUrl: String, preloadedHtml: String? = null): MediaInfo {
        val rawUrl = pageUrl.trim()
        val canonicalUrl = resolveCanonicalInstagramUrl(client, rawUrl)
        val shortcode = extractShortcode(canonicalUrl) ?: extractShortcode(rawUrl)

        if (shortcode != null) {
            // 1. Instagram Web API з X-IG-App-ID
            val apiResult = fetchFromInstagramApi(client, shortcode, canonicalUrl)
            if (apiResult != null) return apiResult

            // 2. Публічні сторінки /p/{sc}/embed/captioned/ та /reel/{sc}/embed/ (+ GraphQL з LSD/CSRF)
            val embedResult = fetchFromEmbedOrGraphQl(client, shortcode, canonicalUrl)
            if (embedResult != null) return embedResult
        }

        // 3. Якщо сторінку завантажено через вбудований браузер (WebView)
        if (!preloadedHtml.isNullOrBlank()) {
            val domResult = parseEmbedHtml(preloadedHtml, shortcode ?: "web", canonicalUrl, canonicalUrl)
            if (domResult != null) return domResult
        }

        val effectiveId = shortcode ?: "video"
        // 4. Fallback MediaInfo (дозволяє перехоплення потоку в WebView, ігноруючи обкладинки)
        return buildFallbackMediaInfo(effectiveId, canonicalUrl)
    }

    private fun resolveCanonicalInstagramUrl(client: OkHttpClient, url: String): String {
        if (!url.contains("/share/", ignoreCase = true) && extractShortcode(url) != null) {
            return url
        }
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (extractShortcode(finalUrl) != null) finalUrl else url
            }
        } catch (_: Exception) {
            url
        }
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
            parseInstagramJson(body, shortcode, pageUrl)
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseInstagramJson(body: String, shortcode: String, pageUrl: String): MediaInfo? {
        return try {
            val json = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val items = json.arrOrNull("items")
            val mediaObj = if (items != null && items.size() > 0) {
                items[0].takeIf { it.isJsonObject }?.asJsonObject
            } else {
                json.objOrNull("graphql")?.objOrNull("shortcode_media")
                    ?: json.objOrNull("data")?.objOrNull("xdt_shortcode_media")
            } ?: return null

            val hasIsVideo = mediaObj.has("is_video")
            val mediaType = mediaObj.intOrDefault("media_type", if (hasIsVideo && mediaObj.boolOrFalse("is_video")) 2 else 1)
            val isVideo = (hasIsVideo && mediaObj.boolOrFalse("is_video")) || mediaType == 2
            if (!isVideo && mediaObj.arrOrNull("video_versions") == null && mediaObj.strOrEmpty("video_url").isEmpty()) {
                return null
            }

            var videoUrl = mediaObj.strOrEmpty("video_url")
            if (videoUrl.isEmpty()) {
                val videoVersions = mediaObj.arrOrNull("video_versions")
                if (videoVersions != null && videoVersions.size() > 0) {
                    videoUrl = videoVersions[0].takeIf { it.isJsonObject }?.asJsonObject?.strOrEmpty("url").orEmpty()
                }
            }
            if (videoUrl.isEmpty() || ExtractorUtils.isImageUrl(videoUrl)) return null

            var caption = mediaObj.objOrNull("caption")?.strOrEmpty("text").orEmpty()
            if (caption.isEmpty()) {
                val edges = mediaObj.objOrNull("edge_media_to_caption")?.arrOrNull("edges")
                if (edges != null && edges.size() > 0) {
                    caption = edges[0].takeIf { it.isJsonObject }?.asJsonObject
                        ?.objOrNull("node")?.strOrEmpty("text").orEmpty()
                }
            }

            val userObj = mediaObj.objOrNull("user") ?: mediaObj.objOrNull("owner")
            val username = userObj?.strOrEmpty("username").orEmpty()
            val title = if (caption.isNotEmpty()) {
                val firstLine = caption.lines().firstOrNull()?.trim() ?: "Instagram Відео"
                if (firstLine.length > 80) firstLine.take(80) + "..." else firstLine
            } else {
                "Instagram Reel"
            }

            val candidates = mediaObj.objOrNull("image_versions2")?.arrOrNull("candidates")
            val coverUrl = if (candidates != null && candidates.size() > 0) {
                candidates[0].takeIf { it.isJsonObject }?.asJsonObject?.strOrEmpty("url").orEmpty()
            } else {
                mediaObj.strOrEmpty("display_url")
            }

            val finalTitle = if (username.isNotEmpty()) "$title (@$username)" else title
            val studio = DubStudio("instagram", "Оригінал")
            val player = PlayerSource("instagram_player", "Instagram")
            player.episodes.add(EpisodeItem("ig_$shortcode", "1", finalTitle, "1", videoUrl))
            studio.players.add(player)

            MediaInfo(
                url = pageUrl,
                siteType = "instagram",
                title = finalTitle,
                originalTitle = username.ifEmpty { null },
                posterUrl = coverUrl.ifEmpty { null },
                description = caption.ifEmpty { null },
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = mutableListOf("1080p (MP4)", "Original")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromEmbedOrGraphQl(client: OkHttpClient, shortcode: String, pageUrl: String): MediaInfo? {
        val embedUrls = listOf(
            "https://www.instagram.com/p/$shortcode/embed/captioned/",
            "https://www.instagram.com/reel/$shortcode/embed/"
        )

        var lastLsd: String? = null
        for (embedUrl in embedUrls) {
            try {
                val req = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Referer", "https://www.instagram.com/")
                    .build()

                val html = ExtractorUtils.executeForString(client, req) ?: continue
                val parsed = parseEmbedHtml(html, shortcode, embedUrl, pageUrl)
                if (parsed != null) return parsed

                if (lastLsd == null) {
                    val lsdM = LSD_TOKEN_PATTERN.matcher(html)
                    if (lsdM.find()) {
                        lastLsd = lsdM.group(1)
                    }
                }
            } catch (_: Exception) {}
        }

        // GraphQL fallback with LSD token
        val lsd = lastLsd ?: "AVqbxe3J_YA"
        return fetchFromGraphQl(client, shortcode, pageUrl, lsd)
    }

    private fun fetchFromGraphQl(
        client: OkHttpClient,
        shortcode: String,
        pageUrl: String,
        lsd: String
    ): MediaInfo? {
        return try {
            val variablesJson = JsonObject().apply { addProperty("shortcode", shortcode) }.toString()
            val form = FormBody.Builder()
                .add("variables", variablesJson)
                .add("doc_id", "8845758582119845")
                .add("lsd", lsd)
                .build()
            val req = Request.Builder()
                .url("https://www.instagram.com/graphql/query")
                .post(form)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("X-FB-LSD", lsd)
                .header("Referer", "https://www.instagram.com/p/$shortcode/")
                .build()
            val body = ExtractorUtils.executeForString(client, req) ?: return null
            parseInstagramJson(body, shortcode, pageUrl)
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseEmbedHtml(
        html: String,
        shortcode: String,
        baseUrl: String,
        pageUrl: String
    ): MediaInfo? {
        val doc = Jsoup.parse(html, baseUrl)

        var videoSrc = doc.selectFirst("video.EmbeddedVideo, video[src], video source[src], video[data-src]")
            ?.let { el ->
                el.attr("src").trim().ifEmpty { el.attr("data-src").trim() }
            }
            .orEmpty()
            .let { unescapeIgUrl(it) }
            .takeIf { it.startsWith("http") && !ExtractorUtils.isImageUrl(it) }
            .orEmpty()

        if (videoSrc.isEmpty()) {
            for (pattern in EMBED_VIDEO_JSON_PATTERNS) {
                val m = pattern.matcher(html)
                while (m.find()) {
                    val candidate = unescapeIgUrl(m.group(1).orEmpty())
                    if (candidate.startsWith("http") && !ExtractorUtils.isImageUrl(candidate)) {
                        videoSrc = candidate
                        break
                    }
                }
                if (videoSrc.isNotEmpty()) break
            }
        }

        if (videoSrc.isEmpty()) return null

        val captionEl = doc.selectFirst(".Caption, .CaptionComments, meta[property='og:title']")
        val rawCaption = if (captionEl?.tagName() == "meta") {
            captionEl.attr("content").trim()
        } else {
            captionEl?.text()?.trim().orEmpty()
        }
        val caption = rawCaption.ifEmpty { "Instagram Reel" }
        val username = doc.selectFirst(".UsernameText, .Avatar")?.text()?.trim().orEmpty()

        val firstLine = caption.lines().firstOrNull()?.trim() ?: "Instagram Відео"
        val title = if (firstLine.length > 80) firstLine.take(80) + "..." else firstLine

        val poster = doc.selectFirst("img.EmbeddedMediaImage, meta[property='og:image']")
            ?.let { el ->
                if (el.tagName() == "meta") el.attr("content").trim() else el.attr("src").trim()
            }
            ?.let { unescapeIgUrl(it) }
            ?.takeIf { it.startsWith("http") }

        val finalTitle = if (username.isNotEmpty()) "$title (@$username)" else title
        val studio = DubStudio("instagram", "Оригінал")
        val player = PlayerSource("instagram_player", "Instagram")
        player.episodes.add(EpisodeItem("ig_$shortcode", "1", finalTitle, "1", videoSrc))
        studio.players.add(player)

        return MediaInfo(
            url = pageUrl,
            siteType = "instagram",
            title = finalTitle,
            originalTitle = username.ifEmpty { null },
            posterUrl = poster,
            description = caption,
            isSeries = false,
            studios = mutableListOf(studio),
            availableQualities = mutableListOf("1080p (MP4)")
        )
    }

    private fun unescapeIgUrl(raw: String): String {
        return GenericPlayerResolver.normalizeEscapedUrls(raw)
            .replace("\\u0025", "%")
            .replace("\\u003D", "=")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
            .trim()
    }

    private fun buildFallbackMediaInfo(shortcode: String, pageUrl: String): MediaInfo {
        val studio = DubStudio("instagram", "Оригінал")
        val player = PlayerSource("instagram_player", "Instagram")
        player.episodes.add(EpisodeItem("ig_fallback_$shortcode", "1", "Instagram Відео", "1", pageUrl))
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

    private fun JsonObject.strOrEmpty(key: String): String =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString.trim() else ""

    private fun JsonObject.boolOrFalse(key: String): Boolean =
        try {
            has(key) && get(key).isJsonPrimitive && get(key).asBoolean
        } catch (_: Exception) {
            false
        }

    private fun JsonObject.intOrDefault(key: String, default: Int): Int =
        try {
            if (has(key) && get(key).isJsonPrimitive) get(key).asInt else default
        } catch (_: Exception) {
            default
        }

    private fun JsonObject.objOrNull(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else null

    private fun JsonObject.arrOrNull(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) getAsJsonArray(key) else null
}
