package ua.aniua.downloader.extractor

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.PlayerSource
import ua.aniua.downloader.model.StreamQuality
import java.util.regex.Pattern

object AshdiExtractor {

    private val FILE_PATTERN = Pattern.compile("file\\s*:\\s*[\"']([^\"']+)[\"']")

    fun extractStreams(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        return try {
            val request = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", referer ?: iframeUrl)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) return emptyList()

            val html = response.body?.string() ?: ""
            val fileMatcher = FILE_PATTERN.matcher(html)
            if (fileMatcher.find()) {
                var fileVal = fileMatcher.group(1)?.trim() ?: ""

                // Якщо це складний вкладений JSON плейлист PlayerJS
                if (fileVal.startsWith("[{")) {
                    try {
                        val element = JsonParser.parseString(fileVal)
                        val firstFile = findFirstFileInJson(element)
                        if (!firstFile.isNullOrEmpty()) {
                            fileVal = firstFile
                        }
                    } catch (ignored: Exception) {}
                }

                val qualities = PlayerJsDecoder.parsePlayerJsQualities(fileVal, iframeUrl)
                if (qualities.isNotEmpty()) {
                    return qualities
                }
            }

            // Прямий пошук .m3u8 або .mp4 у HTML
            val directM3u8Pattern = Pattern.compile("(https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)")
            val m3u8Matcher = directM3u8Pattern.matcher(html)
            if (m3u8Matcher.find()) {
                val m3u8Url = m3u8Matcher.group(1) ?: ""
                return listOf(StreamQuality("Auto (HLS)", 720, m3u8Url, true))
            }

            val directMp4Pattern = Pattern.compile("(https?://[^\\s\"'<>]+?\\.mp4[^\\s\"'<>]*)")
            val mp4Matcher = directMp4Pattern.matcher(html)
            if (mp4Matcher.find()) {
                val mp4Url = mp4Matcher.group(1) ?: ""
                return listOf(StreamQuality("720p", 720, mp4Url, false))
            }

            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun findFirstFileInJson(node: JsonElement?): String? {
        if (node == null || node.isJsonNull) return null
        if (node.isJsonArray) {
            for (child in node.asJsonArray) {
                val res = findFirstFileInJson(child)
                if (res != null) return res
            }
        } else if (node.isJsonObject) {
            val obj = node.asJsonObject
            if (obj.has("file") && obj.get("file").isJsonPrimitive) {
                return obj.get("file").asString
            }
            if (obj.has("folder")) {
                return findFirstFileInJson(obj.get("folder"))
            }
        }
        return null
    }

    fun parseAshdiSerial(client: OkHttpClient, serialUrl: String): List<DubStudio> {
        val studios = mutableListOf<DubStudio>()
        try {
            val request = Request.Builder()
                .url(serialUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", serialUrl)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) return emptyList()

            val html = response.body?.string() ?: ""
            val matcher = FILE_PATTERN.matcher(html)
            if (!matcher.find()) return emptyList()

            val jsonStr = matcher.group(1)?.trim() ?: return emptyList()
            if (!jsonStr.startsWith("[{")) return emptyList()

            val rootElement = JsonParser.parseString(jsonStr)
            if (!rootElement.isJsonArray) return emptyList()

            for (studioElement in rootElement.asJsonArray) {
                if (!studioElement.isJsonObject) continue
                val sObj = studioElement.asJsonObject
                val studioTitle = sObj.get("title")?.asString ?: "Озвучка"
                val studio = DubStudio(
                    id = "ashdi_" + studioTitle.hashCode(),
                    name = studioTitle
                )

                val episodes = mutableListOf<EpisodeItem>()
                if (sObj.has("folder") && sObj.get("folder").isJsonArray) {
                    val folder = sObj.getAsJsonArray("folder")
                    for (i in 0 until folder.size()) {
                        val epObj = folder[i].asJsonObject
                        val epTitle = epObj.get("title")?.asString ?: "Серія ${i + 1}"
                        val epFile = epObj.get("file")?.asString ?: ""
                        if (epFile.isNotEmpty()) {
                            episodes.add(
                                EpisodeItem(
                                    id = "ep_${i + 1}",
                                    number = "${i + 1}",
                                    title = epTitle,
                                    season = "1",
                                    iframeUrl = epFile
                                )
                            )
                        }
                    }
                }

                if (episodes.isNotEmpty()) {
                    studio.players.add(
                        PlayerSource(
                            id = "ashdi",
                            name = "Плеєр Ashdi",
                            episodes = episodes
                        )
                    )
                    studios.add(studio)
                }
            }
        } catch (ignored: Exception) {}
        return studios
    }
}
