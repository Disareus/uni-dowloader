package ua.aniua.downloader.extractor

import android.util.Base64
import ua.aniua.downloader.model.StreamQuality
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

object PlayerJsDecoder {

    private val QUALITY_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\s*(https?://[^\\s,\"\'<>]+)")
    private val HEIGHT_PATTERN = Pattern.compile("(\\d{3,4})")

    fun decodePlayerJsString(encoded: String?): String {
        if (encoded == null) return ""
        val v = encoded.trim()
        if (v.isEmpty() || v.contains("http://") || v.contains("https://")) {
            return v
        }

        var cleaned = v
        if (v.startsWith("#2")) {
            cleaned = v.substring(2)
        } else if (v.startsWith("#")) {
            cleaned = v.substring(1)
        }

        cleaned = cleaned.replace(Regex("//[^/]+//"), "")

        return try {
            val pad = (4 - (cleaned.length % 4)) % 4
            val padded = cleaned + "=".repeat(pad)
            val decodedBytes = Base64.decode(padded, Base64.DEFAULT)
            val decoded = String(decodedBytes, StandardCharsets.UTF_8)
            if (decoded.contains("http")) decoded else v
        } catch (ignored: Exception) {
            v
        }
    }

    fun parsePlayerJsQualities(fileStr: String, referer: String?): List<StreamQuality> {
        val decoded = decodePlayerJsString(fileStr)
        val list = mutableListOf<StreamQuality>()

        val matcher = QUALITY_PATTERN.matcher(decoded)
        while (matcher.find()) {
            val labelRaw = matcher.group(1)
            val rawUrl = matcher.group(2)
            if (rawUrl != null) {
                val url = rawUrl.split(" or ")[0].trim()
                var height = 0
                if (labelRaw != null) {
                    val hMatcher = HEIGHT_PATTERN.matcher(labelRaw)
                    if (hMatcher.find()) {
                        height = hMatcher.group(1).toIntOrNull() ?: 0
                    }
                }
                val label = if (height > 0) "${height}p" else (labelRaw?.trim() ?: "auto")
                val isHls = url.contains(".m3u8")

                val quality = StreamQuality(label, height, url, isHls)
                if (referer != null) {
                    quality.headers["Referer"] = referer
                }
                list.add(quality)
            }
        }

        if (list.isEmpty() && (decoded.startsWith("http://") || decoded.startsWith("https://"))) {
            val url = decoded.split(" or ")[0].trim()
            val isHls = url.contains(".m3u8")
            val q = StreamQuality("Auto", 720, url, isHls)
            if (referer != null) {
                q.headers["Referer"] = referer
            }
            list.add(q)
        }

        list.sortWith(compareByDescending { it.height })
        return list
    }
}
