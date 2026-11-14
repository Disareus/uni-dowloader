package ua.aniua.downloader.extractor

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import ua.aniua.downloader.model.StreamQuality
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

object GenericPlayerResolver {

    private const val MAX_IFRAME_DEPTH = 3

    private val SIBNET_MP4_PATTERN = Pattern.compile("src\\s*:\\s*[\"'](/v/[^\"']+\\.mp4)[\"']")
    private val FILE_PATTERN = Pattern.compile(
        "[\"']?(?:file|hls|stream|video_url|videoUrl|streamUrl|srcUrl)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']"
    )
    private val SOURCES_ARRAY_PATTERN = Pattern.compile("[\"']?sources[\"']?\\s*:\\s*\\[([\\s\\S]*?)\\]")
    private val SOURCE_ITEM_FILE_PATTERN = Pattern.compile("[\"']?(?:file|src|url)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
    private val SOURCE_ITEM_LABEL_PATTERN = Pattern.compile("[\"']?(?:label|res|quality)[\"']?\\s*:\\s*[\"']?([^\"',}]+)[\"']?")
    private val M3U8_PATTERN = Pattern.compile("((?:https?:)?//[^\\s\"'<>\\\\]+?\\.m3u8[^\\s\"'<>\\\\]*)")
    private val MP4_PATTERN = Pattern.compile("((?:https?:)?//[^\\s\"'<>\\\\]+?\\.mp4[^\\s\"'<>\\\\]*)")
    private val JSON_LD_CONTENT_URL_PATTERN = Pattern.compile("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"")
    private val ATOB_PATTERN = Pattern.compile("atob\\(\\s*[\"']([A-Za-z0-9+/=]{12,})[\"']\\s*\\)")
    private val PACKED_JS_PATTERN = Pattern.compile(
        "eval\\(function\\(p,a,c,k,e,[dr]\\)\\{[\\s\\S]*?\\}\\(([\"'])([\\s\\S]*?)\\1,\\s*(\\d+),\\s*(\\d+),\\s*([\"'])([^\"']*)\\5\\.split\\([\"']\\|[\"']\\)",
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

        // 2. Прямий .m3u8 або HLS потік
        if (ExtractorUtils.isHlsUrl(url)) {
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
            val reqBuilder = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Referer", referer ?: iframeUrl)
            val refOrigin = ExtractorUtils.extractBaseOrigin(referer ?: iframeUrl, "")
            if (refOrigin.isNotEmpty()) {
                reqBuilder.header("Origin", refOrigin)
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful || resp.body == null) return emptyList()

                val finalUrl = resp.request.url.toString().ifEmpty { iframeUrl }
                val contentType = resp.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
                if (contentType.startsWith("video/")) {
                    return listOf(StreamQuality("1080p", 1080, finalUrl, false))
                }
                if (contentType.contains("mpegurl")) {
                    val resolved = HlsStreamResolver.resolveMasterPlaylist(client, finalUrl, referer ?: finalUrl)
                    if (resolved.isNotEmpty()) return resolved
                    return listOf(StreamQuality("Auto (HLS)", 720, finalUrl, true))
                }

                val html = resp.body?.string() ?: ""
                if (html.isEmpty()) return emptyList()

                val directTextStreams = extractDirectStreamsFromHtml(html, finalUrl, client)
                if (directTextStreams.isNotEmpty()) return directTextStreams

                val doc = Jsoup.parse(html, finalUrl)

                // 6. HTML5 <video src/data-src> та <source src/data-src>
                val videoEls = doc.select("video[src], video[data-src], video source[src], video source[data-src], source[src], source[data-src]")
                for (vEl in videoEls) {
                    val rawSrc = vEl.attr("src").trim().ifEmpty { vEl.attr("data-src").trim() }
                    val fullSrc = normalizeCandidateMediaUrl(rawSrc, finalUrl) ?: continue
                    if (isIgnoredAssetUrl(fullSrc)) continue
                    val isHls = fullSrc.contains(".m3u8", ignoreCase = true)
                    if (isHls) {
                        val resolved = HlsStreamResolver.resolveMasterPlaylist(client, fullSrc, finalUrl)
                        if (resolved.isNotEmpty()) return resolved
                    }
                    val q = StreamQuality(if (isHls) "Auto (HLS)" else "720p", 720, fullSrc, isHls)
                    q.headers["Referer"] = finalUrl
                    return listOf(q)
                }

                // 7. Data attributes pointing directly to media or nested embed
                val dataStreamNodes = doc.select("[data-file], [data-hls], [data-video], [data-mp4], [data-stream]")
                for (node in dataStreamNodes) {
                    val rawAttr = listOf("data-file", "data-hls", "data-video", "data-mp4", "data-stream")
                        .firstNotNullOfOrNull { node.attr(it).trim().takeIf { v -> v.isNotEmpty() } }
                        ?: continue
                    if (rawAttr.startsWith("blob:") || rawAttr.startsWith("data:") || rawAttr.startsWith("javascript:") || rawAttr == "#") continue
                    val fullAttrUrl = DlePlaylistParser.normalizeUrl(rawAttr, finalUrl)
                    if (ExtractorUtils.isDirectMediaUrl(fullAttrUrl)) {
                        val isHls = fullAttrUrl.contains(".m3u8", ignoreCase = true)
                        if (isHls) {
                            val resolved = HlsStreamResolver.resolveMasterPlaylist(client, fullAttrUrl, finalUrl)
                            if (resolved.isNotEmpty()) return resolved
                        }
                        val q = StreamQuality(if (isHls) "Auto (HLS)" else "720p", 720, fullAttrUrl, isHls)
                        q.headers["Referer"] = finalUrl
                        return listOf(q)
                    } else if (depth < MAX_IFRAME_DEPTH && fullAttrUrl.startsWith("http")) {
                        val nestedRes = resolvePlayerStreamsInternal(client, fullAttrUrl, finalUrl, depth + 1, visited)
                        if (nestedRes.isNotEmpty()) return nestedRes
                    }
                }

                // 8. Themesia Base64 <select class="mirror"> <option value="..."> & nested embeds
                if (depth < MAX_IFRAME_DEPTH) {
                    val mirrorOpts = doc.select("select.mirror option[value], select[name='mirror'] option[value]")
                    for (opt in mirrorOpts) {
                        val rawVal = opt.attr("value").trim()
                        if (rawVal.isEmpty()) continue
                        val decoded = ExtractorUtils.decodeBase64Utf8(rawVal)
                        if (decoded.isNotEmpty()) {
                            val ifrM = IFRAME_SRC_PATTERN.matcher(decoded)
                            val target = if (ifrM.find()) ifrM.group(1) else if (decoded.startsWith("http") || decoded.startsWith("//")) decoded else null
                            if (!target.isNullOrEmpty()) {
                                val normalized = DlePlaylistParser.normalizeUrl(target, finalUrl)
                                val res = resolvePlayerStreamsInternal(client, normalized, finalUrl, depth + 1, visited)
                                if (res.isNotEmpty()) return res
                            }
                        }
                    }

                    // Interactive embed/server links ([data-iframe], [data-embed], [data-player], .server-item[data-link])
                    val embedNodes = doc.select("[data-iframe], [data-embed], [data-player], .server-item[data-link]")
                    for (eNode in embedNodes) {
                        val rawEmbed = listOf("data-iframe", "data-embed", "data-player", "data-link")
                            .firstNotNullOfOrNull { eNode.attr(it).trim().takeIf { v -> v.isNotEmpty() } }
                            ?: continue
                        if (rawEmbed.startsWith("#") || rawEmbed.startsWith("javascript:")) continue
                        val fullEmbed = DlePlaylistParser.normalizeUrl(rawEmbed, finalUrl)
                        if (fullEmbed.startsWith("http")) {
                            val res = resolvePlayerStreamsInternal(client, fullEmbed, finalUrl, depth + 1, visited)
                            if (res.isNotEmpty()) return res
                        }
                    }

                    // Вкладені <iframe> на сторінці
                    val iframes = doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
                    val candidateUrls = mutableListOf<String>()
                    val youtubeTrailerFallbacks = mutableListOf<String>()

                    for (ifr in iframes) {
                        val rawSrc = extractIframeSrc(ifr) ?: continue
                        val lowerSrc = rawSrc.lowercase(Locale.ROOT)
                        if (IGNORED_IFRAME_HINTS.any { lowerSrc.contains(it) }) continue

                        val fullNested = DlePlaylistParser.normalizeUrl(rawSrc, finalUrl)
                        if (lowerSrc.contains("youtube.com/embed") || lowerSrc.contains("youtu.be")) {
                            youtubeTrailerFallbacks.add(fullNested)
                        } else {
                            candidateUrls.add(fullNested)
                        }
                    }

                    for (nested in candidateUrls + youtubeTrailerFallbacks) {
                        val res = resolvePlayerStreamsInternal(client, nested, finalUrl, depth + 1, visited)
                        if (res.isNotEmpty()) return res
                    }

                    // <iframe data-api="/vs_src.php?..."> wrappers
                    for (ifr in doc.select("iframe[data-api]")) {
                        val rawApi = ifr.attr("data-api").trim()
                        if (rawApi.isEmpty()) continue
                        val fullApi = DlePlaylistParser.normalizeUrl(rawApi, finalUrl)
                        if (fullApi.startsWith("http")) {
                            val apiBody = fetchAjaxJsonHtml(client, fullApi, finalUrl)
                            val nestedSrc = apiBody?.let {
                                try {
                                    JsonParser.parseString(it).asJsonObject?.get("src")
                                        ?.takeIf { s -> s.isJsonPrimitive }?.asString
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            if (!nestedSrc.isNullOrBlank()) {
                                val normalizedNested = DlePlaylistParser.normalizeUrl(nestedSrc, finalUrl)
                                val res = resolvePlayerStreamsInternal(client, normalizedNested, finalUrl, depth + 1, visited)
                                if (res.isNotEmpty()) return res
                            }
                        }
                    }

                    // VidSrc.buzz / 2Embed /pl/api.php stream resolution
                    val vidsrcBuzzStreams = resolveVidsrcBuzzStreams(client, html, finalUrl)
                    if (vidsrcBuzzStreams.isNotEmpty()) return vidsrcBuzzStreams

                    // CMS AJAX player servers -> sources (HiAnime / FlixHQ / SFlix / similar)
                    val ajaxStreams = resolveCmsAjaxServerStreams(client, doc, html, finalUrl, depth, visited)
                    if (ajaxStreams.isNotEmpty()) return ajaxStreams
                }

                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun extractDirectStreamsFromHtml(
        html: String,
        baseUrl: String,
        client: OkHttpClient? = null
    ): List<StreamQuality> {
        val unpacked = unpackJs(html)
        val decodedAtob = decodeAtobStrings(html)
        val combinedSearchText = normalizeEscapedUrls(
            buildString {
                append(html)
                if (unpacked.isNotEmpty()) {
                    append('\n').append(unpacked)
                }
                if (decodedAtob.isNotEmpty()) {
                    append('\n').append(decodedAtob)
                }
            }
        )

        // 1. PlayerJS file / hls / stream / video_url (включно з JSON масивами [{...}])
        val fm = FILE_PATTERN.matcher(combinedSearchText)
        while (fm.find()) {
            var fileStr = fm.group(1)?.trim() ?: ""
            if (fileStr.isEmpty() || fileStr.contains("{") && !fileStr.startsWith("[{")) continue
            if (fileStr.startsWith("[{")) {
                try {
                    val elem = JsonParser.parseString(fileStr)
                    val firstFile = findFirstFileInJson(elem)
                    if (!firstFile.isNullOrEmpty()) fileStr = firstFile
                } catch (_: Exception) {}
            }
            if (fileStr.startsWith("//")) {
                fileStr = DlePlaylistParser.normalizeUrl(fileStr, baseUrl)
            }
            if (isIgnoredAssetUrl(fileStr)) continue
            val qualities = PlayerJsDecoder.parsePlayerJsQualities(fileStr, baseUrl)
            if (qualities.isNotEmpty()) {
                val first = qualities.first()
                if (client != null && qualities.size == 1 && first.isHls && first.url.contains(".m3u8", ignoreCase = true)) {
                    val expanded = HlsStreamResolver.resolveMasterPlaylist(client, first.url, baseUrl)
                    if (expanded.isNotEmpty()) return expanded
                }
                return qualities
            }
        }

        // 2. JWPlayer / VideoJS sources: [...]
        val sm = SOURCES_ARRAY_PATTERN.matcher(combinedSearchText)
        while (sm.find()) {
            val block = sm.group(1) ?: continue
            val parsedSources = parseSourcesBlock(block, baseUrl)
            if (parsedSources.isNotEmpty()) {
                val first = parsedSources.first()
                if (client != null && parsedSources.size == 1 && first.isHls && first.url.contains(".m3u8", ignoreCase = true)) {
                    val expanded = HlsStreamResolver.resolveMasterPlaylist(client, first.url, baseUrl)
                    if (expanded.isNotEmpty()) return expanded
                }
                return parsedSources
            }
        }

        // 3. JSON-LD contentUrl
        val ldM = JSON_LD_CONTENT_URL_PATTERN.matcher(combinedSearchText)
        while (ldM.find()) {
            val rawContent = ldM.group(1)?.trim() ?: ""
            val contentUrl = normalizeCandidateMediaUrl(rawContent, baseUrl) ?: continue
            if (isIgnoredAssetUrl(contentUrl)) continue
            val isHls = contentUrl.contains(".m3u8", ignoreCase = true)
            if (isHls && client != null) {
                val resolved = HlsStreamResolver.resolveMasterPlaylist(client, contentUrl, baseUrl)
                if (resolved.isNotEmpty()) return resolved
            }
            val q = StreamQuality(if (isHls) "Auto (HLS)" else "720p", 720, contentUrl, isHls)
            q.headers["Referer"] = baseUrl
            return listOf(q)
        }

        // 4. Прямий .m3u8 у коді, atob або розпакованому JS
        val mm = M3U8_PATTERN.matcher(combinedSearchText)
        while (mm.find()) {
            val rawM3u8 = mm.group(1)?.trim() ?: ""
            val m3u8Url = normalizeCandidateMediaUrl(rawM3u8, baseUrl) ?: continue
            if (isIgnoredAssetUrl(m3u8Url) || m3u8Url.contains("{") || m3u8Url.contains("}")) continue
            if (client != null) {
                val resolved = HlsStreamResolver.resolveMasterPlaylist(client, m3u8Url, baseUrl)
                if (resolved.isNotEmpty()) return resolved
            }
            val q = StreamQuality("Auto (HLS)", 720, m3u8Url, true)
            q.headers["Referer"] = baseUrl
            return listOf(q)
        }

        // 5. Прямий .mp4 у коді, atob або розпакованому JS
        val mpm = MP4_PATTERN.matcher(combinedSearchText)
        while (mpm.find()) {
            val rawMp4 = mpm.group(1)?.trim() ?: ""
            val mp4Url = normalizeCandidateMediaUrl(rawMp4, baseUrl) ?: continue
            if (isIgnoredAssetUrl(mp4Url) || mp4Url.contains("{") || mp4Url.contains("}")) continue
            val q = StreamQuality("720p", 720, mp4Url, false)
            q.headers["Referer"] = baseUrl
            return listOf(q)
        }

        return emptyList()
    }

    private fun resolveCmsAjaxServerStreams(
        client: OkHttpClient,
        doc: org.jsoup.nodes.Document,
        html: String,
        pageUrl: String,
        depth: Int,
        visited: MutableSet<String>
    ): List<StreamQuality> {
        val baseOrigin = ExtractorUtils.extractBaseOrigin(pageUrl, "")
        if (baseOrigin.isEmpty()) return emptyList()

        // Already-rendered server elements with data-id
        val serverIds = linkedSetOf<String>()
        val directServerEls = doc.select(".server-item[data-id], [data-server-id], .link-item[data-id]")
        for (el in directServerEls) {
            val id = el.attr("data-id").trim().ifEmpty { el.attr("data-server-id").trim() }
            if (id.isNotEmpty()) serverIds.add(id)
        }

        val isV2Ajax = html.contains("/ajax/v2/episode/", ignoreCase = true)
        val isV1Ajax = html.contains("/ajax/episode/", ignoreCase = true)

        if (serverIds.isEmpty()) {
            val epId = doc.selectFirst("#wrapper[data-id], #servers-content[data-episode-id], [data-episode-id], #watch-block[data-id]")
                ?.let { it.attr("data-episode-id").trim().ifEmpty { it.attr("data-id").trim() } }
                ?.takeIf { it.isNotEmpty() }
                ?: Regex("(?:episodeId|episode_id)\\s*[:=]\\s*[\"']?(\\d+)[\"']?").find(html)?.groupValues?.getOrNull(1)

            if (!epId.isNullOrEmpty() && (isV2Ajax || isV1Ajax)) {
                val serversUrl = if (isV2Ajax) {
                    "$baseOrigin/ajax/v2/episode/servers?episodeId=$epId"
                } else {
                    "$baseOrigin/ajax/episode/servers/$epId"
                }
                val respBody = fetchAjaxJsonHtml(client, serversUrl, pageUrl)
                val htmlChunk = extractHtmlChunkFromAjax(respBody)
                if (!htmlChunk.isNullOrEmpty()) {
                    val sDoc = Jsoup.parse(htmlChunk, baseOrigin)
                    for (el in sDoc.select("[data-id]")) {
                        val id = el.attr("data-id").trim()
                        if (id.isNotEmpty()) serverIds.add(id)
                    }
                }
            }
        }

        if (serverIds.isEmpty() || (!isV2Ajax && !isV1Ajax)) return emptyList()

        for (sid in serverIds.take(5)) {
            val sourcesUrl = if (isV2Ajax) {
                "$baseOrigin/ajax/v2/episode/sources?id=$sid"
            } else {
                "$baseOrigin/ajax/episode/sources/$sid"
            }
            val srcBody = fetchAjaxJsonHtml(client, sourcesUrl, pageUrl) ?: continue
            val link = try {
                JsonParser.parseString(srcBody).asJsonObject?.get("link")?.takeIf { it.isJsonPrimitive }?.asString
            } catch (_: Exception) {
                null
            } ?: continue
            val fullEmbed = DlePlaylistParser.normalizeUrl(link, baseOrigin)
            if (fullEmbed.startsWith("http")) {
                val res = resolvePlayerStreamsInternal(client, fullEmbed, pageUrl, depth + 1, visited)
                if (res.isNotEmpty()) return res
            }
        }
        return emptyList()
    }

    internal fun extractIframeSrc(ifr: Element): String? {
        for (attr in listOf("src", "data-src", "data-lazy-src")) {
            val v = ifr.attr(attr).trim()
            if (v.isEmpty() || v.equals("about:blank", ignoreCase = true) ||
                v.startsWith("javascript:", ignoreCase = true) || v == "#"
            ) {
                continue
            }
            return v
        }
        return null
    }

    internal fun parseVidsrcBuzzConfig(html: String): JsonObject? {
        val marker = Pattern.compile("var\\s+Q\\s*=\\s*\\{").matcher(html)
        if (!marker.find()) return null
        val start = html.indexOf('{', marker.start())
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        val jsonStr = html.substring(start, i + 1)
                        return try {
                            val elem = JsonParser.parseString(jsonStr)
                            if (elem.isJsonObject) elem.asJsonObject else null
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
        }
        return null
    }

    private fun resolveVidsrcBuzzStreams(
        client: OkHttpClient,
        html: String,
        finalUrl: String
    ): List<StreamQuality> {
        val cfg = parseVidsrcBuzzConfig(html) ?: return emptyList()
        val token = cfg.get("t")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: return emptyList()
        val baseOrigin = ExtractorUtils.extractBaseOrigin(finalUrl, "")
        if (baseOrigin.isEmpty()) return emptyList()

        fun tryMintServer(ref: String, fresh: Boolean): List<StreamQuality> {
            val playUrl = "$baseOrigin/pl/api.php?a=play&ref=${URLEncoder.encode(ref, "UTF-8")}" +
                "&t=${URLEncoder.encode(token, "UTF-8")}${if (fresh) "&fresh=1" else ""}"
            val respBody = fetchAjaxJsonHtml(client, playUrl, finalUrl) ?: return emptyList()
            val obj = try {
                JsonParser.parseString(respBody).asJsonObject
            } catch (_: Exception) {
                null
            } ?: return emptyList()
            val rawStreamUrl = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val streamType = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase(Locale.ROOT) ?: "hls"
            val fullStreamUrl = DlePlaylistParser.normalizeUrl(rawStreamUrl, baseOrigin)
            val isHls = streamType == "hls" || ExtractorUtils.isHlsUrl(fullStreamUrl)
            if (isHls) {
                val resolved = HlsStreamResolver.resolveMasterPlaylist(client, fullStreamUrl, finalUrl)
                if (resolved.isNotEmpty()) {
                    resolved.forEach { sq ->
                        sq.headers.putIfAbsent("Referer", finalUrl)
                        sq.headers.putIfAbsent("Origin", baseOrigin)
                    }
                    return resolved
                }
            }
            val sq = StreamQuality(if (isHls) "Auto (HLS)" else "720p", 720, fullStreamUrl, isHls)
            sq.headers["Referer"] = finalUrl
            sq.headers["Origin"] = baseOrigin
            return listOf(sq)
        }

        val initialRefs = mutableListOf<String>()
        try {
            val ssr = cfg.getAsJsonObject("ssr")
            val servers = ssr?.getAsJsonArray("servers")
            if (servers != null) {
                for (srv in servers) {
                    val ref = srv.takeIf { it.isJsonObject }?.asJsonObject?.get("ref")
                        ?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                    if (!ref.isNullOrEmpty()) initialRefs.add(ref)
                }
            }
        } catch (_: Exception) {}

        for (ref in initialRefs) {
            val direct = tryMintServer(ref, fresh = false)
            if (direct.isNotEmpty()) return direct
            val fresh = tryMintServer(ref, fresh = true)
            if (fresh.isNotEmpty()) return fresh
        }

        val type = cfg.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty().ifEmpty { "tv" }
        val id = cfg.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val s = cfg.get("s")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val e = cfg.get("e")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (id.isNotEmpty()) {
            val sourcesUrl = buildString {
                append("$baseOrigin/pl/api.php?a=sources&type=${URLEncoder.encode(type, "UTF-8")}")
                append("&id=${URLEncoder.encode(id, "UTF-8")}")
                if (s.isNotEmpty()) append("&s=${URLEncoder.encode(s, "UTF-8")}")
                if (e.isNotEmpty()) append("&e=${URLEncoder.encode(e, "UTF-8")}")
                append("&t=${URLEncoder.encode(token, "UTF-8")}&refresh=1")
            }
            val srcBody = fetchAjaxJsonHtml(client, sourcesUrl, finalUrl)
            if (!srcBody.isNullOrBlank()) {
                try {
                    val servers = JsonParser.parseString(srcBody).asJsonObject?.getAsJsonArray("servers")
                    if (servers != null) {
                        for (srv in servers) {
                            val ref = srv.takeIf { it.isJsonObject }?.asJsonObject?.get("ref")
                                ?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: continue
                            val direct = tryMintServer(ref, fresh = false)
                            if (direct.isNotEmpty()) return direct
                            val fresh = tryMintServer(ref, fresh = true)
                            if (fresh.isNotEmpty()) return fresh
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return emptyList()
    }

    private fun fetchAjaxJsonHtml(client: OkHttpClient, url: String, referer: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Referer", referer)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            ExtractorUtils.executeForString(client, req)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHtmlChunkFromAjax(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("<")) return trimmed
        return try {
            val obj = JsonParser.parseString(trimmed).asJsonObject
            when {
                obj.has("html") && obj.get("html").isJsonPrimitive -> obj.get("html").asString
                else -> trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    internal fun decodeAtobStrings(html: String): String {
        val sb = StringBuilder()
        val m = ATOB_PATTERN.matcher(html)
        while (m.find()) {
            val b64 = m.group(1) ?: continue
            val decoded = ExtractorUtils.decodeBase64Utf8(b64)
            if (decoded.isNotEmpty() && (
                    decoded.contains("http", ignoreCase = true) ||
                        decoded.contains(".m3u8", ignoreCase = true) ||
                        decoded.contains(".mp4", ignoreCase = true) ||
                        decoded.contains("<iframe", ignoreCase = true) ||
                        decoded.startsWith("//")
                    )
            ) {
                sb.append(decoded).append('\n')
            }
        }
        return sb.toString()
    }

    private fun normalizeCandidateMediaUrl(raw: String, baseUrl: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("blob:") || trimmed.startsWith("data:") ||
            trimmed.startsWith("javascript:") || trimmed == "#"
        ) {
            return null
        }
        val normalized = DlePlaylistParser.normalizeUrl(trimmed, baseUrl)
        return normalized.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun isIgnoredAssetUrl(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").lowercase(Locale.ROOT)
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".svg") ||
            clean.endsWith(".vtt") || clean.endsWith(".srt") || clean.endsWith(".css") ||
            clean.endsWith(".js")
    }

    internal fun unpackJs(html: String): String {
        val sb = StringBuilder()
        val m = PACKED_JS_PATTERN.matcher(html)
        while (m.find()) {
            try {
                val payload = m.group(2)
                    ?.replace("\\'", "'")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?: continue
                val radix = m.group(3)?.toIntOrNull() ?: 36
                val count = m.group(4)?.toIntOrNull() ?: 0
                val words = (m.group(6) ?: "").split("|")

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
            val rawUrl = fm.group(1)?.trim() ?: continue
            val url = normalizeCandidateMediaUrl(rawUrl, referer) ?: continue
            if (isIgnoredAssetUrl(url)) continue
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
