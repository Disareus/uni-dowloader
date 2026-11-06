package ua.aniua.downloader.extractor

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ua.aniua.downloader.model.StreamQuality
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

object MoonAnimeExtractor {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val IFRAME_SRC_PATTERN = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']")
    private val SLUG_PATTERN = Pattern.compile("/(?:iframe|embed)/([a-zA-Z0-9_-]+)")
    private val VIDEO_SLUG_JS_PATTERN = Pattern.compile("var\\s+videoSlug\\s*=\\s*[\"']([^\"']+)[\"']")
    private val INNER_KEY_PATTERN = Pattern.compile("function\\s+_0xd\\([^)]*\\)\\s*\\{\\s*var\\s+k\\s*=\\s*[\"']([^\"']+)[\"']")
    private val RAW_VIDEO_PATTERN = Pattern.compile("(?:var\\s+rawVideo|file)\\s*=\\s*_0xd\\([\"']([^\"']+)[\"']\\)")
    private val RAW_VIDEO_DIRECT_PATTERN = Pattern.compile("var\\s+rawVideo\\s*=\\s*[\"']([^\"']+)[\"']")
    private val DOWNLOAD_TOKEN_PATTERN = Pattern.compile("(?:var\\s+downloadToken|token)\\s*=\\s*[\"']([^\"']+)[\"']")
    private val TICKET_URL_PATTERN = Pattern.compile("ticketUrl\\s*:\\s*[\"']([^\"']+)[\"']")
    private val M3U8_VARIANT_PATTERN = Pattern.compile("(https?://[^\\s\"'<>]+index\\.m3u8[^\\s\"'<>]*)")
    private val MOONCDN_PATH_PATTERN = Pattern.compile("https?://([a-zA-Z0-9]+)\\.mooncdn\\.(?:online|xyz)/([^/\\s\"']+/[^/\\s\"']+)/hls/")

    fun isMoonUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("moonanime") || lower.contains("mooncdn") || lower.contains("/moon")
    }

    fun extractStreams(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        val streams = extractStreamsInternal(client, iframeUrl, referer)
        if (streams.isNotEmpty()) return streams

        // Якщо первинний домен (наприклад, moonanime.net) дав помилку, пробуємо альтернативний moonanime.art (або навпаки)
        if (iframeUrl.contains("moonanime.net")) {
            val altUrl = iframeUrl.replace("moonanime.net", "moonanime.art")
            val altStreams = extractStreamsInternal(client, altUrl, referer)
            if (altStreams.isNotEmpty()) return altStreams
        } else if (iframeUrl.contains("moonanime.art")) {
            val altUrl = iframeUrl.replace("moonanime.art", "moonanime.net")
            val altStreams = extractStreamsInternal(client, altUrl, referer)
            if (altStreams.isNotEmpty()) return altStreams
        }

        return emptyList()
    }

    private fun extractStreamsInternal(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        return try {
            val request = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "uk,en-US;q=0.9,en;q=0.8")
                .header("Referer", referer ?: "https://anitube.in.ua/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) return emptyList()

            val html = response.body?.string() ?: ""

            // 1. Двоступеневе XOR декодування MoonAnime
            val outerJs = decodeMoonanimeOuterJs(html)
            if (outerJs.isNotEmpty()) {
                val streamsFromOuter = resolveDecodedMoonJs(client, outerJs, iframeUrl)
                if (streamsFromOuter.isNotEmpty()) return streamsFromOuter
            }

            // 2. Звичайний file: "..." (PlayerJS формат у вихідному HTML)
            val filePattern = Pattern.compile("file\\s*:\\s*[\"']([^\"']+)[\"']")
            val fm = filePattern.matcher(html)
            if (fm.find()) {
                val fStr = fm.group(1)?.trim() ?: ""
                val qualities = PlayerJsDecoder.parsePlayerJsQualities(fStr, iframeUrl)
                if (qualities.isNotEmpty()) return qualities
            }

            // 3. Прямий m3u8 у вихідному HTML
            val m3u8Pattern = Pattern.compile("(https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)")
            val mm = m3u8Pattern.matcher(html)
            if (mm.find()) {
                val m3u8Url = mm.group(1) ?: ""
                val resolved = HlsStreamResolver.resolveMasterPlaylist(client, m3u8Url, iframeUrl)
                if (resolved.isNotEmpty()) return resolved
                return listOf(StreamQuality("Auto (Moon HLS)", 720, m3u8Url, true))
            }

            // 4. Прямий mp4 у коді
            val mp4Pattern = Pattern.compile("(https?://[^\\s\"'<>]+?\\.mp4[^\\s\"'<>]*)")
            val mpM = mp4Pattern.matcher(html)
            if (mpM.find()) {
                val mp4Url = mpM.group(1) ?: ""
                return listOf(StreamQuality("720p (Moon MP4)", 720, mp4Url, false))
            }

            // 5. Вкладений iframe
            val ifrMatcher = IFRAME_SRC_PATTERN.matcher(html)
            if (ifrMatcher.find()) {
                val nestedSrc = ifrMatcher.group(1) ?: ""
                if (nestedSrc.isNotEmpty() && nestedSrc != iframeUrl) {
                    val fullNested = DlePlaylistParser.normalizeUrl(nestedSrc, iframeUrl)
                    return GenericPlayerResolver.resolvePlayerStreams(client, fullNested, iframeUrl)
                }
            }

            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resolveDecodedMoonJs(client: OkHttpClient, outerJs: String, iframeUrl: String): List<StreamQuality> {
        val host = try { URI(iframeUrl).host } catch (e: Exception) { "moonanime.net" }
        val innerKey = extractInnerKey(outerJs)
        val rawVideo = decodeMoonanimeInnerVideo(outerJs, innerKey)
        val downloadToken = extractDownloadToken(outerJs)
        val ticketPath = extractTicketPath(outerJs)
        val slug = extractSlug(iframeUrl, outerJs)

        // Стратегія 1: Прямий CDN (mooncdn.xyz) з rawVideo HLS маніфесту (без витрати ліміту завантажень)
        if (rawVideo.contains(".m3u8")) {
            val directStreams = resolveDirectCdnMaster(client, rawVideo, iframeUrl)
            if (directStreams.isNotEmpty()) return directStreams
        }

        // Стратегія 2: Отримання прямого master m3u8 через Ticket API плеєра
        if (!downloadToken.isNullOrEmpty()) {
            val ticketStreams = resolveTicketMaster(client, host, iframeUrl, downloadToken, ticketPath, slug)
            if (ticketStreams.isNotEmpty()) return ticketStreams
        }

        // Стратегія 3: Використання безпосередньо rawVideo як fallback
        if (rawVideo.isNotEmpty()) {
            if (rawVideo.startsWith("[") && rawVideo.contains("http")) {
                val pjs = PlayerJsDecoder.parsePlayerJsQualities(rawVideo, iframeUrl)
                if (pjs.isNotEmpty()) return pjs
            }
            if (rawVideo.contains(".m3u8")) {
                val resolved = HlsStreamResolver.resolveMasterPlaylist(client, rawVideo, iframeUrl)
                if (resolved.isNotEmpty()) {
                    return resolved.map {
                        StreamQuality(if (it.label.contains("Moon")) it.label else "${it.label} (Moon)", it.height, it.url, it.isHls)
                    }
                }
                return listOf(StreamQuality("Auto (Moon)", 720, rawVideo, true))
            }
            if (rawVideo.contains(".mp4") || rawVideo.contains(".webm")) {
                return listOf(StreamQuality("720p (Moon)", 720, rawVideo, false))
            }
        }

        return emptyList()
    }

    private fun resolveDirectCdnMaster(client: OkHttpClient, rawVideoUrl: String, iframeUrl: String): List<StreamQuality> {
        return try {
            val mReq = Request.Builder()
                .url(rawVideoUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "*/*")
                .header("Referer", iframeUrl)
                .header("Origin", "https://${URI(iframeUrl).host}")
                .build()

            val mResp = client.newCall(mReq).execute()
            if (!mResp.isSuccessful || mResp.body == null) return emptyList()

            val mText = mResp.body?.string() ?: ""
            val vm = M3U8_VARIANT_PATTERN.matcher(mText)
            if (!vm.find()) {
                val directMaster = deriveDirectMasterFromContent(mText)
                if (directMaster != null) {
                    val resolved = HlsStreamResolver.resolveMasterPlaylist(client, directMaster, iframeUrl)
                    if (resolved.isNotEmpty()) return formatMoonQualities(resolved)
                }
                return emptyList()
            }

            val variantUrl = vm.group(1) ?: return emptyList()
            val vReq = Request.Builder()
                .url(variantUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "*/*")
                .header("Referer", iframeUrl)
                .header("Origin", "https://${URI(iframeUrl).host}")
                .build()

            val vResp = client.newCall(vReq).execute()
            if (!vResp.isSuccessful || vResp.body == null) return emptyList()

            val vText = vResp.body?.string() ?: ""
            val directMaster = deriveDirectMasterFromContent(vText) ?: return emptyList()

            val resolved = HlsStreamResolver.resolveMasterPlaylist(client, directMaster, iframeUrl)
            if (resolved.isNotEmpty()) {
                formatMoonQualities(resolved)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deriveDirectMasterFromContent(content: String): String? {
        val m = MOONCDN_PATH_PATTERN.matcher(content)
        if (m.find()) {
            val subdomain = m.group(1)
            val path = m.group(2)
            return "https://$subdomain.mooncdn.xyz/$path/hls/playlist.m3u8"
        }
        return null
    }

    private fun resolveTicketMaster(
        client: OkHttpClient,
        host: String,
        iframeUrl: String,
        token: String,
        ticketPath: String?,
        slug: String?
    ): List<StreamQuality> {
        return try {
            val resolvedPath = when {
                !ticketPath.isNullOrEmpty() -> ticketPath
                !slug.isNullOrEmpty() -> "/pl/download/$slug/"
                else -> return emptyList()
            }
            val ticketUrl = if (resolvedPath.startsWith("http")) resolvedPath else "https://$host$resolvedPath"

            val jsonBody = JSONObject().apply {
                put("token", token)
            }.toString()

            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val req = Request.Builder()
                .url(ticketUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "uk,en-US;q=0.9,en;q=0.8")
                .header("Referer", iframeUrl)
                .header("Origin", "https://$host")
                .post(body)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful || resp.body == null) return emptyList()

            val respJson = resp.body?.string() ?: ""
            val obj = JSONObject(respJson)
            if (!obj.optBoolean("ok", false)) return emptyList()

            val master = obj.optString("master", "")
            if (master.isNotEmpty()) {
                val resolved = HlsStreamResolver.resolveMasterPlaylist(client, master, iframeUrl)
                if (resolved.isNotEmpty()) return formatMoonQualities(resolved)
            }

            val files = obj.optJSONArray("files")
            if (files != null && files.length() > 0) {
                val directFiles = mutableListOf<StreamQuality>()
                for (i in 0 until files.length()) {
                    val fObj = files.getJSONObject(i)
                    val fUrl = fObj.optString("url", "")
                    val fHeight = fObj.optInt("height", 720)
                    if (fUrl.isNotEmpty()) {
                        directFiles.add(StreamQuality("${fHeight}p (Moon)", fHeight, fUrl, false))
                    }
                }
                if (directFiles.isNotEmpty()) return directFiles
            }

            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun formatMoonQualities(qualities: List<StreamQuality>): List<StreamQuality> {
        return qualities.map { q ->
            val clean = q.label.replace("(HLS)", "").trim()
            val label = if (clean.contains("Moon")) clean else "$clean (Moon)"
            StreamQuality(label, q.height, q.url, q.isHls)
        }
    }

    fun decodeMoonanimeOuterJs(html: String): String {
        val pattern = Pattern.compile("atob\\([\"']([A-Za-z0-9+/=]{100,})[\"']\\)")
        val m = pattern.matcher(html)
        if (!m.find()) return ""
        val b64 = m.group(1) ?: return ""
        val b = decodeBase64Bytes(b64)
        if (b.size <= 33) return ""
        return try {
            var state = b[0].toInt() and 0xFF
            val key = b.sliceArray(1..32)
            val out = ByteArray(b.size - 33)
            for (i in out.indices) {
                val k = key[i % 32].toInt() and 0xFF
                val bi = b[i + 33].toInt() and 0xFF
                out[i] = (bi xor k xor state).toByte()
                state = (bi + k) and 0xFF
            }
            String(out, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun extractInnerKey(outerJs: String): String? {
        val m = INNER_KEY_PATTERN.matcher(outerJs)
        return if (m.find()) m.group(1) else null
    }

    fun extractDownloadToken(outerJs: String): String? {
        val m = DOWNLOAD_TOKEN_PATTERN.matcher(outerJs)
        return if (m.find()) m.group(1) else null
    }

    fun extractTicketPath(outerJs: String): String? {
        val m = TICKET_URL_PATTERN.matcher(outerJs)
        return if (m.find()) m.group(1) else null
    }

    fun extractSlug(iframeUrl: String, outerJs: String): String? {
        val jsm = VIDEO_SLUG_JS_PATTERN.matcher(outerJs)
        if (jsm.find()) return jsm.group(1)
        val m = SLUG_PATTERN.matcher(iframeUrl)
        return if (m.find()) m.group(1) else null
    }

    fun decodeMoonanimeInnerVideo(outerJs: String, innerKey: String?): String {
        val fm = RAW_VIDEO_PATTERN.matcher(outerJs)
        if (fm.find()) {
            val encodedFile = fm.group(1) ?: ""
            if (encodedFile.isNotEmpty() && !innerKey.isNullOrEmpty()) {
                val decoded = decodeInnerXor(encodedFile, innerKey)
                if (decoded.isNotEmpty()) return decoded
            }
        }

        val directM = RAW_VIDEO_DIRECT_PATTERN.matcher(outerJs)
        if (directM.find()) {
            return directM.group(1)?.trim() ?: ""
        }

        return ""
    }

    fun decodeInnerXor(encoded: String, key: String): String {
        return try {
            val raw = decodeBase64Bytes(encoded)
            val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArray(raw.size)
            for (i in raw.indices) {
                out[i] = (raw[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            val unquoted = String(out, StandardCharsets.UTF_8)
            safeUrlDecode(unquoted)
        } catch (e: Exception) {
            ""
        }
    }

    fun decodeBase64Bytes(raw: String): ByteArray {
        var s = raw.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - s.length % 4) % 4
        if (pad in 1..3) {
            s += "=".repeat(pad)
        }
        return try {
            android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        } catch (e: Throwable) {
            try {
                java.util.Base64.getDecoder().decode(s)
            } catch (ex: Throwable) {
                ByteArray(0)
            }
        }
    }

    fun safeUrlDecode(s: String): String {
        return try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Throwable) {
            try {
                Uri.decode(s) ?: s
            } catch (ex: Throwable) {
                s
            }
        }
    }
}
