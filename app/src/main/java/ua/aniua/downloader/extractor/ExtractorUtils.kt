package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.StreamQuality
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object ExtractorUtils {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val DIRECT_MEDIA_HOST_HINTS = listOf(
        "googlevideo.com/videoplayback",
        "mime=video",
        "tikwm.com/video/media",
        "tiktokcdn",
        "cdninstagram.com",
        "fbcdn.net"
    )

    fun ensureHttpsScheme(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
    }

    fun extractBaseOrigin(url: String, fallback: String = ""): String {
        return try {
            val uri = URI(ensureHttpsScheme(url))
            val scheme = uri.scheme
            val host = uri.host
            if (!scheme.isNullOrEmpty() && !host.isNullOrEmpty()) "$scheme://$host" else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    fun getCookieHeader(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            android.webkit.CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun executeForString(client: OkHttpClient, request: Request): String? {
        val finalRequest = if (request.header("Cookie") == null) {
            val cookie = getCookieHeader(request.url.toString())
            if (!cookie.isNullOrBlank()) {
                request.newBuilder().header("Cookie", cookie).build()
            } else {
                request
            }
        } else {
            request
        }
        return try {
            client.newCall(finalRequest).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isHlsUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val pathPart = lower.substringBefore("?").substringBefore("#")
        return pathPart.endsWith(".m3u8") ||
            lower.contains(".m3u8") ||
            lower.contains("/_stream?id=") ||
            lower.contains("type=hls") ||
            lower.endsWith("#hls")
    }

    fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("content://") || lower.startsWith("file://")) return true
        if (isHlsUrl(url)) return true
        val pathPart = lower.substringBefore("?").substringBefore("#")
        if (pathPart.endsWith(".mp4") || pathPart.endsWith(".webm")) {
            return true
        }
        if (lower.contains(".mp4")) return true
        return DIRECT_MEDIA_HOST_HINTS.any { lower.contains(it) }
    }

    fun decodeBase64Bytes(raw: String): ByteArray {
        var s = raw.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - s.length % 4) % 4
        if (pad in 1..3) {
            s += "=".repeat(pad)
        }
        return try {
            android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            try {
                java.util.Base64.getDecoder().decode(s)
            } catch (_: Throwable) {
                ByteArray(0)
            }
        }
    }

    fun decodeBase64Utf8(raw: String): String {
        val bytes = decodeBase64Bytes(raw)
        return if (bytes.isEmpty()) "" else String(bytes, StandardCharsets.UTF_8)
    }

    fun identifyPlayer(url: String, fallbackIndex: Int? = null): Pair<String, String> {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains("ashdi") -> "ashdi" to "Ashdi"
            lower.contains("tortuga") -> "tortuga" to "Tortuga"
            MoonAnimeExtractor.isMoonUrl(url) -> "moon" to "Moon"
            FenixExtractor.isFenixUrl(url) -> "fenix" to "Fenix"
            PeerTubeExtractor.isPeerTubeUrl(url) -> "peertube" to "PeerTube"
            lower.contains("sibnet") -> "sibnet" to "Sibnet"
            lower.contains("kodik") -> "kodik" to "Kodik"
            lower.contains("ustore") -> "ustore" to "Ustore"
            lower.contains("alloha") -> "alloha" to "Alloha"
            lower.contains("collaps") -> "collaps" to "Collaps"
            lower.contains("vidsrc.buzz") -> "vidsrc_buzz" to "VidSrc"
            lower.contains("2embed") -> "2embed" to "2Embed"
            lower.contains("multiembed") || lower.contains("streamingnow") -> "multiembed" to "MultiEmbed"
            lower.contains("vidsrc") -> "vidsrc" to "VidSrc"
            else -> {
                val label = if (fallbackIndex != null) "Плеєр $fallbackIndex" else "Плеєр"
                "player" to label
            }
        }
    }

    fun buildStreamHeaders(streamUrl: String, referer: String?): MutableMap<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = DEFAULT_UA
        if (!referer.isNullOrEmpty()) {
            headers["Referer"] = referer
        }
        val lowerUrl = streamUrl.lowercase(Locale.ROOT)
        val lowerRef = referer?.lowercase(Locale.ROOT).orEmpty()
        if (lowerUrl.contains("fenix") || lowerRef.contains("fenix")) {
            headers["Origin"] = "https://fenixplay.xyz"
        } else if (lowerUrl.contains("moon") || lowerRef.contains("moon")) {
            headers["Origin"] = "https://moonanime.net"
        } else if (!referer.isNullOrEmpty()) {
            val origin = extractBaseOrigin(referer, "")
            if (origin.isNotEmpty()) {
                headers["Origin"] = origin
            }
        }
        val cookie = getCookieHeader(streamUrl) ?: getCookieHeader(referer)
        if (!cookie.isNullOrBlank()) {
            headers["Cookie"] = cookie
        }
        return headers
    }

    fun selectBestStream(streams: List<StreamQuality>, desiredQuality: String): StreamQuality {
        val targetHeight = desiredQuality.filter { it.isDigit() }.toIntOrNull() ?: 720
        return streams.minByOrNull {
            val h = if (it.height > 0) it.height else 720
            kotlin.math.abs(h - targetHeight)
        } ?: streams[0]
    }
}
