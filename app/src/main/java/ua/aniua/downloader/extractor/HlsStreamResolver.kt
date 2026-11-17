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

    fun resolveMasterPlaylist(
        client: OkHttpClient,
        masterUrl: String,
        referer: String?,
        userAgent: String = ExtractorUtils.DEFAULT_UA
    ): List<StreamQuality> {
        val cleanMasterUrl = ExtractorUtils.stripFragmentHint(masterUrl)
        val bestByHeight = linkedMapOf<Int, Pair<Int, StreamQuality>>()
        val extra = mutableListOf<StreamQuality>()
        try {
            val reqBuilder = Request.Builder().url(cleanMasterUrl)
            val headers = ExtractorUtils.buildStreamHeaders(cleanMasterUrl, referer)
            headers["User-Agent"] = userAgent
            for ((k, v) in headers) {
                reqBuilder.header(k, v)
            }

            val content = ExtractorUtils.executeForString(client, reqBuilder.build()) ?: return emptyList()
            if (!content.contains("#EXTM3U")) return emptyList()
            if (!content.contains("#EXT-X-STREAM-INF")) {
                // Це вже медіа-плейлист, а не master-плейлист
                val sq = StreamQuality("Auto", 720, cleanMasterUrl, true)
                sq.headers["User-Agent"] = userAgent
                if (!referer.isNullOrBlank() && !cleanMasterUrl.contains("googlevideo.com", ignoreCase = true)) {
                    sq.headers["Referer"] = referer
                }
                return listOf(sq)
            }

            val baseUri = URI(cleanMasterUrl)
            val reader = BufferedReader(StringReader(content))
            var line: String?
            var currentHeight = 0
            var currentCodecRank = 1
            val hasSeparateAudio = content.contains("#EXT-X-MEDIA:TYPE=AUDIO")

            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    currentHeight = 0
                    val lowerLine = l.lowercase(java.util.Locale.ROOT)
                    currentCodecRank = when {
                        lowerLine.contains("avc1") -> 3
                        lowerLine.contains("hev1") || lowerLine.contains("hvc1") -> 2
                        !lowerLine.contains("av01") -> 1
                        else -> 0
                    }
                    val rm = RESOLUTION_PATTERN.matcher(l)
                    if (rm.find()) {
                        val w = rm.group(1)?.toIntOrNull() ?: 0
                        val h = rm.group(2)?.toIntOrNull() ?: 0
                        currentHeight = ExtractorUtils.normalizeResolutionTier(w, h)
                    }
                } else if (!l.startsWith("#") && l.isNotEmpty()) {
                    val label = if (currentHeight > 0) "${currentHeight}p" else "Auto"
                    // Якщо майстер-плейлист містить окремі аудіодоріжки (#EXT-X-MEDIA:TYPE=AUDIO),
                    // то окремий sub-url містить виключно відео без аудіо!
                    // Для коректного відтворення зі звуком та завантаження повертаємо Master Playlist URL з підказкою якості.
                    val streamUrl = if (hasSeparateAudio) {
                        if (currentHeight > 0) "$cleanMasterUrl#${currentHeight}p" else cleanMasterUrl
                    } else {
                        if (!l.startsWith("http://") && !l.startsWith("https://")) {
                            baseUri.resolve(l).toString()
                        } else {
                            l
                        }
                    }

                    val sq = StreamQuality(label, currentHeight, streamUrl, true)
                    sq.headers["User-Agent"] = userAgent
                    if (!referer.isNullOrBlank() && !cleanMasterUrl.contains("googlevideo.com", ignoreCase = true)) {
                        sq.headers["Referer"] = referer
                    }
                    if (currentHeight > 0) {
                        val existing = bestByHeight[currentHeight]
                        if (existing == null || currentCodecRank > existing.first) {
                            bestByHeight[currentHeight] = currentCodecRank to sq
                        }
                    } else {
                        extra.add(sq)
                    }
                }
            }
        } catch (ignored: Exception) {}

        val qualities = (bestByHeight.values.map { it.second } + extra).toMutableList()
        qualities.sortWith(compareByDescending { it.height })
        return qualities
    }
}
