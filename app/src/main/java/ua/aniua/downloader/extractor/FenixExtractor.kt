package ua.aniua.downloader.extractor

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource
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
        return lower.contains("fenixplay") || lower.contains("fenix") || lower.contains("fnx.")
    }

    private fun decodeBase64Safe(raw: String): String {
        var s = raw.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - s.length % 4) % 4
        if (pad > 0 && pad < 4) {
            s += "=".repeat(pad)
        }
        return try {
            val bytes = Base64.decode(s, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
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

            // 3. Визначаємо номер серії, редакцію (tv/mini) та бажану озвучку (l)
            var epNum = 1
            var edition = playlistJson.get("default_edition")?.asString ?: "tv"
            var targetLabel: String? = null

            try {
                val uri = URI(url)
                if (uri.path.contains("/vod/")) {
                    val rawPart = uri.path.substringAfter("/vod/").substringBefore(".").substringBefore("?")
                    val decodedStr = decodeBase64Safe(rawPart)
                    if (decodedStr.isNotEmpty()) {
                        val vodJson = JsonParser.parseString(decodedStr).asJsonObject
                        if (vodJson.has("e")) epNum = vodJson.get("e").asInt
                        if (vodJson.has("v")) edition = vodJson.get("v").asString
                        if (vodJson.has("l")) targetLabel = vodJson.get("l").asString
                    }
                } else if (uri.query != null) {
                    val params = uri.query.split("&")
                    for (p in params) {
                        if (p.startsWith("ep=")) {
                            epNum = p.substringAfter("=").toIntOrNull() ?: epNum
                        } else if (p.startsWith("edition=")) {
                            edition = p.substringAfter("=")
                        } else if (p.startsWith("voice=") || p.startsWith("studio=") || p.startsWith("l=")) {
                            targetLabel = p.substringAfter("=")
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

            // 5. Вибір потрібного треку (озвучки) з tracks
            var selectedTrack: JsonObject? = null
            if (epObj.has("tracks") && epObj.get("tracks").isJsonArray) {
                val tracks = epObj.getAsJsonArray("tracks")
                if (!targetLabel.isNullOrEmpty()) {
                    for (tElem in tracks) {
                        if (!tElem.isJsonObject) continue
                        val tObj = tElem.asJsonObject
                        val tLabel = tObj.get("label")?.asString ?: ""
                        if (tLabel.equals(targetLabel, ignoreCase = true) ||
                            tLabel.contains(targetLabel, ignoreCase = true) ||
                            targetLabel.contains(tLabel, ignoreCase = true)
                        ) {
                            selectedTrack = tObj
                            break
                        }
                    }
                }
                if (selectedTrack == null && tracks.size() > 0) {
                    // Шукаємо дефолтний трек або беремо перший
                    for (tElem in tracks) {
                        if (!tElem.isJsonObject) continue
                        val tObj = tElem.asJsonObject
                        if (tObj.get("is_default")?.asBoolean == true) {
                            selectedTrack = tObj
                            break
                        }
                    }
                    if (selectedTrack == null && tracks.size() > 0 && tracks.get(0).isJsonObject) {
                        selectedTrack = tracks.get(0).asJsonObject
                    }
                }
            }

            var rawMaster = selectedTrack?.get("hls_url")?.asString ?: epObj.get("hls_master_url")?.asString
            var rawCastAvc = selectedTrack?.get("cast_avc_url")?.asString ?: epObj.get("cast_avc_url")?.asString

            val authMaster = authorizeUrl(rawMaster, mediaToken)
            val authCastAvc = authorizeUrl(rawCastAvc, mediaToken)

            // Важливо: Fenix використовує розділені відео та аудіо доріжки (fMP4) у master.m3u8 (#EXT-X-MEDIA:TYPE=AUDIO).
            // Не розгортаємо master.m3u8 на окремі субплейлисти v_0, інакше відео гратиме/завантажуватиметься без звуку.
            // Передаємо Master Playlist URL прямо в ExoPlayer та Downloader.
            if (!authMaster.isNullOrEmpty()) {
                val sq = StreamQuality("1080p (Fenix HEVC)", 1080, authMaster, true)
                sq.headers["Referer"] = url
                sq.headers["Origin"] = "https://fenixplay.xyz"
                streams.add(sq)

                // Додаємо також опцію для 480p якості
                val sq480 = StreamQuality("480p (Fenix)", 480, "$authMaster#480p", true)
                sq480.headers["Referer"] = url
                sq480.headers["Origin"] = "https://fenixplay.xyz"
                streams.add(sq480)
            }

            if (!authCastAvc.isNullOrEmpty()) {
                val sqAvc = StreamQuality("720p AVC (Fenix)", 720, authCastAvc, true)
                sqAvc.headers["Referer"] = url
                sqAvc.headers["Origin"] = "https://fenixplay.xyz"
                streams.add(sqAvc)
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

    fun parseFenixPage(client: OkHttpClient, pageUrl: String): MediaInfo? {
        return try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", DEFAULT_UA)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful || resp.body == null) return null

            val html = resp.body?.string() ?: return null
            val mPlaylist = PLAYLIST_PATTERN.matcher(html)
            if (!mPlaylist.find()) return null

            val playlistJson = JsonParser.parseString(mPlaylist.group(1)).asJsonObject
            val titleUa = playlistJson.get("title_ua")?.asString ?: "Fenix Аніме"
            val titleOrig = playlistJson.get("title_orig")?.asString
            val posterUrl = playlistJson.get("poster_url")?.asString
            val slug = playlistJson.get("slug")?.asString ?: "anime"

            val episodesList = mutableListOf<EpisodeItem>()
            val rawEpisodes = if (playlistJson.has("episodes") && playlistJson.get("episodes").isJsonArray) {
                playlistJson.getAsJsonArray("episodes")
            } else {
                JsonArray()
            }

            for (i in 0 until rawEpisodes.size()) {
                val epElem = rawEpisodes.get(i)
                if (!epElem.isJsonObject) continue
                val epObj = epElem.asJsonObject

                val epNum = epObj.get("number")?.asInt ?: (i + 1)
                val epTitle = epObj.get("title")?.asString ?: "Серія $epNum"
                val epEdition = epObj.get("edition")?.asString ?: "tv"

                val epUrl = "https://fenixplay.xyz/embed/$slug?ep=$epNum&edition=$epEdition"
                episodesList.add(
                    EpisodeItem(
                        id = "fenix_${slug}_$epNum",
                        number = "$epNum",
                        title = epTitle,
                        season = "1",
                        iframeUrl = epUrl
                    )
                )
            }

            if (episodesList.isEmpty()) {
                episodesList.add(
                    EpisodeItem(
                        id = "fenix_${slug}_1",
                        number = "1",
                        title = titleUa,
                        season = "1",
                        iframeUrl = pageUrl
                    )
                )
            }

            val studio = DubStudio("fenix_default", "Fenix Player")
            val playerSource = PlayerSource("fenix_player", "Fenix")
            playerSource.episodes.addAll(episodesList)
            studio.players.add(playerSource)

            MediaInfo(
                url = pageUrl,
                siteType = "fenix",
                title = titleUa,
                originalTitle = titleOrig,
                posterUrl = posterUrl,
                description = "Відтворення через Fenix Player",
                isSeries = episodesList.size > 1,
                studios = mutableListOf(studio),
                availableQualities = mutableListOf("1080p", "720p", "480p")
            )
        } catch (e: Exception) {
            null
        }
    }
}
