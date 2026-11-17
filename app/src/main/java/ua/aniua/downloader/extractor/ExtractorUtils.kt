package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.StreamQuality
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

internal object ExtractorUtils {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val HEIGHT_P_PATTERN = Pattern.compile("(\\d{3,4})\\s*[pр]", Pattern.CASE_INSENSITIVE)
    private val FIRST_NUM_HEIGHT_PATTERN = Pattern.compile("(?:^|\\D)(\\d{3,4})(?:\\D|$)")
    private val INTERNAL_FRAGMENT_REGEX = Regex("#(?:hls|\\d{3,4}p\\d{0,3})$", RegexOption.IGNORE_CASE)

    private val DIRECT_MEDIA_HOST_HINTS = listOf(
        "googlevideo.com/videoplayback",
        "mime=video",
        "mime_type=video",
        "tikwm.com/video/media",
        "tikmate.app/download/"
    )

    private val CLEAN_HEADER_CDN_HINTS = listOf(
        "tikmate.app",
        "nowmvideo.com",
        "tikwm.com",
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

    fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val cleanPath = lower.substringBefore("?").substringBefore("#")
        if (cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") || cleanPath.endsWith(".png") ||
            cleanPath.endsWith(".webp") || cleanPath.endsWith(".gif") || cleanPath.endsWith(".svg") ||
            cleanPath.endsWith(".bmp") || cleanPath.endsWith(".ico") || cleanPath.endsWith(".avif") ||
            cleanPath.endsWith(".heic") || cleanPath.endsWith(".image")
        ) {
            return true
        }
        return lower.contains("mime_type=image") ||
            lower.contains("tplv-tiktokx") ||
            lower.contains("tplv-photomode") ||
            lower.contains("sc=cover") ||
            lower.contains("sc=dynamic_cover") ||
            lower.contains("sc=avatar") ||
            lower.contains("stp=dst-jpg") ||
            lower.contains("stp=dst-png") ||
            lower.contains("stp=dst-webp")
    }

    fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("content://") || lower.startsWith("file://")) return true
        if (isImageUrl(url)) return false
        if (isHlsUrl(url)) return true
        val pathPart = lower.substringBefore("?").substringBefore("#")
        if (pathPart.endsWith(".mp4") || pathPart.endsWith(".webm")) {
            return true
        }
        if (lower.contains(".mp4")) return true
        if (DIRECT_MEDIA_HOST_HINTS.any { lower.contains(it) }) return true
        if (lower.contains("nowmvideo.com") && lower.contains("/download/")) return true
        if ((lower.contains("tiktokcdn") || lower.contains("tiktokv.com")) &&
            (lower.contains("/video/") || lower.contains("video_mp4"))
        ) {
            return true
        }
        if ((lower.contains("cdninstagram.com") || lower.contains("fbcdn.net")) &&
            (lower.contains("/o1/v/") || lower.contains("/t50.") || lower.contains("/t2/v/"))
        ) {
            return true
        }
        return false
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
        val lowerUrl = streamUrl.lowercase(Locale.ROOT)
        if (lowerUrl.contains("googlevideo.com")) {
            headers["User-Agent"] = when {
                lowerUrl.contains("c=visionos") -> YouTubeExtractor.VISIONOS_USER_AGENT
                lowerUrl.contains("c=android_vr") -> YouTubeExtractor.ANDROID_VR_USER_AGENT
                lowerUrl.contains("c=ios") -> YouTubeExtractor.IOS_USER_AGENT
                lowerUrl.contains("c=android") -> YouTubeExtractor.ANDROID_USER_AGENT
                lowerUrl.contains("c=mweb") -> YouTubeExtractor.MWEB_USER_AGENT
                lowerUrl.contains("c=web") -> DEFAULT_UA
                else -> YouTubeExtractor.VISIONOS_USER_AGENT
            }
            return headers
        }

        headers["User-Agent"] = DEFAULT_UA
        if (CLEAN_HEADER_CDN_HINTS.any { lowerUrl.contains(it) }) {
            return headers
        }
        if (!referer.isNullOrEmpty()) {
            headers["Referer"] = referer
        }
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

    /**
     * Витягує числову висоту якості з мітки (наприклад, "1080p60" -> 1080, "720p (HD)" -> 720).
     */
    fun extractQualityHeight(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        val pMatcher = HEIGHT_P_PATTERN.matcher(label)
        if (pMatcher.find()) {
            val h = pMatcher.group(1)?.toIntOrNull()
            if (h != null && h in 140..4320) return h
        }
        val numMatcher = FIRST_NUM_HEIGHT_PATTERN.matcher(label)
        if (numMatcher.find()) {
            val h = numMatcher.group(1)?.toIntOrNull()
            if (h != null && h in 140..4320) return h
        }
        val lower = label.lowercase(Locale.ROOT)
        return when {
            lower.contains("4k") -> 2160
            lower.contains("2k") -> 1440
            lower.contains("fhd") -> 1080
            lower.contains("hd") -> 720
            else -> null
        }
    }

    /**
     * Нормалізує роздільну здатність до стандартного класу (1080/720/480/360)
     * для вертикальних відео (Shorts 720x1280 -> 720) та широкоформатного кіно (1920x800 -> 1080).
     */
    fun normalizeResolutionTier(width: Int, height: Int, label: String? = null): Int {
        extractQualityHeight(label)?.let { return it }
        if (width > 0 && height > 0) {
            return when {
                height > width -> width
                width * 9 > height * 16 -> kotlin.math.round(width * 9.0 / 16.0).toInt()
                else -> height
            }
        }
        return if (height > 0) height else 0
    }

    fun isWebmStreamUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val pathPart = lower.substringBefore("?").substringBefore("#")
        return pathPart.endsWith(".webm") ||
            lower.contains("mime=video%2fwebm") ||
            lower.contains("mime=video/webm")
    }

    /**
     * Зрізає лише внутрішній фрагмент якості (#hls, #480p), не чіпаючи локальні імена файлів із '#'.
     */
    fun stripFragmentHint(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return trimmed.replace(INTERNAL_FRAGMENT_REGEX, "")
    }

    fun selectBestStream(streams: List<StreamQuality>, desiredQuality: String): StreamQuality {
        streams.firstOrNull { it.label.equals(desiredQuality, ignoreCase = true) }?.let { return it }

        val targetHeight = extractQualityHeight(desiredQuality)
        if (targetHeight == null) {
            return streams.maxByOrNull { if (it.height > 0) it.height else (extractQualityHeight(it.label) ?: 720) }
                ?: streams[0]
        }

        return streams.minWithOrNull(
            compareBy<StreamQuality> {
                val h = if (it.height > 0) it.height else (extractQualityHeight(it.label) ?: 720)
                kotlin.math.abs(h - targetHeight)
            }.thenBy {
                it.label.contains("Auto", ignoreCase = true)
            }
        ) ?: streams[0]
    }
}
