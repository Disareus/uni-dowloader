package ua.aniua.downloader.extractor

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.StreamQuality
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

object TortugaExtractor {

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
                val rawFile = fileMatcher.group(1)?.trim() ?: ""
                val decoded = tryDecodeTortugaFile(rawFile)
                val qualities = PlayerJsDecoder.parsePlayerJsQualities(decoded, iframeUrl)
                if (qualities.isNotEmpty()) return qualities
            }

            // Fallback direct .m3u8 regex
            val m3u8Pattern = Pattern.compile("(https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)")
            val m = m3u8Pattern.matcher(html)
            if (m.find()) {
                val m3u8Url = m.group(1) ?: ""
                return listOf(StreamQuality("Auto (HLS)", 720, m3u8Url, true))
            }

            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun tryDecodeTortugaFile(raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("[")) {
            return raw
        }
        for (candidate in listOf(raw, raw.reversed())) {
            try {
                val cleaned = candidate.replace(Regex("[^A-Za-z0-9+/=]"), "")
                val pad = (4 - (cleaned.length % 4)) % 4
                val padded = cleaned + "=".repeat(pad)
                val bytes = Base64.decode(padded, Base64.DEFAULT)
                val decoded = String(bytes, StandardCharsets.UTF_8)
                if (decoded.contains("http") && (decoded.contains(".m3u8") || decoded.contains(".mp4"))) {
                    return decoded
                }
            } catch (ignored: Exception) {}
        }
        return raw
    }
}
