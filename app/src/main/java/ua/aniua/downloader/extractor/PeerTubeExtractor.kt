package ua.aniua.downloader.extractor

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import ua.aniua.downloader.model.StreamQuality
import java.net.URI
import java.util.regex.Pattern

object PeerTubeExtractor {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val JSON_LD_PATTERN = Pattern.compile("<script type=[\"']application/ld\\+json[\"']>([\\s\\S]*?)</script>")
    private val M3U8_PATTERN = Pattern.compile("(https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)")
    private val MP4_PATTERN = Pattern.compile("(https?://[^\\s\"'<>]+?\\.mp4[^\\s\"'<>]*)")

    fun isPeerTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("peertube") ||
                lower.contains("/videos/embed/") ||
                lower.contains("/videos/watch/") ||
                (lower.contains("/w/") && !lower.contains("wikipedia"))
    }

    fun extractVideoIdAndHost(url: String): Pair<String, String>? {
        return try {
            val uri = URI(url.trim())
            val host = "${uri.scheme ?: "https"}://${uri.host}"
            val path = uri.path ?: ""

            val videoId = when {
                path.contains("/videos/embed/") -> path.substringAfter("/videos/embed/").substringBefore("/").substringBefore("?")
                path.contains("/videos/watch/") -> path.substringAfter("/videos/watch/").substringBefore("/").substringBefore("?")
                path.contains("/w/") -> path.substringAfter("/w/").substringBefore("/").substringBefore("?")
                else -> path.trim('/').split('/').lastOrNull()?.substringBefore("?") ?: return null
            }

            if (videoId.isNotEmpty()) Pair(host, videoId) else null
        } catch (e: Exception) {
            null
        }
    }

    fun extractStreams(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        val streams = mutableListOf<StreamQuality>()
        val parsed = extractVideoIdAndHost(iframeUrl)

        // 1. Спроба отримати відеопотоки через офіційний PeerTube REST API (/api/v1/videos/{id})
        if (parsed != null) {
            val (host, videoId) = parsed
            val apiUrl = "$host/api/v1/videos/$videoId"

            try {
                val apiReq = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Referer", referer ?: iframeUrl)
                    .header("Accept", "application/json")
                    .build()

                val apiResp = client.newCall(apiReq).execute()
                if (apiResp.isSuccessful && apiResp.body != null) {
                    val bodyStr = apiResp.body?.string() ?: ""
                    val root = JsonParser.parseString(bodyStr).asJsonObject

                    // Парсимо HLS стріми (streamingPlaylists)
                    if (root.has("streamingPlaylists") && root.get("streamingPlaylists").isJsonArray) {
                        val playlists = root.getAsJsonArray("streamingPlaylists")
                        for (pElem in playlists) {
                            if (!pElem.isJsonObject) continue
                            val pObj = pElem.asJsonObject
                            val masterUrl = pObj.get("playlistUrl")?.asString ?: continue

                            val files = if (pObj.has("files") && pObj.get("files").isJsonArray) {
                                pObj.getAsJsonArray("files")
                            } else {
                                JsonArray()
                            }

                            if (files.size() > 0) {
                                for (fElem in files) {
                                    if (!fElem.isJsonObject) continue
                                    val fObj = fElem.asJsonObject
                                    val resObj = fObj.getAsJsonObject("resolution")
                                    val label = resObj?.get("label")?.asString ?: "Auto"
                                    val height = resObj?.get("id")?.asInt ?: 720
                                    if (height == 0) continue // Пропускаємо суто аудіодоріжку "Audio only"

                                    val sq = StreamQuality(label, height, masterUrl, true)
                                    sq.headers["Referer"] = iframeUrl
                                    sq.headers["Origin"] = host
                                    streams.add(sq)
                                }
                            } else {
                                val sq = StreamQuality("Auto (PeerTube HLS)", 720, masterUrl, true)
                                sq.headers["Referer"] = iframeUrl
                                sq.headers["Origin"] = host
                                streams.add(sq)
                            }
                        }
                    }

                    // Парсимо прямі MP4 веб-відео (files)
                    if (root.has("files") && root.get("files").isJsonArray) {
                        val directFiles = root.getAsJsonArray("files")
                        for (fElem in directFiles) {
                            if (!fElem.isJsonObject) continue
                            val fObj = fElem.asJsonObject
                            val resObj = fObj.getAsJsonObject("resolution")
                            val label = resObj?.get("label")?.asString ?: "MP4"
                            val height = resObj?.get("id")?.asInt ?: 720
                            if (height == 0) continue

                            val fileUrl = fObj.get("fileUrl")?.asString
                                ?: fObj.get("fileDownloadUrl")?.asString
                                ?: continue

                            val sq = StreamQuality("$label (MP4)", height, fileUrl, false)
                            sq.headers["Referer"] = iframeUrl
                            sq.headers["Origin"] = host
                            streams.add(sq)
                        }
                    }

                    if (streams.isNotEmpty()) {
                        streams.sortWith(compareByDescending { it.height })
                        return streams
                    }
                }
            } catch (ignored: Exception) {}
        }

        // 2. Fallback: розбір HTML-коду сторінки iframe/embed (JSON-LD або regex)
        try {
            val htmlReq = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Referer", referer ?: iframeUrl)
                .build()

            val htmlResp = client.newCall(htmlReq).execute()
            if (htmlResp.isSuccessful && htmlResp.body != null) {
                val html = htmlResp.body?.string() ?: ""

                // 2.1 Перевірка schema.org JSON-LD (contentUrl)
                val mJsonLd = JSON_LD_PATTERN.matcher(html)
                if (mJsonLd.find()) {
                    try {
                        val ldObj = JsonParser.parseString(mJsonLd.group(1)).asJsonObject
                        val contentUrl = ldObj.get("contentUrl")?.asString
                        if (!contentUrl.isNullOrEmpty()) {
                            val isHls = contentUrl.contains(".m3u8")
                            val sq = StreamQuality(if (isHls) "1080p (PeerTube HLS)" else "1080p (PeerTube MP4)", 1080, contentUrl, isHls)
                            sq.headers["Referer"] = iframeUrl
                            streams.add(sq)
                            return streams
                        }
                    } catch (ignored: Exception) {}
                }

                // 2.2 Прямий .m3u8
                val mM3u8 = M3U8_PATTERN.matcher(html)
                if (mM3u8.find()) {
                    val m3u8Url = mM3u8.group(1) ?: ""
                    val sq = StreamQuality("Auto (PeerTube HLS)", 720, m3u8Url, true)
                    sq.headers["Referer"] = iframeUrl
                    streams.add(sq)
                    return streams
                }

                // 2.3 Прямий .mp4
                val mMp4 = MP4_PATTERN.matcher(html)
                if (mMp4.find()) {
                    val mp4Url = mMp4.group(1) ?: ""
                    val sq = StreamQuality("720p (PeerTube MP4)", 720, mp4Url, false)
                    sq.headers["Referer"] = iframeUrl
                    streams.add(sq)
                    return streams
                }
            }
        } catch (ignored: Exception) {}

        return streams
    }

    fun parsePage(client: OkHttpClient, pageUrl: String): MediaInfo? {
        val parsed = extractVideoIdAndHost(pageUrl) ?: return null
        val (host, videoId) = parsed
        val apiUrl = "$host/api/v1/videos/$videoId"

        var title = "PeerTube Відео"
        var description: String? = null
        var posterUrl: String? = null

        try {
            val apiReq = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "application/json")
                .build()

            val apiResp = client.newCall(apiReq).execute()
            if (apiResp.isSuccessful && apiResp.body != null) {
                val root = JsonParser.parseString(apiResp.body?.string() ?: "").asJsonObject
                title = root.get("name")?.asString ?: title
                description = root.get("description")?.asString

                val previewPath = root.get("previewPath")?.asString ?: root.get("thumbnailPath")?.asString
                if (!previewPath.isNullOrEmpty()) {
                    posterUrl = if (previewPath.startsWith("http")) previewPath else "$host$previewPath"
                }
            }
        } catch (ignored: Exception) {}

        val streams = extractStreams(client, pageUrl, pageUrl)
        val studio = DubStudio("peertube_studio", "PeerTube")
        val player = PlayerSource("peertube_player", "PeerTube")
        player.episodes.add(EpisodeItem("peertube_ep_1", "1", title, "1", pageUrl))
        studio.players.add(player)

        val qualities = if (streams.isNotEmpty()) {
            streams.map { it.label }.toMutableList()
        } else {
            mutableListOf("1080p", "720p", "480p", "360p")
        }

        return MediaInfo(
            url = pageUrl,
            siteType = "peertube",
            title = title,
            posterUrl = posterUrl,
            description = description,
            isSeries = false,
            studios = mutableListOf(studio),
            availableQualities = qualities
        )
    }
}

