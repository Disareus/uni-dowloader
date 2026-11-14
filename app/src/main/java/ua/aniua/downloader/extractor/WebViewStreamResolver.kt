package ua.aniua.downloader.extractor

import android.annotation.SuppressLint
import android.content.Context
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
import ua.aniua.downloader.model.StreamQuality
import java.util.Collections
import java.util.Locale
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
        val cleanPath = lower.substringBefore("?").substringBefore("#")
        if (cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") || cleanPath.endsWith(".png") ||
            cleanPath.endsWith(".webp") || cleanPath.endsWith(".gif") || cleanPath.endsWith(".svg") ||
            cleanPath.endsWith(".css") || cleanPath.endsWith(".js") || cleanPath.endsWith(".ts") ||
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
                var finished = false
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
                    mainHandler.post { cleanup() }
                }

                webView.webViewClient = object : WebViewClient() {
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
                        if (finished) return
                        pendingEvalRunnable?.let { mainHandler.removeCallbacks(it) }
                        val runnable = Runnable {
                            if (finished) return@Runnable
                            view?.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})();") { raw ->
                                if (finished) return@evaluateJavascript
                                val html = unescapeEvalResult(raw)
                                val isChallenge = GenericSeriesExtractor.isChallengeOrBlockedHtml(html)
                                val currentUrl = view.url ?: loadedUrl ?: url
                                // Give Cloudflare JS challenge one extra settle cycle if not timed out
                                if (isChallenge) {
                                    mainHandler.postDelayed({
                                        if (finished) return@postDelayed
                                        view.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})();") { raw2 ->
                                            if (finished) return@evaluateJavascript
                                            finished = true
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
                                } else {
                                    finished = true
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
                var completed = false
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
                    if (completed) return
                    completed = true
                    mainHandler.post {
                        cleanup()
                        if (cont.isActive) cont.resume(streams)
                    }
                }

                cont.invokeOnCancellation {
                    completed = true
                    mainHandler.post { cleanup() }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (completed) return null
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
                            val cookie = ExtractorUtils.getCookieHeader(reqUrl) ?: ExtractorUtils.getCookieHeader(effectiveRef)
                            if (!cookie.isNullOrBlank()) {
                                sq.headers["Cookie"] = cookie
                            }
                            finishWith(listOf(sq))
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (completed) return
                        pollRunnable?.let { mainHandler.removeCallbacks(it) }
                        var attempt = 0
                        val checker = object : Runnable {
                            override fun run() {
                                if (completed) return
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
                                    if (completed) return@evaluateJavascript
                                    val html = unescapeEvalResult(raw)
                                    val curUrl = view.url ?: url ?: pageUrl
                                    if (html.isNotEmpty()) {
                                        val direct = GenericPlayerResolver.extractDirectStreamsFromHtml(html, curUrl, client = null)
                                        if (direct.isNotEmpty()) {
                                            direct.forEach { sq ->
                                                sq.headers.putIfAbsent("Referer", curUrl)
                                                val cookie = ExtractorUtils.getCookieHeader(sq.url) ?: ExtractorUtils.getCookieHeader(curUrl)
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(context: Context): WebView {
        val webView = WebView(context.applicationContext ?: context)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = ExtractorUtils.DEFAULT_UA
        }
        return webView
    }
}
