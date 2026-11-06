package ua.aniua.downloader.extractor

import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.StreamQuality
import java.util.regex.Pattern

object GenericPlayerResolver {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun resolvePlayerStreams(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        val url = iframeUrl.trim()
        val lower = url.lowercase()

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

        // 3. Прямий .mp4 потік
        if (lower.contains(".mp4")) {
            return listOf(StreamQuality("1080p", 1080, url, false))
        }

        // 4. Спеціалізовані балансери
        var streams: List<StreamQuality> = emptyList()

        if (lower.contains("ashdi")) {
            streams = AshdiExtractor.extractStreams(client, url, referer)
        } else if (lower.contains("tortuga")) {
            streams = TortugaExtractor.extractStreams(client, url, referer)
        } else if (FenixExtractor.isFenixUrl(url)) {
            streams = FenixExtractor.extractStreams(client, url, referer)
        } else if (PeerTubeExtractor.isPeerTubeUrl(url)) {
            streams = PeerTubeExtractor.extractStreams(client, url, referer)
        } else if (MoonAnimeExtractor.isMoonUrl(url)) {
            streams = MoonAnimeExtractor.extractStreams(client, url, referer)
        } else if (lower.contains("sibnet")) {
            streams = extractSibnetStream(client, url, referer)
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

        // 5. Універсальний fallback для невідомих iframe (PlayerJS / прямий відео-тег)
        return extractGenericIframe(client, url, referer)
    }

    private fun extractSibnetStream(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        return try {
            val req = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Referer", referer ?: "https://video.sibnet.ru/")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful || resp.body == null) return emptyList()

            val html = resp.body?.string() ?: ""
            val pattern = Pattern.compile("src\\s*:\\s*[\"'](/v/[^\"']+\\.mp4)[\"']")
            val m = pattern.matcher(html)
            if (m.find()) {
                val path = m.group(1)
                val fullUrl = "https://video.sibnet.ru$path"
                val q = StreamQuality("720p", 720, fullUrl, false)
                q.headers["Referer"] = iframeUrl
                listOf(q)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractGenericIframe(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        return try {
            val req = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Referer", referer ?: iframeUrl)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful || resp.body == null) return emptyList()

            val html = resp.body?.string() ?: ""

            // PlayerJS file: '...'
            val filePattern = Pattern.compile("file\\s*:\\s*[\"']([^\"']+)[\"']")
            val fm = filePattern.matcher(html)
            if (fm.find()) {
                val fileStr = fm.group(1)?.trim() ?: ""
                val qualities = PlayerJsDecoder.parsePlayerJsQualities(fileStr, iframeUrl)
                if (qualities.isNotEmpty()) return qualities
            }

            // Прямий .m3u8 у коді
            val m3u8Pattern = Pattern.compile("(https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)")
            val mm = m3u8Pattern.matcher(html)
            if (mm.find()) {
                val m3u8Url = mm.group(1) ?: ""
                val resolved = HlsStreamResolver.resolveMasterPlaylist(client, m3u8Url, iframeUrl)
                if (resolved.isNotEmpty()) return resolved
                return listOf(StreamQuality("Auto (HLS)", 720, m3u8Url, true))
            }

            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
