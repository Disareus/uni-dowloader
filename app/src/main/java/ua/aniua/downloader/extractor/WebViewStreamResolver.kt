package ua.aniua.downloader.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import ua.aniua.downloader.model.StreamQuality
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Headless WebView helper for loading JS-rendered / cookie-gated pages
 * and intercepting dynamically requested .m3u8 / .mp4 streams.
 */
object WebViewStreamResolver {

    data class RenderedPageResult(
        val html: String = "",
        val finalUrl: String = "",
        val interceptedMediaUrls: List<String> = emptyList(),
        val isChallengePage: Boolean = false
    )

    fun unescapeEvalResult(rawJson: String?): String {
        if (rawJson.isNullOrBlank() || rawJson == "null") return ""
        return try {
            val elem = JsonParser.parseString(rawJson)
            if (elem.isJsonPrimitive) elem.asString else ""
        } catch (_: Exception) {
            rawJson.removeSurrounding("\"")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
    }

    fun isInterceptableMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.contains("doubleclick") || lower.contains("googlesyndication") || lower.contains("analytics")) return false
        if (ExtractorUtils.isImageUrl(url)) return false
        val cleanPath = lower.substringBefore("?").substringBefore("#")
        if (cleanPath.endsWith(".css") || cleanPath.endsWith(".js") || cleanPath.endsWith(".ts") ||
            cleanPath.endsWith(".m4s") || cleanPath.endsWith(".vtt") || cleanPath.endsWith(".srt")
        ) {
            return false
        }
        return ExtractorUtils.isDirectMediaUrl(url)
    }

    suspend fun fetchRenderedHtml(
        context: Context,
        url: String,
        timeoutMs: Long = 12000L
    ): RenderedPageResult = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val mainHandler = Handler(Looper.getMainLooper())
                val intercepted = Collections.synchronizedList(mutableListOf<String>())
                val finished = AtomicBoolean(false)
                var pendingEvalRunnable: Runnable? = null

                val webView = createConfiguredWebView(context)
                fun cleanup() {
                    pendingEvalRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingEvalRunnable = null
                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Throwable) {}
                    try {
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Throwable) {}
                }

                cont.invokeOnCancellation {
                    finished.set(true)
                    mainHandler.post { cleanup() }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase(Locale.ROOT)
                        return scheme != "http" && scheme != "https"
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString()
                        if (isInterceptableMediaUrl(reqUrl) && reqUrl != null && !intercepted.contains(reqUrl)) {
                            intercepted.add(reqUrl)
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (finished.get()) return
                        pendingEvalRunnable?.let { mainHandler.removeCallbacks(it) }
                        val runnable = Runnable {
                            if (finished.get()) return@Runnable
                            view?.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})();") { raw ->
                                if (finished.get()) return@evaluateJavascript
                                val html = unescapeEvalResult(raw)
                                val isChallenge = GenericSeriesExtractor.isChallengeOrBlockedHtml(html)
                                val currentUrl = view.url ?: loadedUrl ?: url
                                // Give Cloudflare JS challenge one extra settle cycle if not timed out
                                if (isChallenge) {
                                    mainHandler.postDelayed({
                                        if (finished.get()) return@postDelayed
                                        view.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})();") innerEval@{ raw2 ->
                                            if (!finished.compareAndSet(false, true)) return@innerEval
                                            val html2 = unescapeEvalResult(raw2).ifEmpty { html }
                                            val finalUrl2 = view.url ?: currentUrl
                                            cleanup()
                                            if (cont.isActive) {
                                                cont.resume(
                                                    RenderedPageResult(
                                                        html = html2,
                                                        finalUrl = finalUrl2,
                                                        interceptedMediaUrls = intercepted.toList(),
                                                        isChallengePage = GenericSeriesExtractor.isChallengeOrBlockedHtml(html2)
                                                    )
                                                )
                                            }
                                        }
                                    }, 3500L)
                                } else if (finished.compareAndSet(false, true)) {
                                    cleanup()
                                    if (cont.isActive) {
                                        cont.resume(
                                            RenderedPageResult(
                                                html = html,
                                                finalUrl = currentUrl,
                                                interceptedMediaUrls = intercepted.toList(),
                                                isChallengePage = false
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        pendingEvalRunnable = runnable
                        mainHandler.postDelayed(runnable, 1200L)
                    }
                }

                webView.loadUrl(url)
            }
        } ?: RenderedPageResult(finalUrl = url)
    }

    suspend fun resolveStreamViaWebView(
        context: Context,
        pageUrl: String,
        referer: String?,
        timeoutMs: Long = 15000L
    ): List<StreamQuality> = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val mainHandler = Handler(Looper.getMainLooper())
                val completed = AtomicBoolean(false)
                var pollRunnable: Runnable? = null

                val webView = createConfiguredWebView(context)

                fun cleanup() {
                    pollRunnable?.let { mainHandler.removeCallbacks(it) }
                    pollRunnable = null
                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Throwable) {}
                    try {
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Throwable) {}
                }

                fun finishWith(streams: List<StreamQuality>) {
                    if (!completed.compareAndSet(false, true)) return
                    mainHandler.post {
                        cleanup()
                        if (cont.isActive) cont.resume(streams)
                    }
                }

                cont.invokeOnCancellation {
                    completed.set(true)
                    mainHandler.post { cleanup() }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase(Locale.ROOT)
                        return scheme != "http" && scheme != "https"
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (completed.get()) return null
                        val reqUrl = request?.url?.toString()
                        if (isInterceptableMediaUrl(reqUrl) && reqUrl != null) {
                            val isHls = ExtractorUtils.isHlsUrl(reqUrl)
                            val sq = StreamQuality(if (isHls) "Auto (HLS)" else "1080p", if (isHls) 720 else 1080, reqUrl, isHls)
                            val reqHeaders = request.requestHeaders ?: emptyMap()
                            val effectiveRef = reqHeaders["Referer"] ?: reqHeaders["referer"] ?: referer ?: pageUrl
                            sq.headers["Referer"] = effectiveRef
                            val reqOrigin = reqHeaders["Origin"] ?: reqHeaders["origin"] ?: ExtractorUtils.extractBaseOrigin(effectiveRef, "")
                            if (reqOrigin.isNotEmpty()) {
                                sq.headers["Origin"] = reqOrigin
                            }
                            val cookie = ExtractorUtils.getCookieHeader(reqUrl)
                                ?: if (ExtractorUtils.isSameSiteForCookies(reqUrl, effectiveRef)) ExtractorUtils.getCookieHeader(effectiveRef) else null
                            if (!cookie.isNullOrBlank()) {
                                sq.headers["Cookie"] = cookie
                            }
                            finishWith(listOf(sq))
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (completed.get()) return
                        pollRunnable?.let { mainHandler.removeCallbacks(it) }
                        var attempt = 0
                        val checker = object : Runnable {
                            override fun run() {
                                if (completed.get()) return
                                attempt++
                                val shouldClick = attempt == 1
                                view?.evaluateJavascript(
                                    """(function(){
                                        if ($shouldClick) {
                                            var btns = document.querySelectorAll('#pl_but, #bigplay, #bigPlay, .jw-icon-display, .vjs-big-play-button, .play-button, button[aria-label="Play"]');
                                            for (var i = 0; i < btns.length; i++) {
                                                try { btns[i].click(); } catch(e) {}
                                            }
                                        }
                                        return document.documentElement ? document.documentElement.outerHTML : '';
                                    })();""".trimIndent()
                                ) { raw ->
                                    if (completed.get()) return@evaluateJavascript
                                    val html = unescapeEvalResult(raw)
                                    val curUrl = view.url ?: url ?: pageUrl
                                    if (html.isNotEmpty()) {
                                        val direct = GenericPlayerResolver.extractDirectStreamsFromHtml(html, curUrl, client = null)
                                        if (direct.isNotEmpty()) {
                                            direct.forEach { sq ->
                                                sq.headers.putIfAbsent("Referer", curUrl)
                                                val cookie = ExtractorUtils.getCookieHeader(sq.url)
                                                    ?: if (ExtractorUtils.isSameSiteForCookies(sq.url, curUrl)) ExtractorUtils.getCookieHeader(curUrl) else null
                                                if (!cookie.isNullOrBlank()) sq.headers["Cookie"] = cookie
                                            }
                                            finishWith(direct)
                                            return@evaluateJavascript
                                        }
                                    }
                                    if (attempt < 5) {
                                        mainHandler.postDelayed(this, 1000L)
                                    } else {
                                        finishWith(emptyList())
                                    }
                                }
                            }
                        }
                        pollRunnable = checker
                        mainHandler.postDelayed(checker, 900L)
                    }
                }

                val extraHeaders = mutableMapOf<String, String>()
                if (!referer.isNullOrBlank()) {
                    extraHeaders["Referer"] = referer
                }
                if (extraHeaders.isNotEmpty()) {
                    webView.loadUrl(pageUrl, extraHeaders)
                } else {
                    webView.loadUrl(pageUrl)
                }
            }
        } ?: emptyList()
    }

    private data class YouTubeWebContext(
        val poToken: String,
        val visitorData: String,
        val signatureTimestamp: Int?,
        val clientVersion: String?,
        val cookieHeader: String?
    )

    suspend fun resolveYouTubeStreamsViaWebView(
        context: Context,
        pageUrl: String,
        client: OkHttpClient,
        timeoutMs: Long = 14000L
    ): List<StreamQuality> {
        val videoId = YouTubeExtractor.extractVideoId(pageUrl) ?: return emptyList()
        val watchUrl = "https://m.youtube.com/watch?v=$videoId"

        val webCtx = withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<YouTubeWebContext?> { cont ->
                    val mainHandler = Handler(Looper.getMainLooper())
                    val completed = AtomicBoolean(false)
                    val capturedPot = AtomicReference<String?>(null)
                    val capturedVisitorData = AtomicReference<String?>(null)
                    val capturedSts = AtomicReference<Int?>(null)
                    val capturedClientVer = AtomicReference<String?>(null)
                    var pollRunnable: Runnable? = null

                    val webView = createConfiguredWebView(context, YouTubeExtractor.MWEB_USER_AGENT).apply {
                        measure(
                            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                        )
                        layout(0, 0, 1080, 1920)
                    }

                    fun cleanup() {
                        pollRunnable?.let { mainHandler.removeCallbacks(it) }
                        pollRunnable = null
                        try {
                            CookieManager.getInstance().flush()
                        } catch (_: Throwable) {}
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Throwable) {}
                    }

                    fun tryFinishIfReady() {
                        val pot = capturedPot.get()
                        val vd = capturedVisitorData.get()
                        if (pot.isNullOrBlank() || vd.isNullOrBlank()) return
                        if (!completed.compareAndSet(false, true)) return
                        val cookie = ExtractorUtils.getCookieHeader(watchUrl)
                        val result = YouTubeWebContext(
                            poToken = pot,
                            visitorData = vd,
                            signatureTimestamp = capturedSts.get(),
                            clientVersion = capturedClientVer.get(),
                            cookieHeader = cookie
                        )
                        mainHandler.post {
                            cleanup()
                            if (cont.isActive) cont.resume(result)
                        }
                    }

                    cont.invokeOnCancellation {
                        completed.set(true)
                        mainHandler.post { cleanup() }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (completed.get()) return null
                            val reqUrl = request?.url?.toString() ?: return null
                            if (reqUrl.contains("googlevideo.com/videoplayback", ignoreCase = true)) {
                                val pot = try {
                                    Uri.parse(reqUrl).getQueryParameter("pot")
                                } catch (_: Exception) {
                                    null
                                }
                                if (!pot.isNullOrBlank()) {
                                    capturedPot.compareAndSet(null, pot)
                                    tryFinishIfReady()
                                }
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (completed.get()) return
                            pollRunnable?.let { mainHandler.removeCallbacks(it) }
                            var attempt = 0
                            val checker = object : Runnable {
                                override fun run() {
                                    if (completed.get()) return
                                    attempt++
                                    view?.evaluateJavascript(
                                        """(function(){
                                            try {
                                                if (!window.__aniUaPotHooked && window.fetch) {
                                                    window.__aniUaPotHooked = true;
                                                    var origFetch = window.fetch;
                                                    window.fetch = function() {
                                                        try {
                                                            var req = arguments[0];
                                                            var init = arguments[1];
                                                            var u = (typeof req === 'string') ? req : (req && req.url ? req.url : '');
                                                            if (u.indexOf('/youtubei/v1/player') !== -1 && init && typeof init.body === 'string') {
                                                                var parsed = JSON.parse(init.body);
                                                                if (parsed && parsed.serviceIntegrityDimensions && parsed.serviceIntegrityDimensions.poToken) {
                                                                    window.__aniUaCapturedPot = parsed.serviceIntegrityDimensions.poToken;
                                                                }
                                                            }
                                                        } catch(e) {}
                                                        return origFetch.apply(this, arguments);
                                                    };
                                                }
                                                var btns = document.querySelectorAll('.player-control-play-pause-icon, .ytp-play-button, .ytp-large-play-button, button[aria-label*="Play"], button[aria-label*="Відтворити"]');
                                                for (var bi = 0; bi < btns.length; bi++) {
                                                    try { btns[bi].click(); } catch(e) {}
                                                }
                                                var v = document.querySelector('video');
                                                if (v) { v.muted = true; if (v.paused) { v.play().catch(function(){}); } }
                                                var p = document.getElementById('movie_player');
                                                if (p) {
                                                    if (p.mute) p.mute();
                                                    if (p.playVideo) p.playVideo();
                                                }
                                                var cfg = window.ytcfg;
                                                var vd = cfg && cfg.get ? (cfg.get('VISITOR_DATA') || '') : '';
                                                var sts = cfg && cfg.get ? (cfg.get('STS') || 0) : 0;
                                                var ver = cfg && cfg.get ? (cfg.get('INNERTUBE_CLIENT_VERSION') || '') : '';
                                                var pot = window.__aniUaCapturedPot || '';
                                                return JSON.stringify({vd: vd, sts: sts, ver: ver, pot: pot});
                                            } catch(e) { return '{}'; }
                                        })();""".trimIndent()
                                    ) { raw ->
                                        if (completed.get()) return@evaluateJavascript
                                        val jsonStr = unescapeEvalResult(raw)
                                        try {
                                            val obj = JsonParser.parseString(jsonStr).asJsonObject
                                            val vd = obj.get("vd")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                                            val sts = obj.get("sts")?.takeIf { it.isJsonPrimitive }?.asInt
                                            val ver = obj.get("ver")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                                            val pot = obj.get("pot")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                                            if (!vd.isNullOrBlank()) capturedVisitorData.compareAndSet(null, vd)
                                            if (sts != null && sts > 0) capturedSts.compareAndSet(null, sts)
                                            if (!ver.isNullOrBlank()) capturedClientVer.compareAndSet(null, ver)
                                            if (!pot.isNullOrBlank()) capturedPot.compareAndSet(null, pot)
                                        } catch (_: Exception) {}

                                        tryFinishIfReady()
                                        if (!completed.get()) {
                                            if (attempt < 14) {
                                                mainHandler.postDelayed(this, 750L)
                                            } else if (completed.compareAndSet(false, true)) {
                                                cleanup()
                                                if (cont.isActive) cont.resume(null)
                                            }
                                        }
                                    }
                                }
                            }
                            pollRunnable = checker
                            mainHandler.postDelayed(checker, 600L)
                        }
                    }

                    webView.loadUrl(watchUrl)
                }
            }
        } ?: return emptyList()

        return withContext(Dispatchers.IO) {
            YouTubeExtractor.resolveWithMwebPoToken(
                client = client,
                pageUrl = pageUrl,
                poToken = webCtx.poToken,
                visitorData = webCtx.visitorData,
                signatureTimestamp = webCtx.signatureTimestamp,
                clientVersion = webCtx.clientVersion,
                cookieHeader = webCtx.cookieHeader
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(
        context: Context,
        userAgent: String = ExtractorUtils.DEFAULT_UA
    ): WebView {
        val webView = WebView(context.applicationContext ?: context)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgent
        }
        return webView
    }
}
