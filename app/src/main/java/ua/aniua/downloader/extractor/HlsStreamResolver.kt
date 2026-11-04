package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.StreamQuality
import java.io.BufferedReader
import java.io.StringReader
import java.net.URI
import java.util.regex.Pattern

object HlsStreamResolver {

    private val RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)")

    fun resolveMasterPlaylist(client: OkHttpClient, masterUrl: String, referer: String?): List<StreamQuality> {
        val qualities = mutableListOf<StreamQuality>()
        try {
            val request = Request.Builder()
                .url(masterUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", referer ?: masterUrl)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) return emptyList()

            val content = response.body?.string() ?: return emptyList()
            if (!content.contains("#EXT-X-STREAM-INF")) {
                // Це вже медіа-плейлист, а не master-плейлист
                return listOf(StreamQuality("Auto", 720, masterUrl, true))
            }

            val baseUri = URI(masterUrl)
            val reader = BufferedReader(StringReader(content))
            var line: String?
            var currentHeight = 0

            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    currentHeight = 0
                    val rm = RESOLUTION_PATTERN.matcher(l)
                    if (rm.find()) {
                        currentHeight = rm.group(2).toIntOrNull() ?: 0
                    }
                } else if (!l.startsWith("#") && l.isNotEmpty()) {
                    var streamUrl = l
                    if (!streamUrl.startsWith("http://") && !streamUrl.startsWith("https://")) {
                        streamUrl = baseUri.resolve(streamUrl).toString()
                    }
                    val label = if (currentHeight > 0) "${currentHeight}p" else "Auto"
                    val sq = StreamQuality(label, currentHeight, streamUrl, true)
                    if (referer != null) {
                        sq.headers["Referer"] = referer
                    }
                    qualities.add(sq)
                }
            }
        } catch (ignored: Exception) {}

        qualities.sortWith(compareByDescending { it.height })
        return qualities
    }
}
