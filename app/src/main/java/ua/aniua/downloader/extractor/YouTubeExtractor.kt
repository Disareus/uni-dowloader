package ua.aniua.downloader.extractor

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import ua.aniua.downloader.model.StreamQuality
import java.util.regex.Pattern

object YouTubeExtractor {

    private const val ANDROID_CLIENT_VERSION = "19.09.37"

    // Регулярні вирази для розпізнавання YouTube URL
    private val YOUTUBE_URL_PATTERNS = listOf(
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.|music\\.)?youtube\\.com/watch\\?.*v=([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.)?youtu\\.be/([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/live/([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/embed/([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/v/([a-zA-Z0-9_-]{11})")
    )

    fun isYouTubeUrl(url: String): Boolean {
        val clean = url.trim()
        return YOUTUBE_URL_PATTERNS.any { it.matcher(clean).find() }
    }

    fun extractVideoId(url: String): String? {
        val clean = url.trim()
        for (pattern in YOUTUBE_URL_PATTERNS) {
            val matcher = pattern.matcher(clean)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    fun parseVideo(client: OkHttpClient, pageUrl: String): MediaInfo {
        val videoId = extractVideoId(pageUrl)
            ?: throw IllegalArgumentException("Не вдалося розпізнати ID відео YouTube: $pageUrl")

        // 1. Спроба отримати відео через YouTube Innertube API (клієнт ANDROID)
        val innertubeResult = fetchInnertube(client, videoId, pageUrl)
        if (innertubeResult != null) {
            return innertubeResult
        }

        // 2. Резервний варіант через публічний Invidious/Piped API
        val pipedResult = fetchPipedFallback(client, videoId, pageUrl)
        if (pipedResult != null) {
            return pipedResult
        }

        // 3. Fallback: базовий MediaInfo
        return buildFallbackMediaInfo(videoId, pageUrl)
    }

    private fun fetchInnertube(client: OkHttpClient, videoId: String, pageUrl: String): MediaInfo? {
        return try {
            val endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

            val jsonBody = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", ANDROID_CLIENT_VERSION)
                        put("androidSdkVersion", 34)
                        put("hl", "uk")
                        put("gl", "UA")
                    })
                })
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", "com.google.android.youtube/$ANDROID_CLIENT_VERSION (Linux; U; Android 14; uk_UA)")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val respString = ExtractorUtils.executeForString(client, request) ?: return null
            val root = JSONObject(respString)

            val videoDetails = root.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title", "YouTube Відео") ?: "YouTube Відео"
            val author = videoDetails?.optString("author", "") ?: ""
            val description = if (videoDetails?.has("shortDescription") == true) videoDetails.optString("shortDescription") else null
            val thumbnail = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

            val streamingData = root.optJSONObject("streamingData") ?: return null

            val qualities = mutableListOf<StreamQuality>()

            // 1. Прямі комбіновані потоки (відео + аудіо разом)
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    val qLabel = f.optString("qualityLabel", "")
                    val height = f.optInt("height", 720)
                    val streamUrl = f.optString("url", "")
                    if (streamUrl.isNotEmpty()) {
                        val label = if (qLabel.isNotEmpty()) qLabel else "${height}p"
                        qualities.add(StreamQuality(label, height, streamUrl, false))
                    }
                }
            }

            // 2. HLS мастер-потік якщо є
            val hlsUrl = streamingData.optString("hlsManifestUrl", "")
            if (hlsUrl.isNotEmpty()) {
                qualities.add(0, StreamQuality("Auto (HLS)", 1080, hlsUrl, true))
            }

            // 3. Адаптивні потоки (відео високої якості 1080p+, якщо немає комбінованого)
            if (qualities.isEmpty()) {
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                if (adaptive != null) {
                    for (i in 0 until adaptive.length()) {
                        val f = adaptive.getJSONObject(i)
                        val mime = f.optString("mimeType", "")
                        if (mime.startsWith("video/")) {
                            val qLabel = f.optString("qualityLabel", "")
                            val height = f.optInt("height", 720)
                            val streamUrl = f.optString("url", "")
                            if (streamUrl.isNotEmpty()) {
                                val label = if (qLabel.isNotEmpty()) qLabel else "${height}p"
                                qualities.add(StreamQuality(label, height, streamUrl, false))
                            }
                        }
                    }
                }
            }

            if (qualities.isEmpty()) return null

            qualities.sortByDescending { it.height }
            val availableLabels = qualities.map { it.label }.distinct().toMutableList()
            val finalTitle = if (author.isNotEmpty()) "$title ($author)" else title

            val bestStreamUrl = qualities.first().url
            val episode = EpisodeItem(
                id = "yt_$videoId",
                number = "1",
                title = title,
                season = "1",
                iframeUrl = bestStreamUrl
            )

            val player = PlayerSource("yt_direct", "YouTube")
            player.episodes.add(episode)

            val studio = DubStudio("youtube", "Оригінал")
            studio.players.add(player)

            MediaInfo(
                url = pageUrl,
                siteType = "youtube",
                title = finalTitle,
                originalTitle = author,
                posterUrl = thumbnail,
                description = description,
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = availableLabels
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchPipedFallback(client: OkHttpClient, videoId: String, pageUrl: String): MediaInfo? {
        val instances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.privacy.com.de",
            "https://piped-api.lunar.icu"
        )

        for (host in instances) {
            try {
                val req = Request.Builder()
                    .url("$host/streams/$videoId")
                    .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                    .build()

                val body = ExtractorUtils.executeForString(client, req) ?: continue
                val json = JSONObject(body)
                val title = json.optString("title", "YouTube Відео")
                val uploader = json.optString("uploader", "")
                val desc = if (json.has("description")) json.optString("description") else null
                val thumb = json.optString("thumbnailUrl", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

                val videoStreams = json.optJSONArray("videoStreams")
                val qualities = mutableListOf<StreamQuality>()

                if (videoStreams != null) {
                    for (i in 0 until videoStreams.length()) {
                        val s = videoStreams.getJSONObject(i)
                        val q = s.optString("quality", "")
                        val sUrl = s.optString("url", "")
                        val videoOnly = s.optBoolean("videoOnly", false)
                        if (sUrl.isNotEmpty() && !videoOnly) {
                            val h = q.filter { it.isDigit() }.toIntOrNull() ?: 720
                            qualities.add(StreamQuality(q, h, sUrl, false))
                        }
                    }
                }

                val hlsUrl = json.optString("hls", "")
                if (hlsUrl.isNotEmpty()) {
                    qualities.add(0, StreamQuality("Auto (HLS)", 1080, hlsUrl, true))
                }

                if (qualities.isNotEmpty()) {
                    qualities.sortByDescending { it.height }
                    val availableLabels = qualities.map { it.label }.distinct().toMutableList()
                    val ep = EpisodeItem(
                        id = "yt_$videoId",
                        number = "1",
                        title = title,
                        season = "1",
                        iframeUrl = qualities.first().url
                    )

                    val player = PlayerSource("piped", "YouTube")
                    player.episodes.add(ep)
                    val studio = DubStudio("youtube", "Оригінал")
                    studio.players.add(player)

                    return MediaInfo(
                        url = pageUrl,
                        siteType = "youtube",
                        title = if (uploader.isNotEmpty()) "$title ($uploader)" else title,
                        originalTitle = uploader,
                        posterUrl = thumb,
                        description = desc,
                        isSeries = false,
                        studios = mutableListOf(studio),
                        availableQualities = availableLabels
                    )
                }
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun buildFallbackMediaInfo(videoId: String, pageUrl: String): MediaInfo {
        val studio = DubStudio("youtube", "Оригінал")
        val player = PlayerSource("youtube", "YouTube")
        player.episodes.add(EpisodeItem("yt_$videoId", "1", "YouTube Відео", "1", pageUrl))
        studio.players.add(player)

        return MediaInfo(
            url = pageUrl,
            siteType = "youtube",
            title = "YouTube Відео",
            posterUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
            isSeries = false,
            studios = mutableListOf(studio),
            availableQualities = mutableListOf("1080p", "720p", "480p", "360p")
        )
    }
}
