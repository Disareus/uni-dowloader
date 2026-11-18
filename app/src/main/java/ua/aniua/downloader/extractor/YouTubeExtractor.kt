package ua.aniua.downloader.extractor

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import ua.aniua.downloader.model.StreamQuality
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object YouTubeExtractor {

    private const val VISIONOS_CLIENT_VERSION = "1.04"
    private const val VISIONOS_DEVICE_MODEL = "RealityDevice17,1"
    private const val VISIONOS_OS_VERSION = "26.6.0.23O770"
    internal const val VISIONOS_USER_AGENT =
        "com.google.visionos.youtube/$VISIONOS_CLIENT_VERSION($VISIONOS_DEVICE_MODEL; U; CPU visionOS 26_6_0 like Mac OS X; UA)"
    private const val ANDROID_VR_CLIENT_VERSION = "1.62.27"
    internal const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/$ANDROID_VR_CLIENT_VERSION (Linux; U; Android 12L; Quest 3 Build/SQ3A.220605.009.A1)"
    private const val ANDROID_CLIENT_VERSION = "20.10.38"
    internal const val ANDROID_USER_AGENT =
        "com.google.android.youtube/$ANDROID_CLIENT_VERSION (Linux; U; Android 15; uk_UA)"
    private const val IOS_CLIENT_VERSION = "20.10.4"
    internal const val IOS_USER_AGENT =
        "com.google.ios.youtube/$IOS_CLIENT_VERSION (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"
    internal const val MWEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
    private const val DEFAULT_MWEB_CLIENT_VERSION = "2.20250312.04.00"
    private const val STREAM_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val VISITOR_DATA_TTL_MS = 30 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 64
    private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val random = SecureRandom()

    private class BoundedLruCache<K, V>(private val maxEntries: Int) {
        private val map = object : LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > maxEntries
            }
        }

        @Synchronized
        operator fun get(key: K): V? = map[key]

        @Synchronized
        operator fun set(key: K, value: V) {
            map[key] = value
        }

        @Synchronized
        fun remove(key: K): V? = map.remove(key)

        @Synchronized
        fun size(): Int = map.size
    }

    private data class CachedVideoStreams(
        val qualities: List<StreamQuality>,
        val cachedAtMs: Long
    )

    internal data class ParsedInnertubeResult(
        val mediaInfo: MediaInfo,
        val qualities: List<StreamQuality>
    )

    private val streamCache = BoundedLruCache<String, CachedVideoStreams>(MAX_CACHE_ENTRIES)
    private val unverifiedHigherQualityVideos = BoundedLruCache<String, Boolean>(MAX_CACHE_ENTRIES)

    @Volatile
    private var cachedVisionOsVisitorData: Pair<String, Long>? = null

    private val YOUTUBE_URL_PATTERNS = listOf(
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.|music\\.)?youtube(?:-nocookie)?\\.com/watch\\?(?:.*&)?v=([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.)?youtu\\.be/([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.|music\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/live/([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?youtube(?:-nocookie)?\\.com/embed/([a-zA-Z0-9_-]{11})"),
        Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/v/([a-zA-Z0-9_-]{11})")
    )

    fun isYouTubeUrl(url: String): Boolean {
        val clean = url.trim()
        return YOUTUBE_URL_PATTERNS.any { it.matcher(clean).find() }
    }

    fun extractVideoId(url: String): String? {
        val clean = url.trim()
        for (pattern in YOUTUBE_URL_PATTERNS) {
            val matcher = pattern.matcher(clean)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    fun parseVideo(client: OkHttpClient, pageUrl: String): MediaInfo {
        val cleanUrl = pageUrl.trim()
        val videoId = extractVideoId(cleanUrl)
            ?: throw IllegalArgumentException("Не вдалося розпізнати ID відео YouTube: $pageUrl")

        // 1. YouTube Innertube API (ANDROID 20.10 -> IOS 20.10 -> TVHTML5 embed)
        val innertube = fetchInnertube(client, videoId, cleanUrl)
        if (innertube != null) {
            cacheStreams(videoId, innertube.qualities)
            return innertube.mediaInfo
        }

        // 2. Публічний oEmbed fallback (хоча б коректна назва, автор і обкладинка)
        val oEmbedMeta = fetchOEmbedMetadata(client, videoId)
        return buildFallbackMediaInfo(videoId, cleanUrl, oEmbedMeta?.first, oEmbedMeta?.second)
    }

    fun resolveStreams(client: OkHttpClient, pageUrl: String): List<StreamQuality> {
        val videoId = extractVideoId(pageUrl) ?: return emptyList()
        val now = System.currentTimeMillis()
        val cached = streamCache[videoId]
        if (cached != null && now - cached.cachedAtMs < STREAM_CACHE_TTL_MS && cached.qualities.isNotEmpty()) {
            return cached.qualities.map { it.copy(headers = it.headers.toMutableMap()) }
        }
        val fetched = fetchInnertube(client, videoId, pageUrl) ?: return emptyList()
        cacheStreams(videoId, fetched.qualities)
        return fetched.qualities.map { it.copy(headers = it.headers.toMutableMap()) }
    }

    private fun cacheStreams(videoId: String, qualities: List<StreamQuality>) {
        if (qualities.isNotEmpty()) {
            streamCache[videoId] = CachedVideoStreams(qualities, System.currentTimeMillis())
        }
    }

    fun invalidateCache(pageUrl: String) {
        extractVideoId(pageUrl)?.let {
            streamCache.remove(it)
            unverifiedHigherQualityVideos.remove(it)
        }
    }

    fun needsWebViewRescue(pageUrl: String): Boolean {
        val videoId = extractVideoId(pageUrl) ?: return false
        val maxCachedHeight = streamCache[videoId]?.qualities?.maxOfOrNull { it.height } ?: 0
        if (maxCachedHeight > 360) return false
        return unverifiedHigherQualityVideos[videoId] == true || maxCachedHeight == 0
    }

    fun resolveWithMwebPoToken(
        client: OkHttpClient,
        pageUrl: String,
        poToken: String,
        visitorData: String,
        signatureTimestamp: Int?,
        clientVersion: String?,
        cookieHeader: String? = null
    ): List<StreamQuality> {
        val videoId = extractVideoId(pageUrl) ?: return emptyList()
        if (poToken.isBlank() || visitorData.isBlank()) return emptyList()

        val payload = buildMwebInnertubePayload(
            videoId = videoId,
            visitorData = visitorData,
            clientVersion = clientVersion?.takeIf { it.isNotBlank() } ?: DEFAULT_MWEB_CLIENT_VERSION,
            signatureTimestamp = signatureTimestamp,
            poToken = poToken
        )
        val extraHeaders = mutableMapOf(
            "X-Goog-Visitor-Id" to visitorData,
            "Origin" to "https://m.youtube.com",
            "Referer" to "https://m.youtube.com/watch?v=$videoId"
        )
        if (!cookieHeader.isNullOrBlank()) {
            extraHeaders["Cookie"] = cookieHeader
        }

        val parsed = executeInnertubeRequest(
            client = client,
            payload = payload,
            userAgent = MWEB_USER_AGENT,
            videoId = videoId,
            pageUrl = pageUrl,
            extraRequestHeaders = extraHeaders
        ) ?: return emptyList()

        val encodedPot = URLEncoder.encode(poToken, "UTF-8")
        val potQualities = parsed.qualities.map { sq ->
            val withPotUrl = appendQueryParamIfMissing(sq.url, "pot", encodedPot)
            val withPotAudio = sq.audioUrl?.let { appendQueryParamIfMissing(it, "pot", encodedPot) }
            sq.copy(url = withPotUrl, audioUrl = withPotAudio, headers = sq.headers.toMutableMap()).apply {
                headers["User-Agent"] = MWEB_USER_AGENT
                if (!cookieHeader.isNullOrBlank()) {
                    headers["Cookie"] = cookieHeader
                }
            }
        }

        val working = filterWorkingAndExpandHls(client, potQualities, MWEB_USER_AGENT)
        if (working.isEmpty()) return emptyList()

        val existing = streamCache[videoId]?.qualities.orEmpty()
        val mergedByHeight = linkedMapOf<Int, StreamQuality>()
        for (q in working) {
            mergedByHeight[q.height] = q
        }
        for (q in existing) {
            if (!mergedByHeight.containsKey(q.height)) {
                mergedByHeight[q.height] = q
            }
        }
        val merged = mergedByHeight.values.sortedByDescending { it.height }
        cacheStreams(videoId, merged)
        if (merged.any { it.height > 360 }) {
            unverifiedHigherQualityVideos[videoId] = false
        }
        return merged.map { it.copy(headers = it.headers.toMutableMap()) }
    }

    private fun appendQueryParamIfMissing(url: String, key: String, encodedValue: String): String {
        if (url.contains("?$key=", ignoreCase = true) || url.contains("&$key=", ignoreCase = true)) {
            return url
        }
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep$key=$encodedValue"
    }

    fun resolveFallbackStreams(client: OkHttpClient, pageUrl: String): List<StreamQuality> {
        val videoId = extractVideoId(pageUrl) ?: return emptyList()
        val collected = mutableListOf<StreamQuality>()
        for (req in buildInnertubeClients(client, videoId)) {
            val parsed = executeInnertubeRequest(
                client = client,
                payload = req.payload,
                userAgent = req.userAgent,
                videoId = videoId,
                pageUrl = pageUrl,
                extraRequestHeaders = req.extraHeaders,
                endpointUrl = req.endpointUrl,
                cpn = req.cpn,
                invalidateVisionOsOnBotCheck = req.isVisionOs
            ) ?: continue
            val working = filterWorkingAndExpandHls(client, parsed.qualities, req.userAgent)
            collected.addAll(working)
        }
        if (collected.isNotEmpty()) {
            cacheStreams(videoId, collected)
        }
        return collected.map { it.copy(headers = it.headers.toMutableMap()) }
    }

    private fun filterWorkingAndExpandHls(
        client: OkHttpClient,
        qualities: List<StreamQuality>,
        userAgent: String
    ): List<StreamQuality> {
        val audioCache = mutableMapOf<String, Boolean>()
        val working = mutableListOf<StreamQuality>()
        val hlsCandidates = mutableListOf<StreamQuality>()
        var maxNonHlsHeight = 0

        for (q in qualities) {
            if (q.isHls && q.label.startsWith("Auto", ignoreCase = true)) {
                hlsCandidates.add(q)
                continue
            }
            if (q.height > maxNonHlsHeight) {
                maxNonHlsHeight = q.height
            }
            val extraHeaders = q.headers
            if (!isStreamReachable(client, q.url, userAgent, q.isHls, extraHeaders)) continue
            val audio = q.audioUrl
            if (!audio.isNullOrEmpty()) {
                val audioOk = audioCache.getOrPut(audio) {
                    isStreamReachable(client, audio, userAgent, false, extraHeaders)
                }
                if (!audioOk) continue
            }
            working.add(q)
        }

        val maxWorkingHeight = working.maxOfOrNull { it.height } ?: 0
        val shouldExpandHls = hlsCandidates.isNotEmpty() &&
            (working.isEmpty() || maxWorkingHeight <= 360 || maxWorkingHeight < maxNonHlsHeight)

        if (shouldExpandHls) {
            var expandedHls: List<StreamQuality>? = null
            for (hlsQ in hlsCandidates) {
                val expanded = HlsStreamResolver.resolveMasterPlaylist(client, hlsQ.url, null, userAgent)
                if (expanded.isNotEmpty()) {
                    expandedHls = expanded
                    break
                }
            }
            if (expandedHls != null) {
                val coveredHeights = working.map { it.height }.toSet()
                if (working.isEmpty()) {
                    working.addAll(expandedHls)
                } else {
                    for (hlsQ in expandedHls) {
                        if (hlsQ.height !in coveredHeights) {
                            working.add(hlsQ)
                        }
                    }
                }
            }
        }
        working.sortByDescending { it.height }
        return working
    }

    private data class InnertubeClientRequest(
        val endpointUrl: String,
        val payload: JsonObject,
        val userAgent: String,
        val extraHeaders: Map<String, String> = emptyMap(),
        val cpn: String? = null,
        val isVisionOs: Boolean = false
    )

    private fun generateNonce(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(CPN_ALPHABET[random.nextInt(CPN_ALPHABET.length)])
        }
        return sb.toString()
    }

    private fun buildInnertubeClients(client: OkHttpClient, videoId: String): List<InnertubeClientRequest> {
        val list = mutableListOf<InnertubeClientRequest>()
        val visionOsVisitorData = getOrFetchVisionOsVisitorData(client)
        if (!visionOsVisitorData.isNullOrBlank()) {
            val cpn = generateNonce(16)
            val tParam = generateNonce(12)
            list.add(
                InnertubeClientRequest(
                    endpointUrl = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&t=$tParam&id=$videoId",
                    payload = buildVisionOsInnertubePayload(videoId, visionOsVisitorData, cpn),
                    userAgent = VISIONOS_USER_AGENT,
                    extraHeaders = mapOf("X-Goog-Api-Format-Version" to "2"),
                    cpn = cpn,
                    isVisionOs = true
                )
            )
        }
        val defaultUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        list.add(InnertubeClientRequest(defaultUrl, buildAndroidVrInnertubePayload(videoId), ANDROID_VR_USER_AGENT))
        list.add(InnertubeClientRequest(defaultUrl, buildIosInnertubePayload(videoId), IOS_USER_AGENT))
        list.add(InnertubeClientRequest(defaultUrl, buildAndroidInnertubePayload(videoId), ANDROID_USER_AGENT))
        return list
    }

    private fun getOrFetchVisionOsVisitorData(client: OkHttpClient): String? {
        val now = System.currentTimeMillis()
        val cached = cachedVisionOsVisitorData
        if (cached != null && now - cached.second < VISITOR_DATA_TTL_MS) {
            return cached.first
        }
        return try {
            val payload = JsonObject().apply {
                add("context", buildVisionOsContextObject(null))
            }
            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                .header("User-Agent", VISIONOS_USER_AGENT)
                .header("X-Goog-Api-Format-Version", "2")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()
            val body = ExtractorUtils.executeForString(client, req) ?: return null
            val vd = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
                ?.objOrNull("responseContext")
                ?.strOrEmpty("visitorData")
                ?.takeIf { it.isNotEmpty() }
            if (vd != null) {
                cachedVisionOsVisitorData = vd to now
            }
            vd
        } catch (_: Exception) {
            null
        }
    }

    private fun executeInnertubeRequest(
        client: OkHttpClient,
        payload: JsonObject,
        userAgent: String,
        videoId: String,
        pageUrl: String,
        extraRequestHeaders: Map<String, String> = emptyMap(),
        endpointUrl: String = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        cpn: String? = null,
        invalidateVisionOsOnBotCheck: Boolean = false
    ): ParsedInnertubeResult? {
        return try {
            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val reqBuilder = Request.Builder()
                .url(endpointUrl)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .post(requestBody)
            for ((k, v) in extraRequestHeaders) {
                if (v.isNotEmpty()) reqBuilder.header(k, v)
            }

            val respString = ExtractorUtils.executeForString(client, reqBuilder.build()) ?: return null
            if (invalidateVisionOsOnBotCheck && respString.contains("LOGIN_REQUIRED")) {
                cachedVisionOsVisitorData = null
            }
            val parsed = parseInnertubeResponse(respString, videoId, pageUrl) ?: return null
            val encodedCpn = cpn?.takeIf { it.isNotBlank() }?.let { URLEncoder.encode(it, "UTF-8") }
            val updatedQualities = parsed.qualities.map { sq ->
                val finalUrl = if (encodedCpn != null && !sq.isHls) {
                    appendQueryParamIfMissing(sq.url, "cpn", encodedCpn)
                } else {
                    sq.url
                }
                val finalAudio = if (encodedCpn != null && !sq.audioUrl.isNullOrEmpty()) {
                    appendQueryParamIfMissing(sq.audioUrl!!, "cpn", encodedCpn)
                } else {
                    sq.audioUrl
                }
                sq.copy(url = finalUrl, audioUrl = finalAudio, headers = sq.headers.toMutableMap()).apply {
                    headers["User-Agent"] = userAgent
                }
            }
            ParsedInnertubeResult(parsed.mediaInfo, updatedQualities)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Перевіряє реальну доступність потоку повним GET-запитом без Range:
     * YouTube часто віддає 206 на Range: bytes=0-0, але блокує повне завантаження з HTTP 403.
     */
    private fun isStreamReachable(
        client: OkHttpClient,
        url: String,
        userAgent: String,
        isHls: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ): Boolean {
        return try {
            val builder = Request.Builder()
                .url(url)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("User-Agent", userAgent)
            val cookie = extraHeaders["Cookie"]
            if (!cookie.isNullOrBlank()) {
                builder.header("Cookie", cookie)
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                if (!isHls) {
                    val ct = resp.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
                    if (ct.startsWith("text/") || ct.startsWith("image/")) return false
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchInnertube(client: OkHttpClient, videoId: String, pageUrl: String): ParsedInnertubeResult? {
        var bestResult: ParsedInnertubeResult? = null
        var bestMaxHeight = -1
        var sawUnverifiedHigherQuality = false
        var bestUnverifiedMeta: MediaInfo? = null

        for (req in buildInnertubeClients(client, videoId)) {
            val parsed = executeInnertubeRequest(
                client = client,
                payload = req.payload,
                userAgent = req.userAgent,
                videoId = videoId,
                pageUrl = pageUrl,
                extraRequestHeaders = req.extraHeaders,
                endpointUrl = req.endpointUrl,
                cpn = req.cpn,
                invalidateVisionOsOnBotCheck = req.isVisionOs
            ) ?: continue
            if (bestUnverifiedMeta == null) bestUnverifiedMeta = parsed.mediaInfo
            val maxUnverifiedHeight = parsed.qualities.maxOfOrNull { it.height } ?: 0
            if (maxUnverifiedHeight > 360) {
                sawUnverifiedHigherQuality = true
            }

            val workingQualities = filterWorkingAndExpandHls(client, parsed.qualities, req.userAgent)
            if (workingQualities.isNotEmpty()) {
                val maxWorkingHeight = workingQualities.maxOf { it.height }
                val labels = workingQualities.map { it.label }.distinct().toMutableList()
                val updatedInfo = parsed.mediaInfo.copy(availableQualities = labels)
                val candidate = ParsedInnertubeResult(updatedInfo, workingQualities)
                if (bestResult == null || maxWorkingHeight > bestMaxHeight ||
                    (maxWorkingHeight == bestMaxHeight && workingQualities.size > bestResult.qualities.size)
                ) {
                    bestResult = candidate
                    bestMaxHeight = maxWorkingHeight
                }
                if (maxWorkingHeight > 360 && maxWorkingHeight >= maxUnverifiedHeight) {
                    unverifiedHigherQualityVideos[videoId] = false
                    return candidate
                }
            }
        }

        if (bestResult != null) {
            unverifiedHigherQualityVideos[videoId] = sawUnverifiedHigherQuality && bestMaxHeight <= 360
            return bestResult
        }

        if (bestUnverifiedMeta != null) {
            unverifiedHigherQualityVideos[videoId] = sawUnverifiedHigherQuality
            return ParsedInnertubeResult(
                bestUnverifiedMeta.copy(availableQualities = mutableListOf()),
                emptyList()
            )
        }
        return null
    }

    private fun buildVisionOsContextObject(visitorData: String?): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("clientName", "VISIONOS")
            addProperty("clientVersion", VISIONOS_CLIENT_VERSION)
            addProperty("clientScreen", "WATCH")
            addProperty("platform", "MOBILE")
            addProperty("deviceMake", "Apple")
            addProperty("deviceModel", VISIONOS_DEVICE_MODEL)
            addProperty("osName", "visionOS")
            addProperty("osVersion", VISIONOS_OS_VERSION)
            addProperty("hl", "uk")
            addProperty("gl", "UA")
            addProperty("utcOffsetMinutes", 0)
            if (!visitorData.isNullOrBlank()) {
                addProperty("visitorData", visitorData)
            }
        }
        val requestObj = JsonObject().apply {
            add("internalExperimentFlags", JsonArray())
            addProperty("useSsl", true)
        }
        val userObj = JsonObject().apply {
            addProperty("lockedSafetyMode", false)
        }
        return JsonObject().apply {
            add("client", clientObj)
            add("request", requestObj)
            add("user", userObj)
        }
    }

    private fun buildVisionOsInnertubePayload(
        videoId: String,
        visitorData: String,
        cpn: String
    ): JsonObject {
        return JsonObject().apply {
            add("context", buildVisionOsContextObject(visitorData))
            addProperty("videoId", videoId)
            addProperty("cpn", cpn)
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
        }
    }

    private fun buildAndroidVrClientObject(visitorData: String?): JsonObject {
        return JsonObject().apply {
            addProperty("clientName", "ANDROID_VR")
            addProperty("clientVersion", ANDROID_VR_CLIENT_VERSION)
            addProperty("deviceMake", "Oculus")
            addProperty("deviceModel", "Quest 3")
            addProperty("osName", "Android")
            addProperty("osVersion", "12L")
            addProperty("androidSdkVersion", 32)
            addProperty("hl", "uk")
            addProperty("gl", "UA")
            if (!visitorData.isNullOrBlank()) {
                addProperty("visitorData", visitorData)
            }
        }
    }

    private fun buildAndroidVrInnertubePayload(videoId: String, visitorData: String? = null): JsonObject {
        val contextObj = JsonObject().apply { add("client", buildAndroidVrClientObject(visitorData)) }
        return JsonObject().apply {
            addProperty("videoId", videoId)
            add("context", contextObj)
        }
    }

    private fun buildMwebInnertubePayload(
        videoId: String,
        visitorData: String,
        clientVersion: String,
        signatureTimestamp: Int?,
        poToken: String
    ): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("clientName", "MWEB")
            addProperty("clientVersion", clientVersion)
            addProperty("visitorData", visitorData)
            addProperty("hl", "uk")
            addProperty("gl", "UA")
        }
        val contextObj = JsonObject().apply { add("client", clientObj) }
        return JsonObject().apply {
            addProperty("videoId", videoId)
            add("context", contextObj)
            if (signatureTimestamp != null && signatureTimestamp > 0) {
                add("playbackContext", JsonObject().apply {
                    add("contentPlaybackContext", JsonObject().apply {
                        addProperty("signatureTimestamp", signatureTimestamp)
                    })
                })
            }
            add("serviceIntegrityDimensions", JsonObject().apply {
                addProperty("poToken", poToken)
            })
        }
    }

    private fun buildAndroidInnertubePayload(videoId: String): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("clientName", "ANDROID")
            addProperty("clientVersion", ANDROID_CLIENT_VERSION)
            addProperty("androidSdkVersion", 35)
            addProperty("hl", "uk")
            addProperty("gl", "UA")
        }
        val contextObj = JsonObject().apply { add("client", clientObj) }
        return JsonObject().apply {
            addProperty("videoId", videoId)
            add("context", contextObj)
        }
    }

    private fun buildIosInnertubePayload(videoId: String): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("clientName", "IOS")
            addProperty("clientVersion", IOS_CLIENT_VERSION)
            addProperty("deviceMake", "Apple")
            addProperty("deviceModel", "iPhone16,2")
            addProperty("osName", "iPhone")
            addProperty("osVersion", "18.3.2.22D82")
            addProperty("hl", "uk")
            addProperty("gl", "UA")
        }
        val contextObj = JsonObject().apply { add("client", clientObj) }
        return JsonObject().apply {
            addProperty("videoId", videoId)
            add("context", contextObj)
        }
    }

    private fun buildTvEmbedInnertubePayload(videoId: String): JsonObject {
        val clientObj = JsonObject().apply {
            addProperty("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
            addProperty("clientVersion", "2.0")
            addProperty("hl", "uk")
            addProperty("gl", "UA")
        }
        val thirdParty = JsonObject().apply {
            addProperty("embedUrl", "https://www.youtube.com/")
        }
        val contextObj = JsonObject().apply {
            add("client", clientObj)
            add("thirdParty", thirdParty)
        }
        return JsonObject().apply {
            addProperty("videoId", videoId)
            add("context", contextObj)
        }
    }

    internal fun parseInnertubeResponse(
        respString: String,
        videoId: String,
        pageUrl: String
    ): ParsedInnertubeResult? {
        return try {
            val root = JsonParser.parseString(respString).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val streamingData = root.objOrNull("streamingData") ?: return null

            val videoDetails = root.objOrNull("videoDetails")
            val title = videoDetails?.strOrEmpty("title")?.ifEmpty { "YouTube Відео" } ?: "YouTube Відео"
            val author = videoDetails?.strOrEmpty("author") ?: ""
            val description = videoDetails?.strOrEmpty("shortDescription")?.ifEmpty { null }
            val thumbnail = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

            val muxedQualities = mutableListOf<StreamQuality>()
            val formats = streamingData.arrOrNull("formats")
            if (formats != null) {
                for (el in formats) {
                    val f = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val streamUrl = f.strOrEmpty("url")
                    if (streamUrl.isEmpty()) continue
                    val qLabel = f.strOrEmpty("qualityLabel")
                    val rawWidth = f.intOrDefault("width", 0)
                    val rawHeight = f.intOrDefault("height", 0)
                    val height = ExtractorUtils.normalizeResolutionTier(rawWidth, rawHeight, qLabel)
                        .let { if (it > 0) it else 360 }
                    val label = qLabel.ifEmpty { "${height}p" }
                    muxedQualities.add(StreamQuality(label, height, streamUrl, false))
                }
            }

            val hlsUrl = streamingData.strOrEmpty("hlsManifestUrl")

            // Вибираємо найкращу аудіодоріжку окремо для MP4 (AAC) та WebM (Opus)
            val adaptive = streamingData.arrOrNull("adaptiveFormats")
            var bestMp4AudioUrl: String? = null
            var bestMp4AudioScore = -1
            var bestWebmAudioUrl: String? = null
            var bestWebmAudioScore = -1
            if (adaptive != null) {
                for (el in adaptive) {
                    val f = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val mime = f.strOrEmpty("mimeType").lowercase(Locale.ROOT)
                    val audioUrl = f.strOrEmpty("url")
                    if (audioUrl.isEmpty()) continue
                    val itag = f.intOrDefault("itag", 0)
                    val bitrate = f.intOrDefault("bitrate", 0)
                    if (mime.startsWith("audio/mp4")) {
                        val score = (if (itag == 140) 1_000_000 else 0) + bitrate
                        if (score > bestMp4AudioScore) {
                            bestMp4AudioScore = score
                            bestMp4AudioUrl = audioUrl
                        }
                    } else if (mime.startsWith("audio/webm")) {
                        val score = (if (itag == 251) 1_000_000 else 0) + bitrate
                        if (score > bestWebmAudioScore) {
                            bestWebmAudioScore = score
                            bestWebmAudioUrl = audioUrl
                        }
                    }
                }
            }

            // Адаптивні відеопотоки (включаючи 2K/4K/8K у VP9/WebM та AV1/MP4, а також H.264/MP4 до 1080p)
            val adaptiveCandidates = mutableListOf<Pair<Long, StreamQuality>>()
            if (adaptive != null) {
                for (el in adaptive) {
                    val f = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val mime = f.strOrEmpty("mimeType").lowercase(Locale.ROOT)
                    val isMp4Video = mime.startsWith("video/mp4")
                    val isWebmVideo = mime.startsWith("video/webm")
                    if (!isMp4Video && !isWebmVideo) continue
                    val streamUrl = f.strOrEmpty("url")
                    if (streamUrl.isEmpty()) continue

                    val isAvc1 = mime.contains("avc1")
                    val isVp9 = mime.contains("vp9") || mime.contains("vp09")
                    val isAv01 = mime.contains("av01")
                    val qLabel = f.strOrEmpty("qualityLabel")
                    val rawWidth = f.intOrDefault("width", 0)
                    val rawHeight = f.intOrDefault("height", 0)
                    val height = ExtractorUtils.normalizeResolutionTier(rawWidth, rawHeight, qLabel)
                        .let { if (it > 0) it else 720 }
                    if (height <= 0) continue

                    val compatibleAudio = if (isWebmVideo) bestWebmAudioUrl else bestMp4AudioUrl
                    // Якщо є аудіодоріжка, додаємо audioUrl; якщо немає — пропускаємо беззвучні при наявності muxed
                    if (compatibleAudio == null && muxedQualities.isNotEmpty()) continue

                    val isHdr = qLabel.contains("HDR", ignoreCase = true) ||
                        mime.contains("vp09.02") || mime.contains("vp9.2")
                    val codecTier = when {
                        height <= 1080 -> when {
                            isAvc1 -> 4
                            isVp9 -> 3
                            !isAv01 -> 2
                            else -> 1
                        }
                        else -> when {
                            isAvc1 -> 4
                            isVp9 -> 3
                            isAv01 -> 2
                            else -> 1
                        }
                    }
                    val fps = f.intOrDefault("fps", 30).coerceIn(0, 240)
                    val bitrateKbps = (f.intOrDefault("bitrate", 0) / 1000).coerceIn(0, 999_999)
                    val rank = ((if (!isHdr) 10L else 0L) + codecTier) * 100_000_000L +
                        fps * 1_000_000L + bitrateKbps

                    val label = qLabel.ifEmpty { "${height}p" }
                    adaptiveCandidates.add(
                        rank to StreamQuality(
                            label = label,
                            height = height,
                            url = streamUrl,
                            isHls = false,
                            audioUrl = compatibleAudio
                        )
                    )
                }
            }

            // Об'єднуємо потоки: для однакової роздільної здатності пріоритет має комбінований потік або найкращий сумісний кодек
            val byHeight = linkedMapOf<Int, StreamQuality>()
            for (q in muxedQualities.sortedByDescending { it.height }) {
                byHeight[q.height] = q
            }
            for ((_, q) in adaptiveCandidates.sortedWith(compareByDescending<Pair<Long, StreamQuality>> { it.first }.thenByDescending { it.second.height })) {
                val existing = byHeight[q.height]
                if (existing == null) {
                    byHeight[q.height] = q
                }
            }

            val finalQualities = byHeight.values.sortedByDescending { it.height }.toMutableList()
            if (hlsUrl.isNotEmpty()) {
                val hlsQuality = StreamQuality("Auto (HLS)", 1080, hlsUrl, true)
                if (finalQualities.none { it.isHls }) {
                    finalQualities.add(hlsQuality)
                }
            }

            if (finalQualities.isEmpty()) return null

            val availableLabels = finalQualities.map { it.label }.distinct().toMutableList()
            val finalTitle = if (author.isNotEmpty()) "$title ($author)" else title

            val episode = EpisodeItem(
                id = "yt_$videoId",
                number = "1",
                title = title,
                season = "1",
                iframeUrl = pageUrl
            )
            val player = PlayerSource("yt_direct", "YouTube").apply {
                episodes.add(episode)
            }
            val studio = DubStudio("youtube", "Оригінал").apply {
                players.add(player)
            }

            val mediaInfo = MediaInfo(
                url = pageUrl,
                siteType = "youtube",
                title = finalTitle,
                originalTitle = author.ifEmpty { null },
                posterUrl = thumbnail,
                description = description,
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = availableLabels
            )
            ParsedInnertubeResult(mediaInfo, finalQualities)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchOEmbedMetadata(client: OkHttpClient, videoId: String): Pair<String, String>? {
        return try {
            val watchUrl = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val req = Request.Builder()
                .url("https://www.youtube.com/oembed?url=$watchUrl&format=json")
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .build()
            val body = ExtractorUtils.executeForString(client, req) ?: return null
            val json = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val title = json.strOrEmpty("title")
            val author = json.strOrEmpty("author_name")
            if (title.isEmpty()) null else title to author
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFallbackMediaInfo(
        videoId: String,
        pageUrl: String,
        oEmbedTitle: String? = null,
        oEmbedAuthor: String? = null
    ): MediaInfo {
        val baseTitle = oEmbedTitle?.takeIf { it.isNotEmpty() } ?: "YouTube Відео"
        val finalTitle = if (!oEmbedAuthor.isNullOrEmpty()) "$baseTitle ($oEmbedAuthor)" else baseTitle
        val studio = DubStudio("youtube", "Оригінал")
        val player = PlayerSource("youtube", "YouTube")
        player.episodes.add(EpisodeItem("yt_fallback_$videoId", "1", baseTitle, "1", pageUrl))
        studio.players.add(player)

        return MediaInfo(
            url = pageUrl,
            siteType = "youtube",
            title = finalTitle,
            originalTitle = oEmbedAuthor?.takeIf { it.isNotEmpty() },
            posterUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
            isSeries = false,
            studios = mutableListOf(studio),
            availableQualities = mutableListOf("1080p", "720p", "480p", "360p")
        )
    }

    private fun JsonObject.strOrEmpty(key: String): String =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString.trim() else ""

    private fun JsonObject.intOrDefault(key: String, default: Int): Int =
        try {
            if (has(key) && get(key).isJsonPrimitive) get(key).asInt else default
        } catch (_: Exception) {
            default
        }

    private fun JsonObject.objOrNull(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else null

    private fun JsonObject.arrOrNull(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) getAsJsonArray(key) else null
}
