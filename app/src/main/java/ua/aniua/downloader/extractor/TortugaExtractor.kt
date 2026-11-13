package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.StreamQuality
import java.util.regex.Pattern

object TortugaExtractor {

    private val FILE_PATTERN = Pattern.compile("file\\s*:\\s*[\"']([^\"']+)[\"']")
    private val M3U8_PATTERN = Pattern.compile("(https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)")

    fun extractStreams(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        return try {
            val request = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Referer", referer ?: iframeUrl)
                .build()

            val html = ExtractorUtils.executeForString(client, request) ?: return emptyList()
            val fileMatcher = FILE_PATTERN.matcher(html)
            if (fileMatcher.find()) {
                val rawFile = fileMatcher.group(1)?.trim() ?: ""
                val decoded = tryDecodeTortugaFile(rawFile)
                val qualities = PlayerJsDecoder.parsePlayerJsQualities(decoded, iframeUrl)
                if (qualities.isNotEmpty()) return qualities
            }

            // Fallback direct .m3u8 regex
            val m = M3U8_PATTERN.matcher(html)
            if (m.find()) {
                val m3u8Url = m.group(1) ?: ""
                return listOf(StreamQuality("Auto (HLS)", 720, m3u8Url, true))
            }

            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tryDecodeTortugaFile(raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("[")) {
            return raw
        }
        for (candidate in listOf(raw, raw.reversed())) {
            val cleaned = candidate.replace(Regex("[^A-Za-z0-9+/=]"), "")
            val decoded = ExtractorUtils.decodeBase64Utf8(cleaned)
            if (decoded.contains("http") && (decoded.contains(".m3u8") || decoded.contains(".mp4"))) {
                return decoded
            }
        }
        return raw
    }
}
