package ua.aniua.downloader.extractor

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
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

object TikTokExtractor {

    private val TIKTOK_URL_PATTERN = Pattern.compile(
        "(?:https?://)?(?:www\\.|m\\.|vm\\.|vt\\.)?tiktok\\.com/(?:@[a-zA-Z0-9_.-]+/video/\\d+|[a-zA-Z0-9_-]+|t/[a-zA-Z0-9_-]+)",
        Pattern.CASE_INSENSITIVE
    )
    private val PLAY_ADDR_JSON_PATTERN = Pattern.compile(
        "\"playAddr\"\\s*:\\s*\"([^\"]+)\""
    )
    private val PLAY_ADDR_STRUCT_PATTERN = Pattern.compile(
        "\"PlayAddr\"\\s*:\\s*\\{[^{}]*\"UrlList\"\\s*:\\s*\\[\\s*\"([^\"]+)\""
    )

    fun isTikTokUrl(url: String): Boolean {
        val clean = url.trim()
        return clean.contains("tiktok.com", ignoreCase = true) || TIKTOK_URL_PATTERN.matcher(clean).find()
    }

    fun parseVideo(client: OkHttpClient, pageUrl: String, preloadedHtml: String? = null): MediaInfo {
        val rawUrl = pageUrl.trim()
        val canonicalUrl = resolveCanonicalTikTokUrl(client, rawUrl)

        // 1. TikMate API (пряме завантаження HD / MP4 без водяного знаку)
        val tikMateResult = fetchFromTikMate(client, canonicalUrl)
            ?: if (canonicalUrl != rawUrl) fetchFromTikMate(client, rawUrl) else null
        if (tikMateResult != null) {
            return tikMateResult
        }

        // 2. TikWM API (HD / MP4 без водяного знаку)
        val tikWmResult = fetchFromTikWm(client, canonicalUrl)
            ?: if (canonicalUrl != rawUrl) fetchFromTikWm(client, rawUrl) else null
        if (tikWmResult != null) {
            return tikWmResult
        }

        // 3. Якщо сторінка вже завантажена через WebView — шукаємо чистий playAddr без водяного знаку
        if (!preloadedHtml.isNullOrBlank()) {
            val domResult = parseFromPreloadedHtml(preloadedHtml, canonicalUrl)
            if (domResult != null) {
                return domResult
            }
        }

        // 4. Офіційний TikTok oEmbed API (метадані; потік буде перехоплено через WebView без обкладинки)
        val oEmbedResult = fetchFromOEmbed(client, canonicalUrl)
        if (oEmbedResult != null) {
            return oEmbedResult
        }

        // 5. Fallback MediaInfo
        return buildFallbackMediaInfo(canonicalUrl)
    }

    private fun resolveCanonicalTikTokUrl(client: OkHttpClient, url: String): String {
        val lower = url.lowercase(Locale.ROOT)
        val needsResolve = lower.contains("vm.tiktok.com") ||
            lower.contains("vt.tiktok.com") ||
            lower.contains("tiktok.com/t/")
        if (!needsResolve) return url

        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (finalUrl.contains("/video/")) finalUrl else url
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun fetchFromTikMate(client: OkHttpClient, url: String): MediaInfo? {
        return try {
            val form = FormBody.Builder()
                .add("url", url)
                .build()
            val req = Request.Builder()
                .url("https://api.tikmate.app/api/lookup")
                .post(form)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Accept", "application/json")
                .header("Origin", "https://tikmate.app")
                .header("Referer", "https://tikmate.app/")
                .build()

            val body = ExtractorUtils.executeForString(client, req) ?: return null
            val info = parseTikMateResponse(body, url) ?: return null

            val players = info.studios.firstOrNull()?.players ?: return info
            if (players.size > 1) {
                val primaryUrl = players[0].episodes.firstOrNull()?.iframeUrl
                val secondaryUrl = players[1].episodes.firstOrNull()?.iframeUrl
                if (!primaryUrl.isNullOrEmpty() && !secondaryUrl.isNullOrEmpty() &&
                    !isVideoStreamReachable(client, primaryUrl) &&
                    isVideoStreamReachable(client, secondaryUrl)
                ) {
                    val fallback = players.removeAt(0)
                    players.add(fallback)
                }
            }
            info
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseTikMateResponse(body: String, pageUrl: String): MediaInfo? {
        return try {
            val json = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (!json.boolOrFalse("success")) return null

            val videoId = json.strOrEmpty("id")
            if (videoId.isEmpty()) return null

            val hdToken = json.strOrEmpty("hd_token")
            val token = json.strOrEmpty("token")

            val hdUrl = if (hdToken.isNotEmpty()) {
                "https://tikmate.app/download/$hdToken/$videoId.mp4?hd=1"
            } else {
                ""
            }
            val sdUrl = if (token.isNotEmpty()) {
                "https://tikmate.app/download/$token/$videoId.mp4"
            } else {
                ""
            }
            if (hdUrl.isEmpty() && sdUrl.isEmpty()) return null

            val desc = json.strOrEmpty("desc").ifEmpty { "TikTok Відео" }
            val authorName = json.strOrEmpty("author_name")
                .ifEmpty { json.strOrEmpty("author_id") }
            val cover = json.strOrEmpty("cover")
            val finalTitle = if (authorName.isNotEmpty()) "$desc (@$authorName)" else desc

            val players = mutableListOf<PlayerSource>()
            if (hdUrl.isNotEmpty()) {
                val pHd = PlayerSource("tikmate_hd", "TikMate HD (Без водяного знаку)")
                pHd.episodes.add(EpisodeItem("tt_hd_$videoId", "1", finalTitle, "1", hdUrl))
                players.add(pHd)
            }
            if (sdUrl.isNotEmpty()) {
                val pSd = PlayerSource("tikmate_sd", "TikMate MP4 (Без водяного знаку)")
                pSd.episodes.add(EpisodeItem("tt_sd_$videoId", "1", finalTitle, "1", sdUrl))
                players.add(pSd)
            }

            val qualities = if (hdUrl.isNotEmpty()) {
                mutableListOf("1080p (HD без водяного знаку)", "720p (Без водяного знаку)")
            } else {
                mutableListOf("720p (Без водяного знаку)")
            }

            MediaInfo(
                url = pageUrl,
                siteType = "tiktok",
                title = finalTitle,
                originalTitle = authorName.ifEmpty { null },
                posterUrl = cover.ifEmpty { null },
                description = desc,
                isSeries = false,
                studios = mutableListOf(DubStudio("tiktok", "Оригінал", players)),
                availableQualities = qualities
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isVideoStreamReachable(client: OkHttpClient, url: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val contentType = resp.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
                !contentType.startsWith("image/") && !contentType.startsWith("text/")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchFromTikWm(client: OkHttpClient, url: String): MediaInfo? {
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val apiUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1"

            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Accept", "application/json")
                .header("Origin", "https://www.tikwm.com")
                .header("Referer", "https://www.tikwm.com/")
                .build()

            val body = ExtractorUtils.executeForString(client, req) ?: return null
            parseTikWmResponse(body, url)
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseTikWmResponse(body: String, pageUrl: String): MediaInfo? {
        return try {
            val json = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (json.intOrDefault("code", -1) != 0) return null

            val data = json.objOrNull("data") ?: return null
            val videoId = data.strOrEmpty("id").ifEmpty { "video" }
            val title = data.strOrEmpty("title").ifEmpty { "TikTok Відео" }
            val rawCover = data.strOrEmpty("cover")
            val cover = if (rawCover.isNotEmpty()) {
                DlePlaylistParser.normalizeUrl(rawCover, "https://www.tikwm.com")
            } else {
                ""
            }

            val rawHdPlay = data.strOrEmpty("hdplay")
            val rawPlay = data.strOrEmpty("play")

            val hdUrl = if (rawHdPlay.isNotEmpty()) {
                DlePlaylistParser.normalizeUrl(rawHdPlay, "https://www.tikwm.com")
            } else {
                ""
            }.takeIf { it.isNotEmpty() && !ExtractorUtils.isImageUrl(it) }.orEmpty()

            val playUrl = if (rawPlay.isNotEmpty()) {
                DlePlaylistParser.normalizeUrl(rawPlay, "https://www.tikwm.com")
            } else {
                ""
            }.takeIf { it.isNotEmpty() && !ExtractorUtils.isImageUrl(it) }.orEmpty()

            if (hdUrl.isEmpty() && playUrl.isEmpty()) return null

            val authorObj = data.objOrNull("author")
            val author = authorObj?.strOrEmpty("nickname")
                ?.ifEmpty { authorObj.strOrEmpty("unique_id") }
                ?: ""

            val finalTitle = if (author.isNotEmpty()) "$title (@$author)" else title

            val players = mutableListOf<PlayerSource>()
            if (hdUrl.isNotEmpty()) {
                val pHd = PlayerSource("tikwm_hd", "TikWM HD (Без водяного знаку)")
                pHd.episodes.add(EpisodeItem("tt_wm_hd_$videoId", "1", finalTitle, "1", hdUrl))
                players.add(pHd)
            }
            if (playUrl.isNotEmpty() && playUrl != hdUrl) {
                val pSd = PlayerSource("tikwm_sd", "TikWM MP4 (Без водяного знаку)")
                pSd.episodes.add(EpisodeItem("tt_wm_sd_$videoId", "1", finalTitle, "1", playUrl))
                players.add(pSd)
            }

            val qualities = if (hdUrl.isNotEmpty()) {
                mutableListOf("1080p (HD без водяного знаку)", "720p (Без водяного знаку)")
            } else {
                mutableListOf("720p (Без водяного знаку)")
            }

            MediaInfo(
                url = pageUrl,
                siteType = "tiktok",
                title = finalTitle,
                originalTitle = author.ifEmpty { null },
                posterUrl = cover.ifEmpty { null },
                description = title,
                isSeries = false,
                studios = mutableListOf(DubStudio("tiktok", "Оригінал", players)),
                availableQualities = qualities
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseFromPreloadedHtml(html: String, pageUrl: String): MediaInfo? {
        val normalized = GenericPlayerResolver.normalizeEscapedUrls(html)
        val candidates = linkedSetOf<String>()

        val m1 = PLAY_ADDR_JSON_PATTERN.matcher(normalized)
        while (m1.find()) {
            val raw = m1.group(1)?.trim() ?: continue
            if (raw.startsWith("http") && !ExtractorUtils.isImageUrl(raw)) {
                candidates.add(raw)
            }
        }
        val m2 = PLAY_ADDR_STRUCT_PATTERN.matcher(normalized)
        while (m2.find()) {
            val raw = m2.group(1)?.trim() ?: continue
            if (raw.startsWith("http") && !ExtractorUtils.isImageUrl(raw)) {
                candidates.add(raw)
            }
        }

        val doc = Jsoup.parse(html, pageUrl)
        for (v in doc.select("video[src], video source[src]")) {
            val src = v.attr("src").trim()
            if (src.startsWith("http") && !ExtractorUtils.isImageUrl(src)) {
                candidates.add(src)
            }
        }

        val streamUrl = candidates.firstOrNull() ?: return null
        val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
        val pageTitle = doc.title().trim()
        val title = listOfNotNull(ogTitle, pageTitle)
            .firstOrNull { it.isNotEmpty() && !it.equals("TikTok", ignoreCase = true) }
            ?: "TikTok Відео"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
            ?.takeIf { it.startsWith("http") }

        val studio = DubStudio("tiktok", "Оригінал")
        val player = PlayerSource("tiktok_web", "TikTok (Без водяного знаку)")
        player.episodes.add(EpisodeItem("tt_web", "1", title, "1", streamUrl))
        studio.players.add(player)

        return MediaInfo(
            url = pageUrl,
            siteType = "tiktok",
            title = title,
            posterUrl = poster,
            description = title,
            isSeries = false,
            studios = mutableListOf(studio),
            availableQualities = mutableListOf("720p (Без водяного знаку)")
        )
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
            val json = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val title = json.strOrEmpty("title").ifEmpty { "TikTok Відео" }
            val author = json.strOrEmpty("author_name")
            val thumbnail = json.strOrEmpty("thumbnail_url")

            val studio = DubStudio("tiktok", "Оригінал")
            val player = PlayerSource("tiktok_player", "TikTok")
            player.episodes.add(EpisodeItem("tt_oembed", "1", title, "1", url))
            studio.players.add(player)

            MediaInfo(
                url = url,
                siteType = "tiktok",
                title = if (author.isNotEmpty()) "$title (@$author)" else title,
                originalTitle = author.ifEmpty { null },
                posterUrl = thumbnail.ifEmpty { null },
                description = title,
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = mutableListOf("720p (Без водяного знаку)")
            )
        } catch (_: Exception) {
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
            availableQualities = mutableListOf("720p (Без водяного знаку)")
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
}
