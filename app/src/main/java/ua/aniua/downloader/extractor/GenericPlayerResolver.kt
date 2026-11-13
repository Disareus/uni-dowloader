package ua.aniua.downloader.extractor

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import ua.aniua.downloader.model.StreamQuality
import java.util.Locale
import java.util.regex.Pattern

object GenericPlayerResolver {

    private const val MAX_IFRAME_DEPTH = 3

    private val SIBNET_MP4_PATTERN = Pattern.compile("src\\s*:\\s*[\"'](/v/[^\"']+\\.mp4)[\"']")
    private val FILE_PATTERN = Pattern.compile("file\\s*:\\s*[\"']([^\"']+)[\"']")
    private val SOURCES_ARRAY_PATTERN = Pattern.compile("sources\\s*:\\s*\\[([\\s\\S]*?)\\]")
    private val SOURCE_ITEM_FILE_PATTERN = Pattern.compile("(?:file|src)\\s*:\\s*[\"']([^\"']+)[\"']")
    private val SOURCE_ITEM_LABEL_PATTERN = Pattern.compile("(?:label|res)\\s*:\\s*[\"']?([^\"',}]+)[\"']?")
    private val M3U8_PATTERN = Pattern.compile("(https?://[^\\s\"'<>\\\\]+?\\.m3u8[^\\s\"'<>\\\\]*)")
    private val MP4_PATTERN = Pattern.compile("(https?://[^\\s\"'<>\\\\]+?\\.mp4[^\\s\"'<>\\\\]*)")
    private val JSON_LD_CONTENT_URL_PATTERN = Pattern.compile("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"")
    private val PACKED_JS_PATTERN = Pattern.compile(
        "eval\\(function\\(p,a,c,k,e,[dr]\\)\\{[\\s\\S]*?\\}\\('([\\s\\S]*?)',\\s*(\\d+),\\s*(\\d+),\\s*'([^']*)'\\.split\\('\\|'\\)",
        Pattern.CASE_INSENSITIVE
    )
    private val WORD_TOKEN_PATTERN = Pattern.compile("\\b([0-9a-zA-Z]+)\\b")
    private val IFRAME_SRC_PATTERN = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)

    private val IGNORED_IFRAME_HINTS = listOf(
        "googletagmanager", "google-analytics", "doubleclick", "googlesyndication",
        "disqus", "facebook.com", "platform.twitter", "recaptcha", "hcaptcha",
        "challenges.cloudflare.com", "chatango", "addthis", "sharethis"
    )

    fun resolvePlayerStreams(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        return resolvePlayerStreamsInternal(client, iframeUrl, referer, 0, mutableSetOf())
    }

    private fun resolvePlayerStreamsInternal(
        client: OkHttpClient,
        iframeUrl: String,
        referer: String?,
        depth: Int,
        visited: MutableSet<String>
    ): List<StreamQuality> {
        val url = iframeUrl.trim()
        if (url.isEmpty() || !visited.add(url)) return emptyList()
        val lower = url.lowercase(Locale.ROOT)

        // 1. Вже розпакований рядок PlayerJS
        if (url.startsWith("[") && url.contains("http")) {
            return PlayerJsDecoder.parsePlayerJsQualities(url, referer ?: url)
        }

        // 2. Прямий .m3u8 потік
        if (lower.contains(".m3u8")) {
            val resolved = HlsStreamResolver.resolveMasterPlaylist(client, url, referer)
            if (resolved.isNotEmpty()) return resolved
            return listOf(StreamQuality("Auto (HLS)", 720, url, true))
        }

        // 3. Прямий медіафайл (.mp4, .webm, googlevideo, tikwm, cdninstagram тощо)
        if (ExtractorUtils.isDirectMediaUrl(url)) {
            return listOf(StreamQuality("1080p", 1080, url, false))
        }

        // 4. Спеціалізовані балансери
        val streams: List<StreamQuality> = when {
            lower.contains("ashdi") -> AshdiExtractor.extractStreams(client, url, referer)
            lower.contains("tortuga") -> TortugaExtractor.extractStreams(client, url, referer)
            FenixExtractor.isFenixUrl(url) -> FenixExtractor.extractStreams(client, url, referer)
            PeerTubeExtractor.isPeerTubeUrl(url) -> PeerTubeExtractor.extractStreams(client, url, referer)
            MoonAnimeExtractor.isMoonUrl(url) -> MoonAnimeExtractor.extractStreams(client, url, referer)
            lower.contains("sibnet") -> extractSibnetStream(client, url, referer)
            else -> emptyList()
        }

        if (streams.isNotEmpty()) {
            // Якщо єдина отримана якість це master playlist (.m3u8) без окремих аудіодоріжок,
            // розгортаємо його на окремі якості
            if (streams.size == 1 && streams[0].isHls && streams[0].url.contains(".m3u8") && !FenixExtractor.isFenixUrl(url) && !PeerTubeExtractor.isPeerTubeUrl(url)) {
                val expanded = HlsStreamResolver.resolveMasterPlaylist(client, streams[0].url, url)
                if (expanded.isNotEmpty()) return expanded
            }
            return streams
        }

        // 5. Універсальний fallback для невідомих iframe (PlayerJS / Packed JS / HTML5 video / Nested iframe)
        return extractGenericIframe(client, url, referer, depth, visited)
    }

    private fun extractSibnetStream(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        return try {
            val req = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Referer", referer ?: "https://video.sibnet.ru/")
                .build()

            val html = ExtractorUtils.executeForString(client, req) ?: return emptyList()
            val m = SIBNET_MP4_PATTERN.matcher(html)
            if (m.find()) {
                val path = m.group(1)
                val fullUrl = "https://video.sibnet.ru$path"
                val q = StreamQuality("720p", 720, fullUrl, false)
                q.headers["Referer"] = iframeUrl
                listOf(q)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractGenericIframe(
        client: OkHttpClient,
        iframeUrl: String,
        referer: String?,
        depth: Int,
        visited: MutableSet<String>
    ): List<StreamQuality> {
        return try {
            val req = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Referer", referer ?: iframeUrl)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful || resp.body == null) return emptyList()

                val contentType = resp.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
                if (contentType.startsWith("video/")) {
                    return listOf(StreamQuality("1080p", 1080, iframeUrl, false))
                }
                if (contentType.contains("mpegurl")) {
                    return listOf(StreamQuality("Auto (HLS)", 720, iframeUrl, true))
                }

                val html = resp.body?.string() ?: ""
                if (html.isEmpty()) return emptyList()

                val unpacked = unpackJs(html)
                val combinedSearchText = normalizeEscapedUrls(if (unpacked.isNotEmpty()) "$html\n$unpacked" else html)

                // 1. PlayerJS file: '...' (включно з JSON масивами [{...}])
                val fm = FILE_PATTERN.matcher(combinedSearchText)
                while (fm.find()) {
                    var fileStr = fm.group(1)?.trim() ?: ""
                    if (fileStr.startsWith("[{")) {
                        try {
                            val elem = JsonParser.parseString(fileStr)
                            val firstFile = findFirstFileInJson(elem)
                            if (!firstFile.isNullOrEmpty()) fileStr = firstFile
                        } catch (_: Exception) {}
                    }
                    val qualities = PlayerJsDecoder.parsePlayerJsQualities(fileStr, iframeUrl)
                    if (qualities.isNotEmpty()) return qualities
                }

                // 2. JWPlayer / VideoJS sources: [...]
                val sm = SOURCES_ARRAY_PATTERN.matcher(combinedSearchText)
                while (sm.find()) {
                    val block = sm.group(1) ?: continue
                    val parsedSources = parseSourcesBlock(block, iframeUrl)
                    if (parsedSources.isNotEmpty()) return parsedSources
                }

                // 3. JSON-LD contentUrl
                val ldM = JSON_LD_CONTENT_URL_PATTERN.matcher(combinedSearchText)
                if (ldM.find()) {
                    val contentUrl = ldM.group(1)?.trim() ?: ""
                    if (contentUrl.startsWith("http")) {
                        val isHls = contentUrl.contains(".m3u8", ignoreCase = true)
                        if (isHls) {
                            val resolved = HlsStreamResolver.resolveMasterPlaylist(client, contentUrl, iframeUrl)
                            if (resolved.isNotEmpty()) return resolved
                        }
                        val q = StreamQuality(if (isHls) "Auto (HLS)" else "720p", 720, contentUrl, isHls)
                        q.headers["Referer"] = iframeUrl
                        return listOf(q)
                    }
                }

                // 4. Прямий .m3u8 у коді або розпакованому JS
                val mm = M3U8_PATTERN.matcher(combinedSearchText)
                if (mm.find()) {
                    val m3u8Url = mm.group(1) ?: ""
                    if (m3u8Url.isNotEmpty()) {
                        val resolved = HlsStreamResolver.resolveMasterPlaylist(client, m3u8Url, iframeUrl)
                        if (resolved.isNotEmpty()) return resolved
                        val q = StreamQuality("Auto (HLS)", 720, m3u8Url, true)
                        q.headers["Referer"] = iframeUrl
                        return listOf(q)
                    }
                }

                // 5. Прямий .mp4 у коді або розпакованому JS
                val mpm = MP4_PATTERN.matcher(combinedSearchText)
                if (mpm.find()) {
                    val mp4Url = mpm.group(1) ?: ""
                    if (mp4Url.isNotEmpty()) {
                        val q = StreamQuality("720p", 720, mp4Url, false)
                        q.headers["Referer"] = iframeUrl
                        return listOf(q)
                    }
                }

                val doc = Jsoup.parse(html, iframeUrl)

                // 6. HTML5 <video src="..."> та <source src="...">
                val videoEls = doc.select("video[src], video source[src], source[src]")
                for (vEl in videoEls) {
                    val rawSrc = vEl.attr("src").trim()
                    if (rawSrc.isEmpty() || rawSrc.startsWith("blob:") || rawSrc.startsWith("data:")) continue
                    val fullSrc = DlePlaylistParser.normalizeUrl(rawSrc, iframeUrl)
                    val isHls = fullSrc.contains(".m3u8", ignoreCase = true)
                    if (isHls) {
                        val resolved = HlsStreamResolver.resolveMasterPlaylist(client, fullSrc, iframeUrl)
                        if (resolved.isNotEmpty()) return resolved
                    }
                    val q = StreamQuality(if (isHls) "Auto (HLS)" else "720p", 720, fullSrc, isHls)
                    q.headers["Referer"] = iframeUrl
                    return listOf(q)
                }

                // 7. Themesia Base64 <select class="mirror"> <option value="...">
                if (depth < MAX_IFRAME_DEPTH) {
                    val mirrorOpts = doc.select("select.mirror option[value], select[name='mirror'] option[value]")
                    for (opt in mirrorOpts) {
                        val rawVal = opt.attr("value").trim()
                        if (rawVal.isEmpty()) continue
                        val decoded = ExtractorUtils.decodeBase64Utf8(rawVal)
                        if (decoded.isNotEmpty()) {
                            val ifrM = IFRAME_SRC_PATTERN.matcher(decoded)
                            val target = if (ifrM.find()) ifrM.group(1) else if (decoded.startsWith("http")) decoded else null
                            if (!target.isNullOrEmpty()) {
                                val normalized = DlePlaylistParser.normalizeUrl(target, iframeUrl)
                                val res = resolvePlayerStreamsInternal(client, normalized, iframeUrl, depth + 1, visited)
                                if (res.isNotEmpty()) return res
                            }
                        }
                    }

                    // 8. Вкладені <iframe> на сторінці
                    val iframes = doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
                    val candidateUrls = mutableListOf<String>()
                    val youtubeTrailerFallbacks = mutableListOf<String>()

                    for (ifr in iframes) {
                        val rawSrc = ifr.attr("src")
                            .ifEmpty { ifr.attr("data-src") }
                            .ifEmpty { ifr.attr("data-lazy-src") }
                            .trim()
                        if (rawSrc.isEmpty() || rawSrc == "about:blank" || rawSrc.startsWith("javascript:")) continue
                        val lowerSrc = rawSrc.lowercase(Locale.ROOT)
                        if (IGNORED_IFRAME_HINTS.any { lowerSrc.contains(it) }) continue

                        val fullNested = DlePlaylistParser.normalizeUrl(rawSrc, iframeUrl)
                        if (lowerSrc.contains("youtube.com/embed") || lowerSrc.contains("youtu.be")) {
                            youtubeTrailerFallbacks.add(fullNested)
                        } else {
                            candidateUrls.add(fullNested)
                        }
                    }

                    for (nested in candidateUrls + youtubeTrailerFallbacks) {
                        val res = resolvePlayerStreamsInternal(client, nested, iframeUrl, depth + 1, visited)
                        if (res.isNotEmpty()) return res
                    }
                }

                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun unpackJs(html: String): String {
        val sb = StringBuilder()
        val m = PACKED_JS_PATTERN.matcher(html)
        while (m.find()) {
            try {
                val payload = m.group(1)
                    ?.replace("\\'", "'")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?: continue
                val radix = m.group(2)?.toIntOrNull() ?: 36
                val count = m.group(3)?.toIntOrNull() ?: 0
                val words = (m.group(4) ?: "").split("|")

                val tokenMatcher = WORD_TOKEN_PATTERN.matcher(payload)
                val out = StringBuffer()
                while (tokenMatcher.find()) {
                    val token = tokenMatcher.group(1) ?: ""
                    val idx = decodeBaseN(token, radix)
                    val replacement = if (idx in 0 until count && idx < words.size && words[idx].isNotEmpty()) {
                        words[idx]
                    } else {
                        token
                    }
                    tokenMatcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement))
                }
                tokenMatcher.appendTail(out)
                sb.append(out.toString()).append('\n')
            } catch (_: Exception) {}
        }
        return sb.toString()
    }

    internal fun normalizeEscapedUrls(text: String): String {
        return text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
    }

    private fun decodeBaseN(token: String, radix: Int): Int {
        if (token.isEmpty()) return -1
        if (radix <= 36) {
            return token.toIntOrNull(radix.coerceIn(2, 36)) ?: -1
        }
        var value = 0
        for (ch in token) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> ch - 'a' + 10
                in 'A'..'Z' -> ch - 'A' + 36
                else -> return -1
            }
            if (digit >= radix) return -1
            value = value * radix + digit
        }
        return value
    }

    private fun parseSourcesBlock(block: String, referer: String): List<StreamQuality> {
        val list = mutableListOf<StreamQuality>()
        val items = block.split("}")
        for (item in items) {
            val fm = SOURCE_ITEM_FILE_PATTERN.matcher(item)
            if (!fm.find()) continue
            val url = fm.group(1)?.trim() ?: continue
            if (!url.startsWith("http")) continue
            val lm = SOURCE_ITEM_LABEL_PATTERN.matcher(item)
            val labelRaw = if (lm.find()) lm.group(1)?.trim() ?: "Auto" else "Auto"
            val height = Regex("(\\d{3,4})").find(labelRaw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 720
            val isHls = url.contains(".m3u8", ignoreCase = true)
            val sq = StreamQuality(labelRaw, height, url, isHls)
            sq.headers["Referer"] = referer
            list.add(sq)
        }
        list.sortByDescending { it.height }
        return list
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
}
