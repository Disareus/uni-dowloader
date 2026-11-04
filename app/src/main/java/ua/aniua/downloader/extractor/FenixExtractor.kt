package ua.aniua.downloader.extractor

import android.util.Base64
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.StreamQuality
import java.net.URI
import java.util.regex.Pattern

object FenixExtractor {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val MEDIA_ACCESS_PATTERN = Pattern.compile("window\\.FENIX_MEDIA_ACCESS\\s*=\\s*(\\{[\\s\\S]*?\\});")
    private val PLAYLIST_PATTERN = Pattern.compile("window\\.FENIX_PLAYLIST\\s*=\\s*(\\{[\\s\\S]*?\\});")

    fun isFenixUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("fenixplay") || lower.contains("fenix")
    }

    fun extractStreams(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        val url = iframeUrl.trim()
        val streams = mutableListOf<StreamQuality>()

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_UA)
                .header("Referer", referer ?: "https://anitube.in.ua/")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful || resp.body == null) return emptyList()

            val html = resp.body?.string() ?: return emptyList()

            // 1. Отримуємо токен доступу до медіа-сервера
            var mediaToken: String? = null
            val mAccess = MEDIA_ACCESS_PATTERN.matcher(html)
            if (mAccess.find()) {
                try {
                    val accessJson = JsonParser.parseString(mAccess.group(1)).asJsonObject
                    if (accessJson.has("token")) {
                        mediaToken = accessJson.get("token").asString
                    }
                } catch (ignored: Exception) {}
            }

            // 2. Отримуємо плейлист FENIX
            val mPlaylist = PLAYLIST_PATTERN.matcher(html)
            if (!mPlaylist.find()) return emptyList()

            val playlistJson = JsonParser.parseString(mPlaylist.group(1)).asJsonObject
            val slug = playlistJson.get("slug")?.asString ?: return emptyList()
            val playbackToken = playlistJson.getAsJsonObject("playback")?.get("token")?.asString ?: ""

            // 3. Визначаємо номер серії та редакцію (tv/mini)
            var epNum = 1
            var edition = playlistJson.get("default_edition")?.asString ?: "tv"

            try {
                val uri = URI(url)
                if (uri.path.contains("/vod/")) {
                    val rawPart = uri.path.substringAfter("/vod/").substringBefore(".").substringBefore("?")
                    val decodedBytes = Base64.decode(rawPart, Base64.URL_SAFE or Base64.NO_WRAP)
                    val vodJson = JsonParser.parseString(String(decodedBytes)).asJsonObject
                    if (vodJson.has("e")) epNum = vodJson.get("e").asInt
                    if (vodJson.has("v")) edition = vodJson.get("v").asString
                } else if (uri.query != null && uri.query.contains("ep=")) {
                    val params = uri.query.split("&")
                    for (p in params) {
                        if (p.startsWith("ep=")) {
                            epNum = p.substringAfter("=").toIntOrNull() ?: epNum
                        } else if (p.startsWith("edition=")) {
                            edition = p.substringAfter("=")
                        }
                    }
                }
            } catch (ignored: Exception) {}

            // 4. Запитуємо детальну інформацію про серію через Fenix Playback API
            val apiUrl = "https://fenixplay.xyz/api/playback/$slug/episodes/$edition/$epNum"
            val apiReq = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", DEFAULT_UA)
                .header("Referer", url)
                .header("X-Fenix-Playback", playbackToken)
                .header("Accept", "application/json")
                .build()

            val apiResp = client.newCall(apiReq).execute()
            if (!apiResp.isSuccessful || apiResp.body == null) return emptyList()

            val apiBody = apiResp.body?.string() ?: return emptyList()
            val apiObj = JsonParser.parseString(apiBody).asJsonObject
            val epObj = apiObj.getAsJsonObject("episode") ?: return emptyList()

            val rawMaster = epObj.get("hls_master_url")?.asString
            val rawCastAvc = epObj.get("cast_avc_url")?.asString

            // Додаємо токен авторизації до HLS потоків
            val authMaster = authorizeUrl(rawMaster, mediaToken)
            val authCastAvc = authorizeUrl(rawCastAvc, mediaToken)

            if (!authMaster.isNullOrEmpty()) {
                val masterStreams = HlsStreamResolver.resolveMasterPlaylist(client, authMaster, url)
                if (masterStreams.isNotEmpty()) {
                    streams.addAll(masterStreams)
                } else {
                    val sq = StreamQuality("1080p (Fenix)", 1080, authMaster, true)
                    sq.headers["Referer"] = url
                    streams.add(sq)
                }
            }

            if (streams.isEmpty() && !authCastAvc.isNullOrEmpty()) {
                val avcStreams = HlsStreamResolver.resolveMasterPlaylist(client, authCastAvc, url)
                if (avcStreams.isNotEmpty()) {
                    streams.addAll(avcStreams)
                } else {
                    val sq = StreamQuality("720p AVC (Fenix)", 720, authCastAvc, true)
                    sq.headers["Referer"] = url
                    streams.add(sq)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return streams
    }

    private fun authorizeUrl(url: String?, token: String?): String? {
        if (url.isNullOrEmpty()) return null
        if (token.isNullOrEmpty()) return url
        val sep = if (url.contains("?")) "&" else "?"
        return "$url${sep}token=$token"
    }
}
