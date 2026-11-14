package ua.aniua.downloader.extractor

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

object GenericSeriesExtractor {

    private val EPISODE_URL_PATTERN = Pattern.compile(
        "(?:^|/|-|\\?|&)(?:episode|ep|seria|s\\d+e)[-_=/]?(\\d{1,4})(?:$|/|\\.|\\?|&|-)",
        Pattern.CASE_INSENSITIVE
    )
    private val SEASON_EP_PATH_PATTERN = Pattern.compile(
        "(?:/season[-_/]?(\\d{1,3})/episode[-_/]?(\\d{1,4})|/s(\\d{1,3})/e(\\d{1,4})|/tv/[^/]+/(\\d{1,3})/(\\d{1,4}))",
        Pattern.CASE_INSENSITIVE
    )
    private val S_E_COMPACT_PATTERN = Pattern.compile("s(\\d{1,3})e(\\d{1,4})", Pattern.CASE_INSENSITIVE)
    private val SEASON_X_EP_PATTERN = Pattern.compile(
        "(?:^|[^0-9a-zA-Z])(\\d{1,3})x(\\d{1,4})(?:$|[^0-9a-zA-Z])",
        Pattern.CASE_INSENSITIVE
    )
    private val SEASON_TAB_TEXT_PATTERN = Pattern.compile(
        "^(?:season|сезон|s)\\s*(\\d{1,3})$",
        Pattern.CASE_INSENSITIVE
    )
    private val RESOLUTION_NUMBERS = setOf(240, 320, 360, 480, 640, 720, 800, 1024, 1080, 1280, 1440, 1920, 3840)
    private val EPISODE_TEXT_PATTERN = Pattern.compile(
        "(?:Episode|Ep\\.?|EP|Серія|Серия)\\s*(\\d{1,4})",
        Pattern.CASE_INSENSITIVE
    )
    private val PAGE_EP_COUNT_PATTERN_1 = Pattern.compile(
        "(?:Episodes|Total\\s*Episodes|Епізодів|Серій|Серий)\\s*[:|-]\\s*(?:\\d{1,4}\\s*(?:/|of|з)\\s*)?(\\d{1,4})",
        Pattern.CASE_INSENSITIVE
    )
    private val PAGE_EP_COUNT_PATTERN_2 = Pattern.compile(
        "\\b(\\d{1,4})\\s+(?:Episodes|серій|серий|епізодів)\\b",
        Pattern.CASE_INSENSITIVE
    )
    private val IMDB_PATTERN = Pattern.compile("\\b(tt\\d{5,10})\\b")
    private val URL_TRAILING_ID_PATTERN = Pattern.compile("-(\\d+)(?:\\?.*)?$")

    private val JSON_ARRAY_PREFIX_PATTERNS = listOf(
        Pattern.compile("file\\s*:\\s*['\"]?(\\[\\{)"),
        Pattern.compile("playlist\\s*:\\s*(\\[\\{)"),
        Pattern.compile("(?:var|let|const)\\s+(?:episodes|playlist|files|sources)\\s*=\\s*(\\[\\{)")
    )
    private val TS_READER_PATTERN = Pattern.compile("ts_reader\\.run\\s*\\(\\s*(\\{[\\s\\S]*?\\})\\s*\\)")

    private val AD_OR_TRACKER_IFRAME_HINTS = listOf(
        "googletagmanager", "google-analytics", "doubleclick", "googlesyndication",
        "disqus", "facebook.com", "platform.twitter", "recaptcha", "hcaptcha",
        "challenges.cloudflare.com", "chatango", "addthis", "sharethis"
    )

    private val TEMPLATE_IFRAME_HOSTS = listOf(
        "vidsrc", "vidlink", "embed.su", "2embed", "multiembed", "autoembed"
    )

    data class EpisodeUrlTemplate(
        val template: String,
        val supportsSeason: Boolean,
        val defaultSeason: Int = 1
    ) {
        fun buildUrl(season: Int, episode: Int): String {
            return template
                .replace("{season}", season.toString())
                .replace("{episode}", episode.toString())
        }
    }

    internal data class StructuredJsonResult(
        val studios: List<DubStudio> = emptyList(),
        val totalEpisodes: Int? = null,
        val totalSeasons: Int? = null,
        val title: String? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val rating: String? = null,
        val isSeriesHint: Boolean = false
    )

    internal data class ParsedPageResult(
        val title: String,
        val rawTitle: String,
        val originalTitle: String?,
        val posterUrl: String?,
        val description: String?,
        val rating: String?,
        val siteBadge: String,
        val studios: MutableList<DubStudio>,
        val structuredInfo: StructuredJsonResult,
        val expectedEpisodeCount: Int?,
        val hasSeriesIndicators: Boolean,
        val discoveredIframeUrls: List<String>
    )

    fun parseGenericPage(
        client: OkHttpClient,
        cleanUrl: String,
        preloadedHtml: String? = null
    ): MediaInfo {
        if (ExtractorUtils.isDirectMediaUrl(cleanUrl)) {
            val qualities = GenericPlayerResolver.resolvePlayerStreams(client, cleanUrl, cleanUrl)
            val studio = DubStudio("direct_studio", "Оригінал")
            val player = PlayerSource("direct_player", "Плеєр")
            player.episodes.add(EpisodeItem("ep_direct", "1", "Онлайн Відео", "1", cleanUrl))
            studio.players.add(player)

            val labels = if (qualities.isNotEmpty()) {
                qualities.map { it.label }.distinct().toMutableList()
            } else {
                mutableListOf("1080p", "720p", "480p", "360p")
            }

            return MediaInfo(
                url = cleanUrl,
                siteType = "web",
                title = "Онлайн Відео",
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = labels
            )
        }

        val baseOrigin = ExtractorUtils.extractBaseOrigin(cleanUrl, "")
        var html = preloadedHtml?.trim().orEmpty()
        if (html.isEmpty() && (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://"))) {
            val req = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "uk,en-US;q=0.9,en;q=0.8")
                .apply {
                    if (baseOrigin.isNotEmpty()) header("Referer", "$baseOrigin/")
                }
                .build()
            html = ExtractorUtils.executeForString(client, req) ?: ""
        }

        val isBlockedOrEmpty = html.isEmpty() || isChallengeOrBlockedHtml(html)
        if (isBlockedOrEmpty && !isSeriesUrlCandidate(cleanUrl)) {
            val qualities = if (html.isEmpty()) GenericPlayerResolver.resolvePlayerStreams(client, cleanUrl, cleanUrl) else emptyList()
            val studio = DubStudio("direct_studio", "Оригінал")
            val player = PlayerSource("direct_player", "Плеєр")
            val fallbackTitle = extractTitleFromUrlSlug(cleanUrl).ifEmpty { "Онлайн Відео" }
            player.episodes.add(EpisodeItem("ep_direct", "1", fallbackTitle, "1", cleanUrl))
            studio.players.add(player)
            val labels = if (qualities.isNotEmpty()) {
                qualities.map { it.label }.distinct().toMutableList()
            } else {
                mutableListOf("1080p", "720p", "480p", "360p")
            }
            return MediaInfo(
                url = cleanUrl,
                siteType = extractSiteBadge(cleanUrl),
                title = fallbackTitle,
                isSeries = false,
                studios = mutableListOf(studio),
                availableQualities = labels
            )
        }

        val effectiveHtml = if (isChallengeOrBlockedHtml(html)) "" else html
        val doc = Jsoup.parse(effectiveHtml, cleanUrl)
        val parsed = parseDocumentInternal(client, cleanUrl, effectiveHtml, doc, baseOrigin, allowNetwork = true)

        var title = parsed.title
        var posterUrl = parsed.posterUrl
        var description = parsed.description
        var rating = parsed.rating
        val studios = parsed.studios

        // 3.5 Series Overview -> First Episode Hop
        val allExistingEps = studios.flatMap { it.players }.flatMap { it.episodes }
        if (allExistingEps.size == 1 && !hasEpisodeInUrl(cleanUrl)) {
            val firstEpUrl = allExistingEps[0].iframeUrl
            if (firstEpUrl != cleanUrl && !ExtractorUtils.isDirectMediaUrl(firstEpUrl) &&
                firstEpUrl.startsWith("http") && isSameSiteHost(cleanUrl, firstEpUrl)
            ) {
                val hopHtml = fetchHtmlWithReferer(client, firstEpUrl, cleanUrl)
                if (!hopHtml.isNullOrEmpty() && !isChallengeOrBlockedHtml(hopHtml)) {
                    val hopDoc = Jsoup.parse(hopHtml, firstEpUrl)
                    val hopStudios = extractEpisodesFromDocument(client, firstEpUrl, hopHtml, hopDoc, baseOrigin)
                    val hopEpsCount = hopStudios.flatMap { it.players }.sumOf { it.episodes.size }
                    if (hopEpsCount > 1) {
                        studios.clear()
                        studios.addAll(hopStudios)
                    }
                }
            }
        }

        // 3.6 Automatic Episode Count Detection & Template Expansion
        val expansionResult = expandEpisodesByCountAndMetadata(
            client = client,
            cleanUrl = cleanUrl,
            html = effectiveHtml,
            seriesTitle = title,
            studios = studios,
            expectedEpisodeCount = parsed.expectedEpisodeCount,
            hasSeriesIndicators = parsed.hasSeriesIndicators,
            discoveredIframeUrls = parsed.discoveredIframeUrls,
            allowExternalApi = true
        )

        if (posterUrl.isNullOrEmpty() && !expansionResult.fallbackPoster.isNullOrEmpty()) {
            posterUrl = expansionResult.fallbackPoster
        }
        if (description.isNullOrEmpty() && !expansionResult.fallbackDescription.isNullOrEmpty()) {
            description = expansionResult.fallbackDescription
        }
        if (rating.isNullOrEmpty() && !expansionResult.fallbackRating.isNullOrEmpty()) {
            rating = expansionResult.fallbackRating
        }

        // 4. Fallback if studios still empty
        if (studios.isEmpty()) {
            val fallbackPlayers = mutableListOf<PlayerSource>()
            for ((idx, ifrUrl) in parsed.discoveredIframeUrls.withIndex()) {
                val (_, pName) = ExtractorUtils.identifyPlayer(ifrUrl, idx + 1)
                val ps = PlayerSource("player_$idx", pName)
                ps.episodes.add(EpisodeItem("ep_1", "1", title, "1", ifrUrl))
                fallbackPlayers.add(ps)
            }
            if (fallbackPlayers.isEmpty() && !parsed.hasSeriesIndicators) {
                val movieBackupUrls = buildBackupMovieEmbedUrls(extractTmdbIdFromUrl(cleanUrl), findImdbId(cleanUrl, effectiveHtml))
                for ((idx, mUrl) in movieBackupUrls.withIndex()) {
                    val (_, pName) = ExtractorUtils.identifyPlayer(mUrl, idx + 1)
                    val ps = PlayerSource("movie_backup_$idx", pName)
                    ps.episodes.add(EpisodeItem("ep_1", "1", title, "1", mUrl))
                    fallbackPlayers.add(ps)
                }
            }
            if (fallbackPlayers.isEmpty()) {
                val ps = PlayerSource("direct_player", "Плеєр")
                ps.episodes.add(EpisodeItem("ep_direct", "1", title, "1", cleanUrl))
                fallbackPlayers.add(ps)
            }
            studios.add(DubStudio("direct_studio", "Оригінал", fallbackPlayers))
        }

        val maxEpsInAnyPlayer = studios.flatMap { it.players }.maxOfOrNull { it.episodes.size } ?: 0
        val isSeries = maxEpsInAnyPlayer > 1 || parsed.hasSeriesIndicators

        val firstEpIframe = studios.firstOrNull()?.players?.firstOrNull()?.episodes?.firstOrNull()?.iframeUrl ?: cleanUrl
        val qualities = if (maxEpsInAnyPlayer <= 1) {
            GenericPlayerResolver.resolvePlayerStreams(client, firstEpIframe, cleanUrl)
        } else {
            emptyList()
        }

        val labels = if (qualities.isNotEmpty()) {
            qualities.map { it.label }.distinct().toMutableList()
        } else {
            mutableListOf("1080p", "720p", "480p", "360p")
        }

        return MediaInfo(
            url = cleanUrl,
            siteType = parsed.siteBadge,
            title = title,
            originalTitle = parsed.originalTitle,
            posterUrl = posterUrl,
            description = description,
            isSeries = isSeries,
            studios = studios,
            availableQualities = labels,
            rating = rating
        )
    }

    fun extractEpisodesFromDocument(
        client: OkHttpClient,
        pageUrl: String,
        html: String,
        doc: Document,
        baseOrigin: String
    ): List<DubStudio> {
        val parsed = parseDocumentInternal(client, pageUrl, html, doc, baseOrigin, allowNetwork = true)
        expandEpisodesByCountAndMetadata(
            client = client,
            cleanUrl = pageUrl,
            html = html,
            seriesTitle = parsed.title,
            studios = parsed.studios,
            expectedEpisodeCount = parsed.expectedEpisodeCount,
            hasSeriesIndicators = parsed.hasSeriesIndicators,
            discoveredIframeUrls = parsed.discoveredIframeUrls,
            allowExternalApi = false
        )
        return parsed.studios
    }

    internal fun parseDocumentOffline(pageUrl: String, html: String): MediaInfo {
        val baseOrigin = ExtractorUtils.extractBaseOrigin(pageUrl, "")
        val effectiveHtml = if (isChallengeOrBlockedHtml(html)) "" else html
        val doc = Jsoup.parse(effectiveHtml, pageUrl)
        val parsed = parseDocumentInternal(null, pageUrl, effectiveHtml, doc, baseOrigin, allowNetwork = false)

        expandEpisodesByCountAndMetadata(
            client = null,
            cleanUrl = pageUrl,
            html = effectiveHtml,
            seriesTitle = parsed.title,
            studios = parsed.studios,
            expectedEpisodeCount = parsed.expectedEpisodeCount,
            hasSeriesIndicators = parsed.hasSeriesIndicators,
            discoveredIframeUrls = parsed.discoveredIframeUrls,
            allowExternalApi = false
        )

        val studios = parsed.studios
        if (studios.isEmpty()) {
            val ps = PlayerSource("direct_player", "Плеєр")
            ps.episodes.add(EpisodeItem("ep_direct", "1", parsed.title, "1", pageUrl))
            studios.add(DubStudio("direct_studio", "Оригінал", mutableListOf(ps)))
        }

        val maxEps = studios.flatMap { it.players }.maxOfOrNull { it.episodes.size } ?: 0
        return MediaInfo(
            url = pageUrl,
            siteType = parsed.siteBadge,
            title = parsed.title,
            originalTitle = parsed.originalTitle,
            posterUrl = parsed.posterUrl,
            description = parsed.description,
            isSeries = maxEps > 1 || parsed.hasSeriesIndicators,
            studios = studios,
            availableQualities = mutableListOf("1080p", "720p", "480p", "360p"),
            rating = parsed.rating
        )
    }

    private fun parseDocumentInternal(
        client: OkHttpClient?,
        cleanUrl: String,
        html: String,
        doc: Document,
        baseOrigin: String,
        allowNetwork: Boolean
    ): ParsedPageResult {
        val rawTitle = extractRawTitle(doc, cleanUrl)
        val structured = parseStructuredJsonEpisodes(doc, cleanUrl, baseOrigin)

        var title = cleanSeriesTitle(rawTitle.ifEmpty { structured.title ?: extractTitleFromUrlSlug(cleanUrl) }.ifEmpty { "Онлайн Відео" })
        if (title.isEmpty()) title = extractTitleFromUrlSlug(cleanUrl).ifEmpty { "Онлайн Відео" }

        val origEl = doc.selectFirst(".orig-title, span.origintitle, [itemprop='alternateName'], .film-name-orig, .alias")
        val originalTitle = origEl?.text()?.trim()?.takeIf { it.isNotEmpty() }

        var posterUrl = extractPosterUrl(doc, baseOrigin) ?: structured.posterUrl
        var description = extractDescription(doc) ?: structured.description
        var rating = extractRating(doc) ?: structured.rating

        val discoveredIframes = collectIframeUrls(doc, baseOrigin)
        val studios = mutableListOf<DubStudio>()

        // 3.1 DLE & Ralode & Known Serial Iframes
        if (allowNetwork && client != null) {
            val newsId = DlePlaylistParser.extractNewsId(cleanUrl, html, doc)
            if (newsId != null) {
                val xfname = DlePlaylistParser.extractXfname(doc)
                val userHash = DlePlaylistParser.extractLoginHash(html)
                try {
                    studios.addAll(DlePlaylistParser.fetchDlePlaylists(client, baseOrigin, newsId, xfname, userHash, cleanUrl))
                } catch (_: Exception) {}
            }
        }
        if (studios.isEmpty()) {
            val dleInline = DlePlaylistParser.parseDleHtml(html, baseOrigin)
            if (dleInline.isNotEmpty()) studios.addAll(dleInline)
        }
        if (studios.isEmpty()) {
            val ralode = RalodePlayerParser.parseRalodeScript(html, baseOrigin)
            if (ralode.isNotEmpty()) studios.addAll(ralode)
        }
        if (studios.isEmpty() && allowNetwork && client != null) {
            for (ifrUrl in discoveredIframes) {
                val lowerIfr = ifrUrl.lowercase(Locale.ROOT)
                if (lowerIfr.contains("ashdi") && lowerIfr.contains("/serial/")) {
                    val ashdiStudios = AshdiExtractor.parseAshdiSerial(client, ifrUrl)
                    if (ashdiStudios.isNotEmpty()) {
                        studios.addAll(ashdiStudios)
                        break
                    }
                } else if (FenixExtractor.isFenixUrl(ifrUrl)) {
                    val fenixMedia = FenixExtractor.parseFenixPage(client, ifrUrl)
                    if (fenixMedia != null && fenixMedia.studios.isNotEmpty()) {
                        studios.addAll(fenixMedia.studios)
                        break
                    }
                }
            }
        }

        // 3.2 Embedded JSON Playlists in HTML/Scripts
        if (studios.isEmpty()) {
            val jsonStudios = parseJsonPlaylistsFromHtml(html, baseOrigin)
            if (jsonStudios.isNotEmpty()) studios.addAll(jsonStudios)
        }

        // 3.3 English/American Streaming CMS AJAX Endpoints
        if (studios.isEmpty() && allowNetwork && client != null) {
            val ajaxStudios = tryFetchCmsAjaxEpisodes(client, cleanUrl, doc, baseOrigin)
            if (ajaxStudios.isNotEmpty()) studios.addAll(ajaxStudios)
        }

        // 3.4 Structured JSON Episodes (LD+JSON, __NEXT_DATA__, application/json)
        if (studios.isEmpty() && structured.studios.isNotEmpty()) {
            studios.addAll(structured.studios)
        }

        // 3.5 HTML DOM Episode Lists, Dropdowns, Data-Attributes & Links
        if (studios.isEmpty()) {
            val domStudios = parseDomEpisodes(doc, cleanUrl, baseOrigin)
            if (domStudios.isNotEmpty()) studios.addAll(domStudios)
        }

        // 3.5b Multi-season tabs/dropdowns (e.g. "Select Seasons" / "Season 1..13")
        if (allowNetwork && client != null) {
            fetchAdditionalSeasonsIfAvailable(client, doc, html, cleanUrl, baseOrigin, studios)
        }

        splitMultiSeasonStudioIfNeeded(studios)

        val expectedCount = extractExpectedEpisodeCount(doc, html, structured.totalEpisodes)
        val hasSeriesIndicators = detectSeriesIndicators(cleanUrl, doc, rawTitle, structured.isSeriesHint, discoveredIframes, expectedCount)

        return ParsedPageResult(
            title = title,
            rawTitle = rawTitle,
            originalTitle = originalTitle,
            posterUrl = posterUrl,
            description = description,
            rating = rating,
            siteBadge = extractSiteBadge(cleanUrl),
            studios = studios,
            structuredInfo = structured,
            expectedEpisodeCount = expectedCount,
            hasSeriesIndicators = hasSeriesIndicators,
            discoveredIframeUrls = discoveredIframes
        )
    }

    private fun extractRawTitle(doc: Document, cleanUrl: String = ""): String {
        val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
        val twTitle = doc.selectFirst("meta[name='twitter:title']")?.attr("content")?.trim()
        val h1 = doc.selectFirst("h1")?.text()?.trim()
        val tagTitle = doc.title().trim()
        val candidate = listOfNotNull(ogTitle, twTitle, h1, tagTitle).firstOrNull {
            it.isNotEmpty() && !isBlockedTitle(it)
        } ?: ""
        return candidate.ifEmpty { extractTitleFromUrlSlug(cleanUrl) }
    }

    private fun isBlockedTitle(title: String): Boolean {
        val lower = title.lowercase(Locale.ROOT)
        return lower.contains("just a moment") ||
            lower.contains("attention required") ||
            lower.contains("403 forbidden") ||
            lower.contains("access denied") ||
            lower.contains("cloudflare")
    }

    internal fun isChallengeOrBlockedHtml(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase(Locale.ROOT)
        return lower.contains("cf-browser-verification") ||
            lower.contains("challenges.cloudflare.com") ||
            lower.contains("<title>just a moment") ||
            lower.contains("attention required! | cloudflare") ||
            lower.contains("enable javascript and cookies to continue")
    }

    internal fun extractTmdbIdFromUrl(url: String): String? {
        val m = Pattern.compile("/(?:serie|series|tv|movie)/(\\d{1,8})(?:/|-|$)", Pattern.CASE_INSENSITIVE).matcher(url)
        return if (m.find()) m.group(1) else null
    }

    internal fun extractTitleFromUrlSlug(url: String): String {
        return try {
            val path = URI(ExtractorUtils.ensureHttpsScheme(url)).path ?: return ""
            val ignoredSegments = setOf("serie", "series", "tv", "movie", "movies", "watch", "episode", "episodes", "ep", "anime", "serial", "season")
            val segments = path.split('/').filter { it.isNotBlank() }
            val slug = segments.lastOrNull { seg ->
                val lower = seg.lowercase(Locale.ROOT)
                lower !in ignoredSegments && !seg.all { it.isDigit() } && !lower.matches(Regex("^season[-_]?\\d+$"))
            } ?: return ""

            var cleaned = slug
                .replace(Regex("(?i)\\.html?$"), "")
                .replace(Regex("(?i)(?:-episode|-ep|-season|-seria)[-_]?\\d+.*$"), "")
                .replace(Regex("(?i)-\\d{1,3}x\\d{1,4}.*$"), "")
                .replace(Regex("(?i)-s\\d{1,3}e\\d{1,4}.*$"), "")
                .replace(Regex("^\\d+[-_]"), "")
                .replace('-', ' ')
                .replace('_', ' ')
                .trim()
            if (cleaned.isEmpty()) return ""
            cleaned = cleaned.split(Regex("\\s+")).joinToString(" ") { word ->
                word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString() }
            }
            cleaned
        } catch (_: Exception) {
            ""
        }
    }

    private fun isSeriesUrlCandidate(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("/serie/") ||
            lower.contains("/series/") ||
            lower.contains("/serial") ||
            lower.contains("/tv/") ||
            lower.contains("/anime/") ||
            hasEpisodeInUrl(url)
    }

    internal fun cleanSeriesTitle(rawTitle: String): String {
        var t = rawTitle.trim()
        if (t.isEmpty()) return ""
        t = t.replace(Regex("(?i)^watch\\s+"), "")
        t = t.replace(Regex("(?i)\\s+(?:online\\s+free|free\\s+online|for\\s+free|hd\\s+free|english\\s+subbed|sub/dub|subbed|dubbed).*$"), "")
        t = t.replace(Regex("(?i)\\s+free\\s+download.*$"), "")
        t = t.replace(Regex("(?i)\\s*(?:-|–|:|\\|)?\\s*(?:season\\s*\\d+\\s*)?(?:episode|ep\\.?|серія|серия)\\s*\\d+.*$"), "")
        t = t.replace(Regex("(?i)\\s+s\\d+e\\d+.*$"), "")
        t = t.replace(Regex("\\s+[|\\-–]\\s+[^|\\-–]{2,30}$"), "")
        return t.trim().ifEmpty { rawTitle.trim() }
    }

    private fun extractPosterUrl(doc: Document, baseOrigin: String): String? {
        val el = doc.selectFirst(
            "meta[property='og:image'], meta[name='twitter:image'], [itemprop='image'], .film-poster img, .poster img, .fposter img"
        ) ?: return null
        val raw = when {
            el.tagName() == "meta" -> el.attr("content")
            el.hasAttr("content") && el.attr("content").isNotEmpty() -> el.attr("content")
            el.hasAttr("data-src") && el.attr("data-src").isNotEmpty() -> el.attr("data-src")
            el.hasAttr("data-lazy-src") && el.attr("data-lazy-src").isNotEmpty() -> el.attr("data-lazy-src")
            else -> el.attr("src")
        }.trim()
        if (raw.isEmpty()) return null
        return DlePlaylistParser.normalizeUrl(raw, baseOrigin)
    }

    private fun extractDescription(doc: Document): String? {
        val el = doc.selectFirst(
            "meta[property='og:description'], meta[name='description'], [itemprop='description'], .description, .synopsis, .overview, .film-description"
        ) ?: return null
        val text = if (el.tagName() == "meta") el.attr("content") else el.text()
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private fun extractRating(doc: Document): String? {
        val rateEl = doc.selectFirst("[itemprop='ratingValue'], .rate-imdb span, .imdb, .rating-score")
        val raw = if (rateEl?.tagName() == "meta") rateEl.attr("content") else rateEl?.text()?.trim()
        if (!raw.isNullOrEmpty()) {
            val m = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)").matcher(raw)
            if (m.find()) {
                return "★ ${m.group(1)?.replace(',', '.')}"
            }
        }
        return null
    }

    private fun extractSiteBadge(url: String): String {
        return try {
            val host = URI(ExtractorUtils.ensureHttpsScheme(url)).host?.lowercase(Locale.ROOT) ?: return "web"
            val noWww = host.removePrefix("www.")
            val parts = noWww.split(".")
            if (parts.size >= 2) parts[parts.size - 2].takeIf { it.isNotEmpty() } ?: "web" else noWww
        } catch (_: Exception) {
            "web"
        }
    }

    private fun collectIframeUrls(doc: Document, baseOrigin: String): List<String> {
        val urls = linkedSetOf<String>()
        val iframes = doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
        for (ifr in iframes) {
            val raw = GenericPlayerResolver.extractIframeSrc(ifr) ?: continue
            val lower = raw.lowercase(Locale.ROOT)
            if (AD_OR_TRACKER_IFRAME_HINTS.any { lower.contains(it) }) continue
            urls.add(DlePlaylistParser.normalizeUrl(raw, baseOrigin))
        }
        return urls.toList()
    }

    internal fun parseJsonPlaylistsFromHtml(html: String, baseOrigin: String): List<DubStudio> {
        for (pattern in JSON_ARRAY_PREFIX_PATTERNS) {
            val m = pattern.matcher(html)
            while (m.find()) {
                val startIdx = m.start(1)
                val jsonCandidate = extractBalancedBracketBlock(html, startIdx) ?: continue
                try {
                    val elem = JsonParser.parseString(jsonCandidate)
                    if (elem.isJsonArray) {
                        val studios = parsePlayerJsArray(elem.asJsonArray, baseOrigin)
                        if (studios.isNotEmpty()) return studios
                    }
                } catch (_: Exception) {}
            }
        }

        val tsM = TS_READER_PATTERN.matcher(html)
        if (tsM.find()) {
            val jsonCandidate = tsM.group(1)?.trim() ?: ""
            try {
                val root = JsonParser.parseString(jsonCandidate).asJsonObject
                if (root.has("sources") && root.get("sources").isJsonArray) {
                    val sources = root.getAsJsonArray("sources")
                    val players = mutableListOf<PlayerSource>()
                    for (i in 0 until sources.size()) {
                        val sObj = sources[i].asJsonObjectOrNull() ?: continue
                        val sName = sObj.getStringOrNull("source") ?: "Плеєр ${i + 1}"
                        val ps = PlayerSource("ts_$i", sName)
                        val filesArr = sObj.getAsJsonArrayOrNull("files")
                        if (filesArr != null) {
                            for (j in 0 until filesArr.size()) {
                                val fObj = filesArr[j].asJsonObjectOrNull() ?: continue
                                val fUrl = fObj.getStringOrNull("file") ?: fObj.getStringOrNull("url") ?: continue
                                val fTitle = fObj.getStringOrNull("title") ?: "Серія ${j + 1}"
                                ps.episodes.add(
                                    EpisodeItem("ts_${i}_$j", extractEpNumber(fTitle, j + 1), fTitle, "1", DlePlaylistParser.normalizeUrl(fUrl, baseOrigin))
                                )
                            }
                        } else {
                            val content = sObj.getStringOrNull("content")
                            if (!content.isNullOrEmpty()) {
                                ps.episodes.add(EpisodeItem("ts_${i}_0", "1", sName, "1", DlePlaylistParser.normalizeUrl(content, baseOrigin)))
                            }
                        }
                        if (ps.episodes.isNotEmpty()) players.add(ps)
                    }
                    if (players.isNotEmpty()) {
                        return listOf(DubStudio("ts_studio", "Озвучення", players))
                    }
                }
            } catch (_: Exception) {}
        }

        return emptyList()
    }

    private fun extractBalancedBracketBlock(text: String, startIdx: Int): String? {
        if (startIdx < 0 || startIdx >= text.length || text[startIdx] != '[') return null
        var depth = 0
        var inStr = false
        var strChar = '\u0000'
        var escape = false

        for (i in startIdx until text.length) {
            val ch = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\') {
                escape = true
                continue
            }
            if (inStr) {
                if (ch == strChar) inStr = false
                continue
            }
            if (ch == '"' || ch == '\'') {
                inStr = true
                strChar = ch
                continue
            }
            if (ch == '[') depth++
            else if (ch == ']') {
                depth--
                if (depth == 0) return text.substring(startIdx, i + 1)
            }
        }
        return null
    }

    private fun parsePlayerJsArray(arr: JsonArray, baseOrigin: String): List<DubStudio> {
        if (arr.size() == 0) return emptyList()
        val studios = mutableListOf<DubStudio>()

        val firstObj = arr[0].asJsonObjectOrNull() ?: return emptyList()
        // Check 3-level or 2-level nested folder structure
        if (firstObj.has("folder") || firstObj.has("episodes")) {
            for (sIdx in 0 until arr.size()) {
                val itemObj = arr[sIdx].asJsonObjectOrNull() ?: continue
                val groupTitle = itemObj.getStringOrNull("title")
                    ?: itemObj.getStringOrNull("name")
                    ?: "Озвучка ${sIdx + 1}"
                val childArr = itemObj.getAsJsonArrayOrNull("folder")
                    ?: itemObj.getAsJsonArrayOrNull("episodes")
                    ?: continue

                val episodes = mutableListOf<EpisodeItem>()
                collectNestedEpisodes(childArr, baseOrigin, "1", episodes)
                if (episodes.isNotEmpty()) {
                    val ps = PlayerSource("json_player_$sIdx", "Плеєр", episodes)
                    studios.add(DubStudio("json_studio_$sIdx", groupTitle, mutableListOf(ps)))
                }
            }
            if (studios.isNotEmpty()) return studios
        }

        // Check 1-level episode array
        val flatEpisodes = mutableListOf<EpisodeItem>()
        for (i in 0 until arr.size()) {
            val epObj = arr[i].asJsonObjectOrNull() ?: continue
            val epUrl = extractUrlFromJsonObj(epObj) ?: continue
            val epTitle = epObj.getStringOrNull("title")
                ?: epObj.getStringOrNull("name")
                ?: epObj.getStringOrNull("ep")
                ?: "Серія ${i + 1}"
            val epNum = epObj.getStringOrNull("number")
                ?: epObj.getStringOrNull("episode")
                ?: extractEpNumber(epTitle, i + 1)
            val season = epObj.getStringOrNull("season") ?: "1"
            flatEpisodes.add(
                EpisodeItem(
                    id = "json_ep_$i",
                    number = epNum,
                    title = epTitle,
                    season = season,
                    iframeUrl = DlePlaylistParser.normalizeUrl(epUrl, baseOrigin)
                )
            )
        }

        if (flatEpisodes.isNotEmpty()) {
            val ps = PlayerSource("json_player", "Плеєр", flatEpisodes)
            studios.add(DubStudio("json_studio", "Оригінал", mutableListOf(ps)))
        }
        return studios
    }

    private fun collectNestedEpisodes(
        arr: JsonArray,
        baseOrigin: String,
        currentSeason: String,
        out: MutableList<EpisodeItem>
    ) {
        for (i in 0 until arr.size()) {
            val obj = arr[i].asJsonObjectOrNull() ?: continue
            val title = obj.getStringOrNull("title") ?: obj.getStringOrNull("name") ?: ""
            val subArr = obj.getAsJsonArrayOrNull("folder") ?: obj.getAsJsonArrayOrNull("episodes")
            if (subArr != null) {
                val seasonNum = extractSeasonNumber(title) ?: currentSeason
                collectNestedEpisodes(subArr, baseOrigin, seasonNum, out)
            } else {
                val url = extractUrlFromJsonObj(obj) ?: continue
                val epLabel = title.ifEmpty { "Серія ${out.size + 1}" }
                val epNum = obj.getStringOrNull("number") ?: extractEpNumber(epLabel, out.size + 1)
                val season = obj.getStringOrNull("season") ?: currentSeason
                out.add(
                    EpisodeItem(
                        id = "json_ep_${out.size}",
                        number = epNum,
                        title = epLabel,
                        season = season,
                        iframeUrl = DlePlaylistParser.normalizeUrl(url, baseOrigin)
                    )
                )
            }
        }
    }

    private fun extractUrlFromJsonObj(obj: JsonObject): String? {
        val direct = obj.getStringOrNull("file")
            ?: obj.getStringOrNull("url")
            ?: obj.getStringOrNull("src")
            ?: obj.getStringOrNull("iframe")
            ?: obj.getStringOrNull("embedUrl")
            ?: obj.getStringOrNull("link")
        if (!direct.isNullOrEmpty() && (direct.startsWith("http") || direct.startsWith("/") || direct.startsWith("["))) {
            return direct
        }
        val sources = obj.getAsJsonArrayOrNull("sources")
        if (sources != null && sources.size() > 0) {
            val first = sources[0].asJsonObjectOrNull()
            return first?.getStringOrNull("file") ?: first?.getStringOrNull("url") ?: first?.getStringOrNull("src")
        }
        return null
    }

    private fun tryFetchCmsAjaxEpisodes(
        client: OkHttpClient,
        cleanUrl: String,
        doc: Document,
        baseOrigin: String
    ): List<DubStudio> {
        if (baseOrigin.isEmpty()) return emptyList()

        val idCandidates = linkedSetOf<String>()
        val selectors = listOf(
            ".detail_page-watch[data-id]",
            "#watch-block[data-id]",
            "#wrapper[data-id]",
            ".film-poster[data-id]",
            "input#movie_id[value]"
        )
        for (sel in selectors) {
            val el = doc.selectFirst(sel) ?: continue
            val valStr = el.attr("data-id").ifEmpty { el.attr("value") }.trim()
            if (valStr.isNotEmpty()) idCandidates.add(valStr)
        }
        val urlIdM = URL_TRAILING_ID_PATTERN.matcher(cleanUrl)
        if (urlIdM.find()) {
            urlIdM.group(1)?.let { idCandidates.add(it) }
        }

        // 1. HiAnime / AniWatch / Zoro / Kaido AJAX
        for (mediaId in idCandidates) {
            for (endpoint in listOf(
                "$baseOrigin/ajax/v2/episode/list/$mediaId",
                "$baseOrigin/ajax/episode/list/$mediaId"
            )) {
                val eps = fetchAjaxEpisodeFragment(client, endpoint, cleanUrl, baseOrigin)
                if (eps.isNotEmpty()) {
                    val ps = PlayerSource("ajax_player", "Плеєр", eps.toMutableList())
                    return listOf(DubStudio("ajax_studio", "Оригінал", mutableListOf(ps)))
                }
            }
        }

        // 2. FlixHQ / SFlix / MoviesJoy / HiMovies / Goku / Hurawatch AJAX
        for (mediaId in idCandidates) {
            for (seasonEndpoint in listOf(
                "$baseOrigin/ajax/v2/tv/seasons/$mediaId",
                "$baseOrigin/ajax/season/list/$mediaId"
            )) {
                val seasonHtml = fetchAjaxHtml(client, seasonEndpoint, cleanUrl) ?: continue
                val sDoc = Jsoup.parse(seasonHtml, baseOrigin)
                val seasonEls = sDoc.select(".ss-item[data-id], a[data-id]")
                if (seasonEls.isEmpty()) continue

                val allEps = mutableListOf<EpisodeItem>()
                for ((sIdx, sEl) in seasonEls.withIndex()) {
                    val seasonId = sEl.attr("data-id").trim()
                    if (seasonId.isEmpty()) continue
                    val seasonNum = extractSeasonNumber(sEl.text()) ?: "${sIdx + 1}"
                    val epHtml = fetchAjaxHtml(client, "$baseOrigin/ajax/v2/season/episodes/$seasonId", cleanUrl)
                        ?: fetchAjaxHtml(client, "$baseOrigin/ajax/season/episodes/$seasonId", cleanUrl)
                        ?: continue
                    val epDoc = Jsoup.parse(epHtml, baseOrigin)
                    val epEls = epDoc.select(".eps-item[data-id], .nav-item a[data-id], a[data-id]")
                    for ((eIdx, eEl) in epEls.withIndex()) {
                        val dataId = eEl.attr("data-id").trim()
                        val href = eEl.attr("href").trim()
                        val epTitle = eEl.attr("title").ifEmpty { eEl.text() }.trim().ifEmpty { "Серія ${eIdx + 1}" }
                        val epNum = extractEpNumber(eEl.attr("data-number").ifEmpty { epTitle }, eIdx + 1)
                        val targetUrl = if (href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript")) {
                            DlePlaylistParser.normalizeUrl(href, baseOrigin)
                        } else {
                            "$cleanUrl?ep=$dataId"
                        }
                        allEps.add(
                            EpisodeItem(
                                id = "cms_s${seasonNum}_e${epNum}_$eIdx",
                                number = epNum,
                                title = epTitle,
                                season = seasonNum,
                                iframeUrl = targetUrl
                            )
                        )
                    }
                }
                if (allEps.isNotEmpty()) {
                    val ps = PlayerSource("cms_player", "Плеєр", allEps)
                    return listOf(DubStudio("cms_studio", "Оригінал", mutableListOf(ps)))
                }
            }
        }

        // 3. Gogoanime / Anitaku AJAX & Range
        val epPageLinks = doc.select("#episode_page a[ep_end]")
        if (epPageLinks.isNotEmpty()) {
            val maxEp = epPageLinks.mapNotNull { it.attr("ep_end").trim().toIntOrNull() }.maxOrNull() ?: 0
            if (maxEp > 1) {
                val movieId = doc.selectFirst("input#movie_id[value]")?.attr("value")?.trim() ?: ""
                val alias = doc.selectFirst("input#alias_anime[value]")?.attr("value")?.trim() ?: ""
                if (movieId.isNotEmpty()) {
                    val gogoUrl = "$baseOrigin/load-list-episode?ep_start=0&ep_end=$maxEp&id=$movieId&default_ep=0&alias=$alias"
                    val gogoEps = fetchAjaxEpisodeFragment(client, gogoUrl, cleanUrl, baseOrigin)
                    if (gogoEps.size > 1) {
                        val ps = PlayerSource("gogo_player", "Плеєр", gogoEps.toMutableList())
                        return listOf(DubStudio("gogo_studio", "Оригінал", mutableListOf(ps)))
                    }
                }
                val slug = alias.ifEmpty {
                    cleanUrl.substringAfterLast("/").substringBefore("?").removePrefix("category/")
                }
                if (slug.isNotEmpty()) {
                    val generated = (1..maxEp).map { num ->
                        EpisodeItem(
                            id = "gogo_ep_$num",
                            number = num.toString(),
                            title = "Episode $num",
                            season = "1",
                            iframeUrl = "$baseOrigin/$slug-episode-$num"
                        )
                    }.toMutableList()
                    val ps = PlayerSource("gogo_player", "Плеєр", generated)
                    return listOf(DubStudio("gogo_studio", "Оригінал", mutableListOf(ps)))
                }
            }
        }

        return emptyList()
    }

    private fun fetchAjaxHtml(client: OkHttpClient, url: String, referer: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ExtractorUtils.DEFAULT_UA)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val raw = ExtractorUtils.executeForString(client, req)?.trim() ?: return null
        if (raw.startsWith("{")) {
            return try {
                val obj = JsonParser.parseString(raw).asJsonObject
                obj.getStringOrNull("html") ?: obj.getStringOrNull("result") ?: obj.getStringOrNull("response")
            } catch (_: Exception) {
                null
            }
        }
        return raw.takeIf { it.contains("<") }
    }

    private fun fetchAjaxEpisodeFragment(
        client: OkHttpClient,
        endpoint: String,
        cleanUrl: String,
        baseOrigin: String
    ): List<EpisodeItem> {
        val html = fetchAjaxHtml(client, endpoint, cleanUrl) ?: return emptyList()
        val doc = Jsoup.parse(html, baseOrigin)
        val items = doc.select(".ep-item, .ssl-item, a[data-number], a[data-id][href], #episode_related a")
        val episodes = mutableListOf<EpisodeItem>()
        for ((idx, el) in items.withIndex()) {
            val href = el.attr("href").trim()
            val dataId = el.attr("data-id").trim()
            val numStr = el.attr("data-number").trim().ifEmpty {
                extractEpNumber(el.text(), idx + 1)
            }
            val title = el.attr("title").trim().ifEmpty { el.text().trim() }.ifEmpty { "Episode $numStr" }
            val epUrl = when {
                href.isNotEmpty() && !href.startsWith("#") && !href.startsWith("javascript") ->
                    DlePlaylistParser.normalizeUrl(href, baseOrigin)
                dataId.isNotEmpty() -> "$cleanUrl?ep=$dataId"
                else -> continue
            }
            episodes.add(
                EpisodeItem(
                    id = "ajax_ep_${idx}_$numStr",
                    number = numStr,
                    title = title,
                    season = "1",
                    iframeUrl = epUrl
                )
            )
        }
        return episodes
            .distinctBy { "${it.season}:${it.number}" }
            .sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
    }

    internal fun parseStructuredJsonEpisodes(
        doc: Document,
        pageUrl: String,
        baseOrigin: String
    ): StructuredJsonResult {
        var totalEpisodes: Int? = null
        var totalSeasons: Int? = null
        var title: String? = null
        var posterUrl: String? = null
        var description: String? = null
        var rating: String? = null
        var isSeriesHint = false
        val collectedEpisodes = mutableListOf<EpisodeItem>()

        // 1. application/ld+json
        val ldScripts = doc.select("script[type='application/ld+json']")
        for (script in ldScripts) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            try {
                val root = JsonParser.parseString(raw)
                val nodes = mutableListOf<JsonObject>()
                collectJsonLdNodes(root, nodes)
                for (obj in nodes) {
                    val type = obj.getStringOrNull("@type") ?: ""
                    if (type.contains("TVSeries", ignoreCase = true) ||
                        type.contains("TVSeason", ignoreCase = true) ||
                        type.contains("TVEpisode", ignoreCase = true)
                    ) {
                        isSeriesHint = true
                    }
                    if (title == null) title = obj.getStringOrNull("name")
                    if (description == null) description = obj.getStringOrNull("description")
                    if (posterUrl == null) {
                        val img = obj.get("image")
                        if (img != null && img.isJsonPrimitive) {
                            posterUrl = DlePlaylistParser.normalizeUrl(img.asString, baseOrigin)
                        }
                    }
                    if (rating == null && obj.has("aggregateRating") && obj.get("aggregateRating").isJsonObject) {
                        val rv = obj.getAsJsonObject("aggregateRating").getStringOrNull("ratingValue")
                        if (!rv.isNullOrEmpty()) rating = "★ $rv"
                    }
                    val numEps = obj.getIntOrNull("numberOfEpisodes")
                    if (numEps != null && numEps > 1) {
                        totalEpisodes = maxOf(totalEpisodes ?: 0, numEps)
                    }
                    val numSeasons = obj.getIntOrNull("numberOfSeasons")
                    if (numSeasons != null && numSeasons > 0) {
                        totalSeasons = maxOf(totalSeasons ?: 0, numSeasons)
                    }
                    extractLdEpisodes(obj.get("episode"), "1", baseOrigin, pageUrl, collectedEpisodes)
                    val seasonsElem = obj.get("containsSeason")
                    if (seasonsElem != null && seasonsElem.isJsonArray) {
                        for ((sIdx, sEl) in seasonsElem.asJsonArray.withIndex()) {
                            val sObj = sEl.asJsonObjectOrNull() ?: continue
                            val sNum = sObj.getStringOrNull("seasonNumber") ?: "${sIdx + 1}"
                            val sEps = sObj.getIntOrNull("numberOfEpisodes")
                            if (sEps != null && sEps > 1) {
                                totalEpisodes = maxOf(totalEpisodes ?: 0, sEps)
                            }
                            extractLdEpisodes(sObj.get("episode"), sNum, baseOrigin, pageUrl, collectedEpisodes)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. __NEXT_DATA__ and <script type="application/json">
        val jsonScripts = doc.select("script#__NEXT_DATA__, script[type='application/json']")
        for (script in jsonScripts) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            try {
                val root = JsonParser.parseString(raw)
                val state = RecursiveJsonScanState()
                scanJsonStateRecursively(root, baseOrigin, pageUrl, state)
                if (state.totalEpisodes != null && state.totalEpisodes!! > 1) {
                    totalEpisodes = maxOf(totalEpisodes ?: 0, state.totalEpisodes!!)
                }
                if (state.totalSeasons != null && state.totalSeasons!! > 0) {
                    totalSeasons = maxOf(totalSeasons ?: 0, state.totalSeasons!!)
                }
                if (state.episodes.size > collectedEpisodes.size) {
                    collectedEpisodes.clear()
                    collectedEpisodes.addAll(state.episodes)
                }
                if (state.isSeriesHint) isSeriesHint = true
            } catch (_: Exception) {}
        }

        val studios = if (collectedEpisodes.isNotEmpty()) {
            val deduped = collectedEpisodes
                .distinctBy { "${it.season}:${it.number}" }
                .sortedWith(compareBy({ it.season.toIntOrNull() ?: 1 }, { it.number.toIntOrNull() ?: Int.MAX_VALUE }))
                .toMutableList()
            listOf(DubStudio("structured_studio", "Оригінал", mutableListOf(PlayerSource("structured_player", "Плеєр", deduped))))
        } else {
            emptyList()
        }

        return StructuredJsonResult(
            studios = studios,
            totalEpisodes = totalEpisodes,
            totalSeasons = totalSeasons,
            title = title,
            posterUrl = posterUrl,
            description = description,
            rating = rating,
            isSeriesHint = isSeriesHint
        )
    }

    private fun collectJsonLdNodes(elem: JsonElement?, out: MutableList<JsonObject>) {
        if (elem == null || elem.isJsonNull) return
        if (elem.isJsonArray) {
            for (child in elem.asJsonArray) collectJsonLdNodes(child, out)
        } else if (elem.isJsonObject) {
            val obj = elem.asJsonObject
            out.add(obj)
            if (obj.has("@graph")) collectJsonLdNodes(obj.get("@graph"), out)
        }
    }

    private fun extractLdEpisodes(
        epElem: JsonElement?,
        seasonNum: String,
        baseOrigin: String,
        pageUrl: String,
        out: MutableList<EpisodeItem>
    ) {
        if (epElem == null || !epElem.isJsonArray) return
        val arr = epElem.asJsonArray
        for (i in 0 until arr.size()) {
            val epObj = arr[i].asJsonObjectOrNull() ?: continue
            val epNum = epObj.getStringOrNull("episodeNumber") ?: "${i + 1}"
            val epTitle = epObj.getStringOrNull("name") ?: "Episode $epNum"
            val rawUrl = epObj.getStringOrNull("url")
                ?: epObj.getStringOrNull("embedUrl")
                ?: epObj.getStringOrNull("contentUrl")
            val epUrl = if (!rawUrl.isNullOrEmpty()) {
                DlePlaylistParser.normalizeUrl(rawUrl, baseOrigin)
            } else {
                buildEpisodeUrlTemplate(pageUrl)?.buildUrl(seasonNum.toIntOrNull() ?: 1, epNum.toIntOrNull() ?: (i + 1)) ?: pageUrl
            }
            out.add(
                EpisodeItem(
                    id = "ld_s${seasonNum}_e${epNum}_$i",
                    number = epNum,
                    title = epTitle,
                    season = seasonNum,
                    iframeUrl = epUrl
                )
            )
        }
    }

    private class RecursiveJsonScanState {
        var totalEpisodes: Int? = null
        var totalSeasons: Int? = null
        var isSeriesHint: Boolean = false
        val episodes = mutableListOf<EpisodeItem>()
    }

    private fun scanJsonStateRecursively(
        node: JsonElement?,
        baseOrigin: String,
        pageUrl: String,
        state: RecursiveJsonScanState,
        parentSeason: String? = null
    ) {
        if (node == null || node.isJsonNull) return
        if (node.isJsonArray) {
            for (child in node.asJsonArray) {
                scanJsonStateRecursively(child, baseOrigin, pageUrl, state, parentSeason)
            }
            return
        }
        if (!node.isJsonObject) return

        val obj = node.asJsonObject
        val currentSeason = obj.getStringOrNull("season_number")
            ?: obj.getStringOrNull("seasonNumber")
            ?: obj.getStringOrNull("season")
            ?: parentSeason

        for (countKey in listOf(
            "numberOfEpisodes", "number_of_episodes", "totalEpisodes", "total_episodes",
            "episodesCount", "episodes_count"
        )) {
            val v = obj.getIntOrNull(countKey)
            if (v != null && v in 2..2000) {
                state.totalEpisodes = state.totalEpisodes ?: v
                state.isSeriesHint = true
            }
        }
        for (seasonKey in listOf("numberOfSeasons", "number_of_seasons", "totalSeasons", "total_seasons")) {
            val v = obj.getIntOrNull(seasonKey)
            if (v != null && v in 1..200) {
                state.totalSeasons = maxOf(state.totalSeasons ?: 0, v)
                state.isSeriesHint = true
            }
        }

        for (epKey in listOf("episodes", "episodeList", "episodesList", "epList")) {
            val arr = obj.getAsJsonArrayOrNull(epKey)
            if (arr != null && arr.size() > 1) {
                val parsedEps = mutableListOf<EpisodeItem>()
                val tpl = buildEpisodeUrlTemplate(pageUrl)
                for (i in 0 until arr.size()) {
                    val epObj = arr[i].asJsonObjectOrNull() ?: continue
                    val num = epObj.getStringOrNull("number")
                        ?: epObj.getStringOrNull("episode")
                        ?: epObj.getStringOrNull("episodeNumber")
                        ?: epObj.getStringOrNull("episode_number")
                        ?: epObj.getStringOrNull("ep")
                        ?: "${i + 1}"
                    val title = epObj.getStringOrNull("title")
                        ?: epObj.getStringOrNull("name")
                        ?: "Episode $num"
                    val season = epObj.getStringOrNull("season")
                        ?: epObj.getStringOrNull("seasonNumber")
                        ?: epObj.getStringOrNull("season_number")
                        ?: currentSeason
                        ?: "1"
                    val rawUrl = extractUrlFromJsonObj(epObj)
                    val epUrl = if (!rawUrl.isNullOrEmpty()) {
                        DlePlaylistParser.normalizeUrl(rawUrl, baseOrigin)
                    } else {
                        val sInt = season.toIntOrNull() ?: 1
                        val eInt = num.toIntOrNull() ?: (i + 1)
                        tpl?.buildUrl(sInt, eInt) ?: "$pageUrl?ep=$eInt"
                    }
                    parsedEps.add(
                        EpisodeItem(
                            id = "next_s${season}_e${num}_$i",
                            number = num,
                            title = title,
                            season = season,
                            iframeUrl = epUrl
                        )
                    )
                }
                val existingKeys = state.episodes.mapTo(mutableSetOf()) { "${it.season}:${it.number}" }
                val existingSeasons = state.episodes.map { it.season }.toSet()
                val newSeasons = parsedEps.map { it.season }.toSet()
                val hasDisjointSeasons = existingSeasons.isNotEmpty() && (newSeasons - existingSeasons).isNotEmpty()
                if (hasDisjointSeasons) {
                    for (ep in parsedEps) {
                        if (existingKeys.add("${ep.season}:${ep.number}")) {
                            state.episodes.add(ep)
                        }
                    }
                    state.isSeriesHint = true
                } else if (parsedEps.size > state.episodes.size) {
                    state.episodes.clear()
                    state.episodes.addAll(parsedEps)
                    state.isSeriesHint = true
                }
            }
        }

        val ignoredJsonContainers = setOf(
            "trending", "recommended", "recommendations", "related", "similar",
            "popular", "latest", "recent", "spotlight", "suggestions", "schedule",
            "comments", "mostviewed", "most_viewed", "topairing", "top_airing",
            "featured", "sidebar"
        )
        for (entry in obj.entrySet()) {
            if (entry.key.lowercase(Locale.ROOT) in ignoredJsonContainers) continue
            if (entry.value.isJsonObject || entry.value.isJsonArray) {
                scanJsonStateRecursively(entry.value, baseOrigin, pageUrl, state, currentSeason)
            }
        }
    }

    internal fun parseDomEpisodes(
        doc: Document,
        pageUrl: String,
        baseOrigin: String
    ): List<DubStudio> {
        // 1. Data-attribute episode nodes
        val dataNodes = doc.select(
            "[data-file], [data-iframe], [data-embed], [data-video], " +
                ".episodes [data-src], .episode-list [data-src], .ep-list [data-src], .playlists-videos [data-src], " +
                ".episodes [data-url], .episode-list [data-url], [data-episode][data-url], [data-ep][data-url], " +
                "[data-episode][data-href], [data-ep][data-href]"
        )
        if (dataNodes.isNotEmpty()) {
            val grouped = linkedMapOf<String, LinkedHashMap<String, PlayerSource>>()
            for ((idx, node) in dataNodes.withIndex()) {
                val rawUrl = node.attr("data-file")
                    .ifEmpty { node.attr("data-iframe") }
                    .ifEmpty { node.attr("data-embed") }
                    .ifEmpty { node.attr("data-video") }
                    .ifEmpty { node.attr("data-src") }
                    .ifEmpty { node.attr("data-url") }
                    .ifEmpty { node.attr("data-href") }
                    .ifEmpty { node.attr("data-link") }
                    .trim()
                if (rawUrl.isEmpty() || rawUrl.startsWith("#") || rawUrl.startsWith("javascript")) continue
                val lowerRaw = rawUrl.lowercase(Locale.ROOT)
                if (lowerRaw.endsWith(".jpg") || lowerRaw.endsWith(".png") || lowerRaw.endsWith(".webp") || lowerRaw.endsWith(".css")) continue

                val epUrl = DlePlaylistParser.normalizeUrl(rawUrl, baseOrigin)
                val studioName = node.attr("data-voice").trim().ifEmpty { "Оригінал" }
                val serverName = node.attr("data-server").trim()
                val (pIdRaw, pNameRaw) = ExtractorUtils.identifyPlayer(epUrl)
                val pId = if (serverName.isNotEmpty()) "${pIdRaw}_${serverName.lowercase(Locale.ROOT)}" else pIdRaw
                val pName = serverName.ifEmpty { pNameRaw }

                val epTitle = node.text().trim().ifEmpty { node.attr("title").trim() }.ifEmpty { "Серія ${idx + 1}" }
                val epNum = node.attr("data-number").trim()
                    .ifEmpty { node.attr("data-episode").trim() }
                    .ifEmpty { node.attr("data-ep").trim() }
                    .ifEmpty { extractEpNumber(epTitle, idx + 1) }
                val season = node.attr("data-season").trim().ifEmpty { "1" }

                val studioBucket = grouped.getOrPut(studioName) { LinkedHashMap() }
                val ps = studioBucket.getOrPut(pId) { PlayerSource(pId, pName) }
                ps.episodes.add(
                    EpisodeItem("dom_data_${idx}_$epNum", epNum, epTitle, season, epUrl)
                )
            }
            val result = grouped.entries.mapIndexed { sIdx, (sName, playersMap) ->
                DubStudio("dom_studio_$sIdx", sName, playersMap.values.toMutableList())
            }.filter { s -> s.players.any { it.episodes.isNotEmpty() } }

            if (result.any { s -> s.players.any { it.episodes.size > 1 } }) {
                return result
            }
        }

        // 2. <select> dropdowns for episodes
        val selects = doc.select("select")
        val template = buildEpisodeUrlTemplate(pageUrl)
        for (sel in selects) {
            val meta = (sel.id() + " " + sel.attr("name") + " " + sel.className() + " " + sel.attr("aria-label")).lowercase(Locale.ROOT)
            val options = sel.select("option")
            val isEpSelect = meta.contains("episode") || meta.contains("seria") || meta.contains("series") ||
                options.count { EPISODE_TEXT_PATTERN.matcher(it.text()).find() } >= 2
            if (!isEpSelect || options.size < 2) continue

            val eps = mutableListOf<EpisodeItem>()
            for ((idx, opt) in options.withIndex()) {
                val valAttr = opt.attr("value").trim()
                val text = opt.text().trim()
                if (valAttr.isEmpty() && text.isEmpty()) continue
                val epNum = extractEpNumber(text.ifEmpty { valAttr }, idx + 1)
                val epUrl = when {
                    valAttr.startsWith("http") || valAttr.startsWith("/") || valAttr.startsWith("//") ->
                        DlePlaylistParser.normalizeUrl(valAttr, baseOrigin)
                    valAttr.isNotEmpty() && valAttr.toIntOrNull() != null && template != null ->
                        template.buildUrl(template.defaultSeason, valAttr.toInt())
                    else -> {
                        val decoded = ExtractorUtils.decodeBase64Utf8(valAttr)
                        if (decoded.contains("http")) {
                            val m = Pattern.compile("(https?://[^\\s\"'<>]+)").matcher(decoded)
                            if (m.find()) m.group(1) ?: continue else continue
                        } else {
                            continue
                        }
                    }
                }
                eps.add(
                    EpisodeItem(
                        id = "sel_ep_${idx}_$epNum",
                        number = epNum,
                        title = text.ifEmpty { "Episode $epNum" },
                        season = "1",
                        iframeUrl = epUrl
                    )
                )
            }
            if (eps.size > 1) {
                val ps = PlayerSource("select_player", "Плеєр", eps)
                return listOf(DubStudio("select_studio", "Оригінал", mutableListOf(ps)))
            }
        }

        // 3. Episode Link Lists (<a href="...">) in dedicated containers
        val containerLinks = doc.select(
            ".episodes a, .episode-list a, #episodes a, .ep-list a, .eplister a, .episodelist a, " +
                "ul.episodios a, .les-content a, .ss-list a, a.ep-item, .season-episodes a, " +
                ".list-episodes a, .halim-list-eps a, .all-episode a, [class*='episode-list'] a, " +
                "[class*='episodelist'] a, [id*='episodes'] a, a[data-episode], a[data-ep]"
        )

        val containerParsed = parseEpisodeLinks(containerLinks, baseOrigin)
        if (containerParsed.isNotEmpty()) {
            val ps = PlayerSource("dom_links_player", "Плеєр", containerParsed.toMutableList())
            return listOf(DubStudio("dom_links_studio", "Оригінал", mutableListOf(ps)))
        }

        // 4. Fallback scan of all <a href> matching explicit episode URL or text patterns
        val ignoredContainerSelector = "header, nav, footer, aside, .sidebar, #sidebar, .related, .recommended, " +
            ".similar, .comments, .popular, .trending, .latest-episodes, .widget, " +
            "[class*='sidebar'], [class*='related'], [class*='recommend'], [class*='popular'], [class*='trending']"
        val allLinks = doc.select("a[href]")
        val candidateGroups = linkedMapOf<String, MutableList<EpisodeItem>>()
        val pageDir = pageUrl.substringBefore("?").substringBefore("#").trimEnd('/').substringBeforeLast("/")
        for ((idx, link) in allLinks.withIndex()) {
            if (link.closest(ignoredContainerSelector) != null) continue
            val href = link.attr("href").trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript")) continue
            val cardText = extractCardText(link)
            val cleanedDisplayText = stripIsoDates(cardText).ifEmpty { link.attr("title").trim() }
            if (SEASON_TAB_TEXT_PATTERN.matcher(link.text().trim()).matches()) continue

            val fullHref = DlePlaylistParser.normalizeUrl(href, baseOrigin)
            if (baseOrigin.isNotEmpty() && !fullHref.startsWith(baseOrigin, ignoreCase = true)) continue
            val lowerHref = fullHref.lowercase(Locale.ROOT)
            if (lowerHref.contains("/page/") || lowerHref.contains("/genre/") || lowerHref.contains("/year/") ||
                lowerHref.contains("/tag/") || lowerHref.contains("/actor/") || lowerHref.contains("/director/")
            ) continue

            val seasonEp = extractSeasonAndEpisodeFromUrl(fullHref) ?: extractSeasonAndEpisodeFromText(cardText)
            val epFromUrl = extractEpisodeFromUrl(fullHref)
            val epTextMatch = EPISODE_TEXT_PATTERN.matcher(cleanedDisplayText)
            val hasEpText = epTextMatch.find()

            val item = if (seasonEp != null) {
                val (s, e) = seasonEp
                EpisodeItem(
                    id = "link_s${s}_e${e}_$idx",
                    number = e.toString(),
                    title = cleanedDisplayText.ifEmpty { "Season $s Episode $e" },
                    season = s.toString(),
                    iframeUrl = fullHref
                )
            } else if (epFromUrl != null || hasEpText) {
                val epNum = epFromUrl?.toString() ?: epTextMatch.group(1) ?: continue
                EpisodeItem(
                    id = "link_e${epNum}_$idx",
                    number = epNum,
                    title = cleanedDisplayText.ifEmpty { "Episode $epNum" },
                    season = "1",
                    iframeUrl = fullHref
                )
            } else {
                null
            }

            if (item != null) {
                val linkDir = fullHref.substringBefore("?").substringBefore("#").trimEnd('/').substringBeforeLast("/")
                candidateGroups.getOrPut(linkDir) { mutableListOf() }.add(item)
            }
        }

        val chosenCandidates = candidateGroups[pageDir]?.takeIf { it.isNotEmpty() }
            ?: candidateGroups.values.maxByOrNull { group ->
                val distinctEps = group.map { "${it.season}:${it.number}" }.distinct()
                val minEp = group.mapNotNull { it.number.toIntOrNull() }.minOrNull() ?: Int.MAX_VALUE
                // Prefer groups starting near episode 1 and having more distinct episodes
                distinctEps.size * 100 - if (minEp <= 3) 0 else 50
            }
            ?: emptyList()

        val deduped = filterEpisodeOutliers(
            chosenCandidates
                .distinctBy { "${it.season}:${it.number}" }
                .sortedWith(compareBy({ it.season.toIntOrNull() ?: 1 }, { it.number.toIntOrNull() ?: Int.MAX_VALUE }))
        )

        if (deduped.isNotEmpty()) {
            val ps = PlayerSource("dom_scan_player", "Плеєр", deduped.toMutableList())
            return listOf(DubStudio("dom_scan_studio", "Оригінал", mutableListOf(ps)))
        }

        return emptyList()
    }

    private fun filterEpisodeOutliers(episodes: List<EpisodeItem>): List<EpisodeItem> {
        if (episodes.size < 3) return episodes
        val bySeason = episodes.groupBy { it.season }
        val filtered = mutableListOf<EpisodeItem>()
        for ((_, seasonEps) in bySeason) {
            val sorted = seasonEps.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
            var cutIdx = sorted.size
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1].number.toIntOrNull() ?: continue
                val curr = sorted[i].number.toIntOrNull() ?: continue
                val gap = curr - prev
                val remaining = sorted.size - i
                if (gap > maxOf(30, i * 2) && remaining <= 2 && prev <= 200) {
                    cutIdx = i
                    break
                }
            }
            filtered.addAll(sorted.subList(0, cutIdx))
        }
        return filtered.sortedWith(compareBy({ it.season.toIntOrNull() ?: 1 }, { it.number.toIntOrNull() ?: Int.MAX_VALUE }))
    }

    private fun extractCardText(link: org.jsoup.nodes.Element): String {
        val ownText = link.text().trim()
        val attrTitle = link.attr("title").trim()
        val card = link.closest("article, li, .item, .card, .post, .episode, .ep-item, [class*='episode'], [class*='card']")
            ?: link.parent()
        val singleCardLinkText = if (card != null) {
            val hrefs = card.select("a[href]").map { it.attr("href").trim() }.filter { it.isNotEmpty() }.distinct()
            if (hrefs.size == 1) card.text().trim() else ""
        } else {
            ""
        }
        return listOf(ownText, singleCardLinkText, attrTitle)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .trim()
    }

    private fun stripIsoDates(text: String): String {
        return text
            .replace(Regex("\\b(?:19|20)\\d{2}[-/.]\\d{2}[-/.]\\d{2}\\b"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun parseEpisodeLinks(links: org.jsoup.select.Elements, baseOrigin: String): List<EpisodeItem> {
        val result = mutableListOf<EpisodeItem>()
        for ((idx, link) in links.withIndex()) {
            val href = link.attr("href").trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript")) continue
            val fullHref = DlePlaylistParser.normalizeUrl(href, baseOrigin)
            val cardText = extractCardText(link)
            val cleanedText = stripIsoDates(cardText)
            val seasonEp = extractSeasonAndEpisodeFromUrl(fullHref) ?: extractSeasonAndEpisodeFromText(cardText)
            val season = link.attr("data-season").trim().ifEmpty { seasonEp?.first?.toString() ?: "1" }
            val epNum = link.attr("data-episode").trim()
                .ifEmpty { link.attr("data-ep").trim() }
                .ifEmpty { link.attr("data-number").trim() }
                .ifEmpty { seasonEp?.second?.toString() ?: "" }
                .ifEmpty { extractEpisodeFromUrl(fullHref)?.toString() ?: "" }
                .ifEmpty { extractEpNumber(cleanedText, idx + 1) }

            val epTitle = link.attr("title").trim().ifEmpty { cleanedText }.ifEmpty { "Episode $epNum" }
            result.add(
                EpisodeItem(
                    id = "ep_s${season}_e${epNum}_$idx",
                    number = epNum,
                    title = epTitle,
                    season = season,
                    iframeUrl = fullHref
                )
            )
        }
        return filterEpisodeOutliers(
            result
                .distinctBy { "${it.season}:${it.number}" }
                .sortedWith(compareBy({ it.season.toIntOrNull() ?: 1 }, { it.number.toIntOrNull() ?: Int.MAX_VALUE }))
        )
    }

    internal fun buildEpisodeUrlTemplate(url: String): EpisodeUrlTemplate? {
        val clean = url.trim()
        if (clean.isEmpty()) return null

        // 1. /tv/{id}/{season}/{episode} or /embed/tv/{id}/{season}-{episode}
        val tvMatch = Pattern.compile("^(https?://[^?#]+?/(?:embed/)?tv/[^/]+/)(\\d{1,3})([/-])(\\d{1,4})(.*)$", Pattern.CASE_INSENSITIVE).matcher(clean)
        if (tvMatch.find()) {
            val prefix = tvMatch.group(1) ?: return null
            val season = tvMatch.group(2)?.toIntOrNull() ?: 1
            val sep = tvMatch.group(3) ?: "/"
            val suffix = tvMatch.group(5) ?: ""
            return EpisodeUrlTemplate("$prefix{season}$sep{episode}$suffix", supportsSeason = true, defaultSeason = season)
        }

        // 2. /season-{s}/episode-{e} or /s{s}/e{e}
        val seasonEpMatch = Pattern.compile("^(https?://[^?#]+?/season[-_/]?)(\\d{1,3})(/episode[-_/]?)(\\d{1,4})(.*)$", Pattern.CASE_INSENSITIVE).matcher(clean)
        if (seasonEpMatch.find()) {
            val p1 = seasonEpMatch.group(1) ?: return null
            val s = seasonEpMatch.group(2)?.toIntOrNull() ?: 1
            val p2 = seasonEpMatch.group(3) ?: return null
            val suffix = seasonEpMatch.group(5) ?: ""
            return EpisodeUrlTemplate("$p1{season}$p2{episode}$suffix", supportsSeason = true, defaultSeason = s)
        }

        val seSlashMatch = Pattern.compile("^(https?://[^?#]+?/s)(\\d{1,3})(/e)(\\d{1,4})(.*)$", Pattern.CASE_INSENSITIVE).matcher(clean)
        if (seSlashMatch.find()) {
            val p1 = seSlashMatch.group(1) ?: return null
            val s = seSlashMatch.group(2)?.toIntOrNull() ?: 1
            val p2 = seSlashMatch.group(3) ?: return null
            val suffix = seSlashMatch.group(5) ?: ""
            return EpisodeUrlTemplate("$p1{season}$p2{episode}$suffix", supportsSeason = true, defaultSeason = s)
        }

        // 3. s{s}e{e} compact in URL
        val seCompact = Pattern.compile("^(https?://[^?#]*?s)(\\d{1,3})(e)(\\d{1,4})(.*)$", Pattern.CASE_INSENSITIVE).matcher(clean)
        if (seCompact.find()) {
            val p1 = seCompact.group(1) ?: return null
            val s = seCompact.group(2)?.toIntOrNull() ?: 1
            val p2 = seCompact.group(3) ?: return null
            val suffix = seCompact.group(5) ?: ""
            return EpisodeUrlTemplate("$p1{season}$p2{episode}$suffix", supportsSeason = true, defaultSeason = s)
        }

        // 3b. {s}x{e} pattern in URL (e.g., /episode/doctor-who-13x1/)
        val xCompact = Pattern.compile("^(https?://[^?#]*?[^0-9a-zA-Z])(\\d{1,3})(x)(\\d{1,4})((?:$|[^0-9a-zA-Z].*))$", Pattern.CASE_INSENSITIVE).matcher(clean)
        if (xCompact.find()) {
            val p1 = xCompact.group(1) ?: return null
            val s = xCompact.group(2)?.toIntOrNull() ?: 1
            val p2 = xCompact.group(3) ?: return null
            val suffix = xCompact.group(5) ?: ""
            if (s !in RESOLUTION_NUMBERS) {
                return EpisodeUrlTemplate("$p1{season}$p2{episode}$suffix", supportsSeason = true, defaultSeason = s)
            }
        }

        // 4. Query parameters ?season=1&episode=1 or ?s=1&e=1 or ?sea=1&epi=1
        val querySeasonEp = Pattern.compile("([?&](?:season|sea|s)=)(\\d{1,3})(&(?:episode|epi|ep|e)=)(\\d{1,4})", Pattern.CASE_INSENSITIVE).matcher(clean)
        if (querySeasonEp.find()) {
            val s = querySeasonEp.group(2)?.toIntOrNull() ?: 1
            val replaced = querySeasonEp.replaceFirst("$1{season}$3{episode}")
            return EpisodeUrlTemplate(replaced, supportsSeason = true, defaultSeason = s)
        }

        // 5. -episode-{e}, /episode-{e}, -ep-{e}, /ep-{e}, /episodes/{e}
        val pathEp = Pattern.compile("(/(?:episodes?|ep)/|[-/](?:episode|ep)[-_]?)(\\d{1,4})(?=$|/|\\.|\\?|&|-)", Pattern.CASE_INSENSITIVE).matcher(clean)
        if (pathEp.find()) {
            val replaced = pathEp.replaceFirst("$1{episode}")
            return EpisodeUrlTemplate(replaced, supportsSeason = false, defaultSeason = 1)
        }

        // 6. Query parameter ?ep={e} or ?episode={e}
        val queryEp = Pattern.compile("([?&](?:episode|ep)=)(\\d{1,4})", Pattern.CASE_INSENSITIVE).matcher(clean)
        if (queryEp.find()) {
            val replaced = queryEp.replaceFirst("$1{episode}")
            return EpisodeUrlTemplate(replaced, supportsSeason = false, defaultSeason = 1)
        }

        // 7. Fallback query param
        val sep = if (clean.contains("?")) "&" else "?"
        return EpisodeUrlTemplate("$clean${sep}ep={episode}", supportsSeason = false, defaultSeason = 1)
    }

    internal fun expandEpisodesByTemplate(
        template: EpisodeUrlTemplate,
        totalEpisodes: Int,
        targetSeason: Int = template.defaultSeason,
        existingEpisodes: List<EpisodeItem> = emptyList()
    ): MutableList<EpisodeItem> {
        val byKey = linkedMapOf<String, EpisodeItem>()
        for (ep in existingEpisodes) {
            byKey["${ep.season}:${ep.number}"] = ep
        }
        for (num in 1..totalEpisodes) {
            val key = "$targetSeason:$num"
            if (!byKey.containsKey(key)) {
                byKey[key] = EpisodeItem(
                    id = "tpl_s${targetSeason}_e$num",
                    number = num.toString(),
                    title = "Episode $num",
                    season = targetSeason.toString(),
                    iframeUrl = template.buildUrl(targetSeason, num)
                )
            }
        }
        return byKey.values
            .sortedWith(compareBy({ it.season.toIntOrNull() ?: 1 }, { it.number.toIntOrNull() ?: Int.MAX_VALUE }))
            .toMutableList()
    }

    @Suppress("UNUSED_PARAMETER")
    internal fun extractExpectedEpisodeCount(doc: Document, html: String, structuredCount: Int?): Int? {
        var best = structuredCount?.takeIf { it in 2..2000 } ?: 0
        val cleanDoc = doc.clone()
        cleanDoc.select(
            "script, style, noscript, header, nav, footer, aside, .sidebar, #sidebar, " +
                ".related, .recommended, .similar, .comments, .popular, .trending, .widget, " +
                "[class*='sidebar'], [class*='related'], [class*='recommend'], [class*='popular'], [class*='trending']"
        ).remove()
        val bodyText = cleanDoc.body().text()
        if (bodyText.isNotEmpty()) {
            val m1 = PAGE_EP_COUNT_PATTERN_1.matcher(bodyText)
            if (m1.find()) {
                val n = m1.group(1)?.toIntOrNull() ?: 0
                if (n in 2..2000) best = maxOf(best, n)
            }
            val m2 = PAGE_EP_COUNT_PATTERN_2.matcher(bodyText)
            if (m2.find()) {
                val n = m2.group(1)?.toIntOrNull() ?: 0
                if (n in 2..2000) best = maxOf(best, n)
            }
        }
        return best.takeIf { it > 1 }
    }

    private data class MetadataExpansionResult(
        val fallbackPoster: String? = null,
        val fallbackDescription: String? = null,
        val fallbackRating: String? = null
    )

    private fun expandEpisodesByCountAndMetadata(
        client: OkHttpClient?,
        cleanUrl: String,
        html: String,
        seriesTitle: String,
        studios: MutableList<DubStudio>,
        expectedEpisodeCount: Int?,
        hasSeriesIndicators: Boolean,
        discoveredIframeUrls: List<String>,
        allowExternalApi: Boolean
    ): MetadataExpansionResult {
        val existingEpisodes = studios.flatMap { it.players }.flatMap { it.episodes }
        val maxSeenEp = existingEpisodes.mapNotNull { it.number.toIntOrNull() }.maxOrNull() ?: 0
        val distinctSeasons = existingEpisodes.map { it.season }.distinct()
        if (distinctSeasons.size > 1) {
            if (allowExternalApi && client != null) {
                val backupUrls = buildBackupEmbedSampleUrls(extractTmdbIdFromUrl(cleanUrl), findImdbId(cleanUrl, html))
                if (backupUrls.isNotEmpty()) {
                    addTemplateIframePlayersForSeasons(studios, backupUrls)
                }
            }
            splitMultiSeasonStudioIfNeeded(studios)
            return MetadataExpansionResult()
        }
        val hasContiguousSingleSeasonList = existingEpisodes.size >= 2 &&
            distinctSeasons.size <= 1 &&
            maxSeenEp == existingEpisodes.size

        // Only expand beyond existing episodes if we don't already have an explicit complete contiguous list,
        // or if expectedEpisodeCount is a structured/reasonable expansion above existingEpisodes.
        val allowTextOverride = !hasContiguousSingleSeasonList ||
            (expectedEpisodeCount != null && expectedEpisodeCount in (existingEpisodes.size + 1)..(existingEpisodes.size * 4 + 12))
        val effectiveExpected = if (allowTextOverride) expectedEpisodeCount else null

        var targetCount = maxOf(
            effectiveExpected ?: 0,
            if (maxSeenEp > existingEpisodes.size && maxSeenEp <= 2000) maxSeenEp else 0
        )
        var tvMazeEpisodes: List<EpisodeItem> = emptyList()
        var fallbackPoster: String? = null
        var fallbackDesc: String? = null
        var fallbackRating: String? = null

        var resolvedImdbId: String? = findImdbId(cleanUrl, html)
        val tmdbId = extractTmdbIdFromUrl(cleanUrl)

        val rawTemplateSource = existingEpisodes.firstOrNull()?.iframeUrl?.takeIf { hasEpisodeInUrl(it) }
            ?: cleanUrl.takeIf { hasEpisodeInUrl(it) }
            ?: discoveredIframeUrls.firstOrNull { isTemplateIframeUrl(it) }

        val templateSourceUrl = rawTemplateSource ?: cleanUrl
        val pageTemplate = buildEpisodeUrlTemplate(templateSourceUrl)

        if (allowExternalApi && client != null && existingEpisodes.size <= 1 && targetCount <= 1 && hasSeriesIndicators) {
            val tvMazeResult = fetchTvMazeEpisodes(client, seriesTitle, resolvedImdbId, if (rawTemplateSource != null) pageTemplate else null)
            if (tvMazeResult != null) {
                tvMazeEpisodes = tvMazeResult.episodes
                fallbackPoster = tvMazeResult.posterUrl
                fallbackDesc = tvMazeResult.description
                fallbackRating = tvMazeResult.rating
                if (resolvedImdbId.isNullOrEmpty()) {
                    resolvedImdbId = tvMazeResult.imdbId
                }
            }
            if (tvMazeEpisodes.size <= 1) {
                val jikanResult = fetchJikanAnimeInfo(client, seriesTitle)
                if (jikanResult != null) {
                    if (jikanResult.episodeCount > 1) {
                        targetCount = jikanResult.episodeCount
                    }
                    if (fallbackPoster == null) fallbackPoster = jikanResult.posterUrl
                    if (fallbackDesc == null) fallbackDesc = jikanResult.description
                    if (fallbackRating == null) fallbackRating = jikanResult.rating
                }
            }
        }

        val backupEmbedUrls = if (allowExternalApi) buildBackupEmbedSampleUrls(tmdbId, resolvedImdbId) else emptyList()
        val combinedIframeUrls = (discoveredIframeUrls + backupEmbedUrls).distinct()

        // Apply TVMaze multi-episode metadata if available
        if (tvMazeEpisodes.size > 1 && existingEpisodes.size <= 1) {
            val primaryBackupTpl = if (rawTemplateSource == null) {
                backupEmbedUrls.firstOrNull()?.let { buildEpisodeUrlTemplate(it) }
            } else {
                null
            }
            val preparedEpisodes = if (primaryBackupTpl != null) {
                tvMazeEpisodes.map { ep ->
                    val s = ep.season.toIntOrNull() ?: 1
                    val e = ep.number.toIntOrNull() ?: 1
                    ep.copy(iframeUrl = primaryBackupTpl.buildUrl(s, e))
                }
            } else {
                tvMazeEpisodes
            }

            val explicitSeasonInUrl = extractExplicitSeasonFromUrl(cleanUrl)
            val filtered = if (hasEpisodeInUrl(cleanUrl) || explicitSeasonInUrl != null) {
                val targetSeason = explicitSeasonInUrl
                    ?: extractSeasonAndEpisodeFromUrl(templateSourceUrl)?.first
                    ?: pageTemplate?.defaultSeason
                    ?: 1
                val sEps = preparedEpisodes.filter { it.season == targetSeason.toString() }
                if (sEps.size > 1) sEps else preparedEpisodes
            } else {
                preparedEpisodes
            }

            if (filtered.size > 1) {
                val mainPlayerName = if (primaryBackupTpl != null) "Плеєр (Auto)" else "Плеєр"
                val mainPlayer = PlayerSource("auto_player", mainPlayerName, filtered.toMutableList())
                if (studios.isEmpty()) {
                    studios.add(DubStudio("auto_studio", "Оригінал", mutableListOf(mainPlayer)))
                } else {
                    studios[0].players[0].episodes = filtered.toMutableList()
                }
                addTemplateIframePlayers(studios, combinedIframeUrls, filtered)
                splitMultiSeasonStudioIfNeeded(studios)
                return MetadataExpansionResult(fallbackPoster, fallbackDesc, fallbackRating)
            }
        }

        // Expand by numeric count (from pagination, JSON, text, or Jikan)
        if (targetCount > 1 && pageTemplate != null && existingEpisodes.size < targetCount) {
            val targetSeason = extractSeasonAndEpisodeFromUrl(templateSourceUrl)?.first ?: pageTemplate.defaultSeason
            val expanded = expandEpisodesByTemplate(pageTemplate, targetCount, targetSeason, existingEpisodes)
            if (studios.isEmpty()) {
                studios.add(DubStudio("auto_studio", "Оригінал", mutableListOf(PlayerSource("auto_player", "Плеєр", expanded))))
            } else {
                studios[0].players[0].episodes = expanded
            }
            addTemplateIframePlayers(studios, combinedIframeUrls, expanded)
        } else if (backupEmbedUrls.isNotEmpty() && existingEpisodes.isNotEmpty()) {
            addTemplateIframePlayers(studios, backupEmbedUrls, existingEpisodes)
        }

        splitMultiSeasonStudioIfNeeded(studios)
        return MetadataExpansionResult(fallbackPoster, fallbackDesc, fallbackRating)
    }

    private fun extractExplicitSeasonFromUrl(url: String): Int? {
        val m = Pattern.compile("(?:season[-_/=?]|/s)(\\d{1,3})(?:$|/|&|-)", Pattern.CASE_INSENSITIVE).matcher(url)
        return if (m.find()) m.group(1)?.toIntOrNull() else null
    }

    private fun buildBackupEmbedSampleUrls(tmdbId: String?, imdbId: String?): List<String> {
        val urls = mutableListOf<String>()
        if (!tmdbId.isNullOrBlank()) {
            urls.add("https://vidsrc.buzz/embed/tv/$tmdbId/1/1")
            urls.add("https://www.2embed.cc/embedtv/$tmdbId&s=1&e=1")
            urls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=1&e=1")
            urls.add("https://vidsrc.to/embed/tv/$tmdbId/1/1")
        } else if (!imdbId.isNullOrBlank()) {
            urls.add("https://vidsrc.buzz/embed/tv/$imdbId/1/1")
            urls.add("https://www.2embed.cc/embedtv/$imdbId&s=1&e=1")
            urls.add("https://multiembed.mov/?video_id=$imdbId&s=1&e=1")
            urls.add("https://vidsrc.to/embed/tv/$imdbId/1/1")
        }
        return urls
    }

    private fun buildBackupMovieEmbedUrls(tmdbId: String?, imdbId: String?): List<String> {
        val urls = mutableListOf<String>()
        if (!tmdbId.isNullOrBlank()) {
            urls.add("https://vidsrc.buzz/embed/movie/$tmdbId")
            urls.add("https://www.2embed.cc/embed/$tmdbId")
            urls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1")
        } else if (!imdbId.isNullOrBlank()) {
            urls.add("https://vidsrc.buzz/embed/movie/$imdbId")
            urls.add("https://www.2embed.cc/embed/$imdbId")
            urls.add("https://multiembed.mov/?video_id=$imdbId")
        }
        return urls
    }

    private fun addTemplateIframePlayersForSeasons(
        studios: MutableList<DubStudio>,
        sampleEmbedUrls: List<String>
    ) {
        for (studio in studios) {
            val baseEps = studio.players.firstOrNull()?.episodes ?: continue
            if (baseEps.isEmpty()) continue
            for ((idx, ifrUrl) in sampleEmbedUrls.withIndex()) {
                val tpl = buildEpisodeUrlTemplate(ifrUrl) ?: continue
                val (_, pName) = ExtractorUtils.identifyPlayer(ifrUrl, idx + 1)
                val epList = baseEps.map { ep ->
                    val s = ep.season.toIntOrNull() ?: tpl.defaultSeason
                    val e = ep.number.toIntOrNull() ?: 1
                    ep.copy(id = "ifr_${idx}_s${s}_e$e", iframeUrl = tpl.buildUrl(s, e))
                }.toMutableList()
                val alreadyHas = studio.players.any { it.episodes.firstOrNull()?.iframeUrl == epList.firstOrNull()?.iframeUrl }
                if (!alreadyHas && epList.isNotEmpty()) {
                    studio.players.add(PlayerSource("ifr_player_$idx", "$pName (Embed)", epList))
                }
            }
        }
    }

    private fun addTemplateIframePlayers(
        studios: MutableList<DubStudio>,
        discoveredIframeUrls: List<String>,
        baseEpisodes: List<EpisodeItem>
    ) {
        if (studios.isEmpty()) return
        for ((idx, ifrUrl) in discoveredIframeUrls.withIndex()) {
            if (!isTemplateIframeUrl(ifrUrl)) continue
            val tpl = buildEpisodeUrlTemplate(ifrUrl) ?: continue
            val (_, pName) = ExtractorUtils.identifyPlayer(ifrUrl, idx + 1)
            val epList = baseEpisodes.map { ep ->
                val s = ep.season.toIntOrNull() ?: tpl.defaultSeason
                val e = ep.number.toIntOrNull() ?: 1
                ep.copy(
                    id = "ifr_${idx}_s${s}_e$e",
                    iframeUrl = tpl.buildUrl(s, e)
                )
            }.toMutableList()
            val alreadyHasSamePlayer = studios[0].players.any { it.episodes.firstOrNull()?.iframeUrl == epList.firstOrNull()?.iframeUrl }
            if (!alreadyHasSamePlayer && epList.isNotEmpty()) {
                studios[0].players.add(PlayerSource("ifr_player_$idx", "$pName (Embed)", epList))
            }
        }
    }

    private data class ExternalSeriesMetadata(
        val episodes: List<EpisodeItem> = emptyList(),
        val episodeCount: Int = 0,
        val posterUrl: String? = null,
        val description: String? = null,
        val rating: String? = null,
        val imdbId: String? = null
    )

    private fun fetchTvMazeEpisodes(
        client: OkHttpClient,
        seriesTitle: String,
        imdbId: String?,
        template: EpisodeUrlTemplate?
    ): ExternalSeriesMetadata? {
        return try {
            var showObj: JsonObject? = null
            if (!imdbId.isNullOrEmpty()) {
                val lookupReq = Request.Builder()
                    .url("https://api.tvmaze.com/lookup/shows?imdb=$imdbId")
                    .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                    .build()
                val lookupBody = ExtractorUtils.executeForString(client, lookupReq)
                if (!lookupBody.isNullOrEmpty()) {
                    showObj = JsonParser.parseString(lookupBody).asJsonObjectOrNull()
                }
            }

            if (showObj == null && seriesTitle.length >= 2 && seriesTitle != "Онлайн Відео") {
                val q = URLEncoder.encode(seriesTitle, "UTF-8")
                val searchReq = Request.Builder()
                    .url("https://api.tvmaze.com/singlesearch/shows?q=$q&embed=episodes")
                    .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                    .build()
                val searchBody = ExtractorUtils.executeForString(client, searchReq)
                if (!searchBody.isNullOrEmpty()) {
                    showObj = JsonParser.parseString(searchBody).asJsonObjectOrNull()
                }
            }

            if (showObj == null) return null

            val showId = showObj.getIntOrNull("id")
            var epArray = showObj.getAsJsonObjectOrNull("_embedded")?.getAsJsonArrayOrNull("episodes")
            if ((epArray == null || epArray.size() == 0) && showId != null) {
                val epReq = Request.Builder()
                    .url("https://api.tvmaze.com/shows/$showId/episodes")
                    .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                    .build()
                val epBody = ExtractorUtils.executeForString(client, epReq)
                if (!epBody.isNullOrEmpty()) {
                    val elem = JsonParser.parseString(epBody)
                    if (elem.isJsonArray) epArray = elem.asJsonArray
                }
            }

            if (epArray == null || epArray.size() == 0) return null

            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val eps = mutableListOf<EpisodeItem>()
            for (i in 0 until epArray.size()) {
                val epObj = epArray[i].asJsonObjectOrNull() ?: continue
                val num = epObj.getIntOrNull("number") ?: continue
                val season = epObj.getIntOrNull("season") ?: 1
                val airdate = epObj.getStringOrNull("airdate") ?: ""
                if (airdate.isNotEmpty() && airdate > todayStr) continue

                val epName = epObj.getStringOrNull("name")?.trim()?.ifEmpty { "Episode $num" } ?: "Episode $num"
                val epUrl = template?.buildUrl(season, num) ?: ""
                eps.add(
                    EpisodeItem(
                        id = "tvmaze_s${season}_e$num",
                        number = num.toString(),
                        title = epName,
                        season = season.toString(),
                        iframeUrl = epUrl
                    )
                )
            }

            val poster = showObj.getAsJsonObjectOrNull("image")?.let {
                it.getStringOrNull("original") ?: it.getStringOrNull("medium")
            }
            val summaryRaw = showObj.getStringOrNull("summary")
            val desc = summaryRaw?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotEmpty() }
            val ratingNum = showObj.getAsJsonObjectOrNull("rating")?.get("average")?.let {
                if (it.isJsonPrimitive) it.asString else null
            }
            val rating = ratingNum?.let { "★ $it" }

            val resolvedImdbId = imdbId ?: showObj.getAsJsonObjectOrNull("externals")?.getStringOrNull("imdb")

            ExternalSeriesMetadata(
                episodes = eps,
                episodeCount = eps.size,
                posterUrl = poster,
                description = desc,
                rating = rating,
                imdbId = resolvedImdbId
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchJikanAnimeInfo(client: OkHttpClient, title: String): ExternalSeriesMetadata? {
        if (title.length < 2 || title == "Онлайн Відео") return null
        return try {
            val q = URLEncoder.encode(title, "UTF-8")
            val req = Request.Builder()
                .url("https://api.jikan.moe/v4/anime?q=$q&limit=1")
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .build()
            val body = ExtractorUtils.executeForString(client, req) ?: return null
            val root = JsonParser.parseString(body).asJsonObjectOrNull() ?: return null
            val data = root.getAsJsonArrayOrNull("data") ?: return null
            if (data.size() == 0) return null
            val anime = data[0].asJsonObjectOrNull() ?: return null
            var epCount = anime.getIntOrNull("episodes") ?: 0
            val malId = anime.getIntOrNull("mal_id")

            if (epCount == 0 && malId != null) {
                val epReq = Request.Builder()
                    .url("https://api.jikan.moe/v4/anime/$malId/episodes")
                    .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                    .build()
                val epBody = ExtractorUtils.executeForString(client, epReq)
                if (!epBody.isNullOrEmpty()) {
                    val epRoot = JsonParser.parseString(epBody).asJsonObjectOrNull()
                    val epData = epRoot?.getAsJsonArrayOrNull("data")
                    if (epData != null && epData.size() > 0) {
                        epCount = epData.size()
                    }
                }
            }

            val poster = anime.getAsJsonObjectOrNull("images")
                ?.getAsJsonObjectOrNull("jpg")
                ?.getStringOrNull("large_image_url")
            val desc = anime.getStringOrNull("synopsis")?.takeIf { it.isNotEmpty() }
            val score = anime.get("score")?.takeIf { it.isJsonPrimitive }?.asString?.let { "★ $it" }

            ExternalSeriesMetadata(
                episodeCount = epCount,
                posterUrl = poster,
                description = desc,
                rating = score
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun detectSeriesIndicators(
        cleanUrl: String,
        doc: Document,
        rawTitle: String,
        structuredHint: Boolean,
        iframeUrls: List<String>,
        expectedCount: Int?
    ): Boolean {
        if (structuredHint || (expectedCount != null && expectedCount > 1)) return true
        if (isSeriesUrlCandidate(cleanUrl)) {
            return true
        }
        val ogType = doc.selectFirst("meta[property='og:type']")?.attr("content")?.lowercase(Locale.ROOT) ?: ""
        if (ogType.contains("tv_show") || ogType.contains("episode") || ogType.contains("series")) {
            return true
        }
        if (iframeUrls.any { isTemplateIframeUrl(it) || it.lowercase(Locale.ROOT).contains("/serial/") }) {
            return true
        }
        val lowerTitle = rawTitle.lowercase(Locale.ROOT)
        return lowerTitle.contains("season ") || lowerTitle.contains("episode ") ||
            lowerTitle.contains("серіал") || lowerTitle.contains("сезон") || lowerTitle.contains("серія")
    }

    private fun isTemplateIframeUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return TEMPLATE_IFRAME_HOSTS.any { lower.contains(it) } || lower.contains("/tv/")
    }

    private fun hasEpisodeInUrl(url: String): Boolean {
        return extractSeasonAndEpisodeFromUrl(url) != null || extractEpisodeFromUrl(url) != null
    }

    private fun extractSeasonAndEpisodeFromUrl(url: String): Pair<Int, Int>? {
        val m = SEASON_EP_PATH_PATTERN.matcher(url)
        if (m.find()) {
            val s = (m.group(1) ?: m.group(3) ?: m.group(5))?.toIntOrNull()
            val e = (m.group(2) ?: m.group(4) ?: m.group(6))?.toIntOrNull()
            if (s != null && e != null) return s to e
        }
        val m2 = S_E_COMPACT_PATTERN.matcher(url)
        if (m2.find()) {
            val s = m2.group(1)?.toIntOrNull()
            val e = m2.group(2)?.toIntOrNull()
            if (s != null && e != null) return s to e
        }
        val mx = SEASON_X_EP_PATTERN.matcher(url)
        while (mx.find()) {
            val s = mx.group(1)?.toIntOrNull() ?: continue
            val e = mx.group(2)?.toIntOrNull() ?: continue
            if (s in 1..150 && e in 1..2000 && s !in RESOLUTION_NUMBERS) {
                return s to e
            }
        }
        val qm = Pattern.compile("[?&](?:season|sea|s)=(\\d{1,3}).*?[?&](?:episode|epi|ep|e)=(\\d{1,4})", Pattern.CASE_INSENSITIVE).matcher(url)
        if (qm.find()) {
            val s = qm.group(1)?.toIntOrNull()
            val e = qm.group(2)?.toIntOrNull()
            if (s != null && e != null) return s to e
        }
        return null
    }

    private fun extractSeasonAndEpisodeFromText(text: String): Pair<Int, Int>? {
        val mx = SEASON_X_EP_PATTERN.matcher(text)
        while (mx.find()) {
            val s = mx.group(1)?.toIntOrNull() ?: continue
            val e = mx.group(2)?.toIntOrNull() ?: continue
            if (s in 1..150 && e in 1..2000 && s !in RESOLUTION_NUMBERS) {
                return s to e
            }
        }
        val m2 = S_E_COMPACT_PATTERN.matcher(text)
        if (m2.find()) {
            val s = m2.group(1)?.toIntOrNull()
            val e = m2.group(2)?.toIntOrNull()
            if (s != null && e != null && s in 1..150 && e in 1..2000) return s to e
        }
        return null
    }

    private fun fetchAdditionalSeasonsIfAvailable(
        client: OkHttpClient,
        doc: Document,
        html: String,
        cleanUrl: String,
        baseOrigin: String,
        studios: MutableList<DubStudio>
    ) {
        val existingEpisodes = studios.flatMap { it.players }.flatMap { it.episodes }
        val existingSeasons = existingEpisodes.map { it.season }.distinct()
        if (existingSeasons.size > 1) return

        val seasonTargets = linkedMapOf<Int, String>()

        // 1. Season switcher <select> ("Select Seasons" / season dropdowns)
        for (sel in doc.select("select")) {
            val meta = (sel.id() + " " + sel.attr("name") + " " + sel.className() + " " + sel.attr("aria-label")).lowercase(Locale.ROOT)
            val opts = sel.select("option")
            val isSeasonSelect = meta.contains("season") || meta.contains("сезон") ||
                opts.count { SEASON_TAB_TEXT_PATTERN.matcher(it.text().trim()).matches() } >= 2
            if (!isSeasonSelect || opts.size < 2) continue

            for (opt in opts) {
                val sNum = extractSeasonNumber(opt.text().trim())?.toIntOrNull() ?: continue
                val valAttr = opt.attr("value").trim().ifEmpty { opt.attr("data-url").trim() }
                if (valAttr.startsWith("http") || valAttr.startsWith("/") || valAttr.startsWith("//")) {
                    val full = DlePlaylistParser.normalizeUrl(valAttr, baseOrigin)
                    if (full != cleanUrl && isSameSiteHost(cleanUrl, full)) {
                        seasonTargets[sNum] = full
                    }
                }
            }
        }

        // 2. Explicit Season links ("Season 1", "Season 2", ... tabs/pagination)
        if (seasonTargets.isEmpty()) {
            for (link in doc.select("a[href]")) {
                val text = link.text().trim()
                val m = SEASON_TAB_TEXT_PATTERN.matcher(text)
                if (!m.find()) continue
                val sNum = m.group(1)?.toIntOrNull() ?: continue
                val href = link.attr("href").trim()
                if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript")) continue
                val full = DlePlaylistParser.normalizeUrl(href, baseOrigin)
                if (full != cleanUrl && isSameSiteHost(cleanUrl, full)) {
                    seasonTargets[sNum] = full
                }
            }
        }

        val collectedBySeason = mutableListOf<EpisodeItem>()
        collectedBySeason.addAll(existingEpisodes)

        if (seasonTargets.isNotEmpty()) {
            for ((sNum, targetUrl) in seasonTargets.entries.sortedBy { it.key }.take(20)) {
                val sHtml = fetchHtmlWithReferer(client, targetUrl, cleanUrl) ?: continue
                val sDoc = Jsoup.parse(sHtml, targetUrl)
                val sStudios = parseDomEpisodes(sDoc, targetUrl, baseOrigin)
                val sEps = sStudios.flatMap { it.players }.flatMap { it.episodes }
                    .map { ep -> if (ep.season == "1" && sNum > 1) ep.copy(season = sNum.toString()) else ep }
                collectedBySeason.addAll(sEps)
            }
        } else {
            // 3. WordPress Toroflix / DooPlay AJAX season switching (admin-ajax.php)
            val ajaxPostId = doc.selectFirst("[data-post], [data-post-id], #wp-admin-ajax")
                ?.let { it.attr("data-post").ifEmpty { it.attr("data-post-id") } }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val ajaxSeasons = linkedSetOf<Int>()
            for (el in doc.select("[data-season]")) {
                val sNum = el.attr("data-season").trim().toIntOrNull() ?: continue
                if (sNum in 1..150) ajaxSeasons.add(sNum)
            }
            for (sel in doc.select("select")) {
                if (sel.select("option").count { SEASON_TAB_TEXT_PATTERN.matcher(it.text().trim()).matches() } >= 2) {
                    for (opt in sel.select("option")) {
                        val sNum = extractSeasonNumber(opt.text().trim())?.toIntOrNull() ?: continue
                        if (sNum in 1..150) ajaxSeasons.add(sNum)
                    }
                }
            }
            val currentSeasonsSet = existingEpisodes.mapNotNull { it.season.toIntOrNull() }.toSet()
            val missingAjaxSeasons = ajaxSeasons.filter { it !in currentSeasonsSet }
            val wpAjaxAction = when {
                html.contains("action_select_season", ignoreCase = true) -> "action_select_season"
                html.contains("tr_select_season", ignoreCase = true) -> "tr_select_season"
                else -> null
            }

            if (!ajaxPostId.isNullOrEmpty() && wpAjaxAction != null && missingAjaxSeasons.isNotEmpty() && baseOrigin.isNotEmpty()) {
                val ajaxEndpoint = "$baseOrigin/wp-admin/admin-ajax.php"
                for (sNum in missingAjaxSeasons.sorted().take(20)) {
                    try {
                        val form = okhttp3.FormBody.Builder()
                            .add("action", wpAjaxAction)
                            .add("season", sNum.toString())
                            .add("post", ajaxPostId)
                            .build()
                        val req = Request.Builder()
                            .url(ajaxEndpoint)
                            .post(form)
                            .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                            .header("Referer", cleanUrl)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .build()
                        val respHtml = ExtractorUtils.executeForString(client, req) ?: continue
                        if (respHtml.isBlank()) continue
                        val sDoc = Jsoup.parse("<html><body>$respHtml</body></html>", cleanUrl)
                        val sEps = parseEpisodeLinks(sDoc.select("a[href]"), baseOrigin)
                            .map { ep -> if (ep.season == "1" && sNum > 1) ep.copy(season = sNum.toString()) else ep }
                        collectedBySeason.addAll(sEps)
                    } catch (_: Exception) {}
                }
            }
        }

        val merged = collectedBySeason
            .distinctBy { "${it.season}:${it.number}" }
            .sortedWith(compareBy({ it.season.toIntOrNull() ?: 1 }, { it.number.toIntOrNull() ?: Int.MAX_VALUE }))

        if (merged.size > existingEpisodes.size) {
            if (studios.isEmpty()) {
                studios.add(DubStudio("dom_seasons_studio", "Оригінал", mutableListOf(PlayerSource("dom_seasons_player", "Плеєр", merged.toMutableList()))))
            } else {
                studios[0].players[0].episodes = merged.toMutableList()
            }
        }
    }

    internal fun splitMultiSeasonStudioIfNeeded(studios: MutableList<DubStudio>) {
        if (studios.size != 1) return
        val onlyStudio = studios[0]
        if (onlyStudio.players.isEmpty()) return
        val distinctSeasons = onlyStudio.players
            .flatMap { it.episodes }
            .map { it.season }
            .distinct()
        if (distinctSeasons.size <= 1) return

        val sortedSeasons = distinctSeasons.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        val originalPlayers = onlyStudio.players.toList()

        studios.clear()
        for (s in sortedSeasons) {
            val seasonPlayers = mutableListOf<PlayerSource>()
            for (p in originalPlayers) {
                val seasonEps = p.episodes
                    .filter { it.season == s }
                    .sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
                    .toMutableList()
                if (seasonEps.isNotEmpty()) {
                    seasonPlayers.add(PlayerSource("${p.id}_s$s", p.name, seasonEps))
                }
            }
            if (seasonPlayers.isNotEmpty()) {
                studios.add(
                    DubStudio(
                        id = "season_studio_$s",
                        name = "Сезон $s",
                        players = seasonPlayers
                    )
                )
            }
        }
    }

    private fun extractEpisodeFromUrl(url: String): Int? {
        val m = EPISODE_URL_PATTERN.matcher(url)
        return if (m.find()) m.group(1)?.toIntOrNull() else null
    }

    private fun extractEpNumber(text: String, fallback: Int): String {
        val cleaned = stripIsoDates(text)
        extractSeasonAndEpisodeFromText(cleaned)?.let { return it.second.toString() }
        val m = EPISODE_TEXT_PATTERN.matcher(cleaned)
        if (m.find()) return m.group(1) ?: fallback.toString()
        val numM = Pattern.compile("(\\d{1,4})").matcher(cleaned)
        return if (numM.find()) numM.group(1) ?: fallback.toString() else fallback.toString()
    }

    private fun extractSeasonNumber(text: String): String? {
        val m = Pattern.compile("(?:season|сезон|s)\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE).matcher(text)
        if (m.find()) return m.group(1)
        val numM = Pattern.compile("(\\d{1,3})").matcher(text)
        return if (numM.find()) numM.group(1) else null
    }

    private fun findImdbId(url: String, html: String): String? {
        val m1 = IMDB_PATTERN.matcher(url)
        if (m1.find()) return m1.group(1)
        val m2 = IMDB_PATTERN.matcher(html)
        if (m2.find()) return m2.group(1)
        return null
    }

    private fun isSameSiteHost(url1: String, url2: String): Boolean {
        return try {
            val h1 = URI(ExtractorUtils.ensureHttpsScheme(url1)).host?.removePrefix("www.")?.lowercase(Locale.ROOT)
            val h2 = URI(ExtractorUtils.ensureHttpsScheme(url2)).host?.removePrefix("www.")?.lowercase(Locale.ROOT)
            !h1.isNullOrEmpty() && h1 == h2
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchHtmlWithReferer(client: OkHttpClient, url: String, referer: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ExtractorUtils.DEFAULT_UA)
            .header("Referer", referer)
            .build()
        return ExtractorUtils.executeForString(client, req)
    }

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? =
        if (this != null && this.isJsonObject) this.asJsonObject else null

    private fun JsonElement?.asJsonArrayOrNull(): JsonArray? =
        if (this != null && this.isJsonArray) this.asJsonArray else null

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else null

    private fun JsonObject.getAsJsonArrayOrNull(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) getAsJsonArray(key) else null

    private fun JsonObject.getStringOrNull(key: String): String? =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else null

    private fun JsonObject.getIntOrNull(key: String): Int? =
        try {
            if (has(key) && get(key).isJsonPrimitive) get(key).asInt else null
        } catch (_: Exception) {
            null
        }
}
