package ua.aniua.downloader.extractor

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
import java.net.URLEncoder
import java.util.regex.Pattern

object FenixExtractor {

    private val MEDIA_ACCESS_PATTERN = Pattern.compile("window\\.FENIX_MEDIA_ACCESS\\s*=\\s*(\\{[\\s\\S]*?\\});")
    private val PLAYLIST_PATTERN = Pattern.compile("window\\.FENIX_PLAYLIST\\s*=\\s*(\\{[\\s\\S]*?\\});")
    private val SAFE_PATH_SEGMENT = Regex("^[a-zA-Z0-9._-]+$")

    private fun JsonObject.strOrNull(key: String): String? =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else null

    private fun JsonObject.intOrNull(key: String): Int? =
        try {
            if (has(key) && get(key).isJsonPrimitive) get(key).asInt else null
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.boolOrNull(key: String): Boolean? =
        try {
            if (has(key) && get(key).isJsonPrimitive) get(key).asBoolean else null
        } catch (_: Exception) {
            null
        }

    fun isFenixUrl(url: String): Boolean {
        val lower = url.lowercase()
        val host = try { URI(ExtractorUtils.ensureHttpsScheme(lower)).host ?: "" } catch (_: Exception) { "" }
        return if (host.isNotEmpty()) {
            host.contains("fenixplay") || host.contains("fenix") || host.contains("fnx.")
        } else {
            lower.contains("fenixplay") || lower.contains("fnx.")
        }
    }

    fun extractStreams(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        val url = iframeUrl.trim()
        val streams = mutableListOf<StreamQuality>()

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Referer", referer ?: "https://anitube.in.ua/")
                .build()

            val html = ExtractorUtils.executeForString(client, req) ?: return emptyList()

            // 1. Отримуємо токен доступу до медіа-сервера
            var mediaToken: String? = null
            val mAccess = MEDIA_ACCESS_PATTERN.matcher(html)
            if (mAccess.find()) {
                try {
                    val accessJson = JsonParser.parseString(mAccess.group(1)).asJsonObject
                    mediaToken = accessJson.strOrNull("token")
                } catch (ignored: Exception) {}
            }

            // 2. Отримуємо плейлист FENIX
            val mPlaylist = PLAYLIST_PATTERN.matcher(html)
            if (!mPlaylist.find()) return emptyList()

            val playlistJson = JsonParser.parseString(mPlaylist.group(1)).asJsonObject
            val slug = playlistJson.strOrNull("slug")?.takeIf { SAFE_PATH_SEGMENT.matches(it) } ?: return emptyList()
            val playbackToken = playlistJson.get("playback")
                ?.takeIf { it.isJsonObject }?.asJsonObject?.strOrNull("token") ?: ""

            // 3. Визначаємо номер серії, редакцію (tv/mini) та бажану озвучку (l)
            var epNum = 1
            var edition = playlistJson.strOrNull("default_edition")
                ?.takeIf { SAFE_PATH_SEGMENT.matches(it) } ?: "tv"
            var targetLabel: String? = null

            try {
                val uri = URI(url)
                if (uri.path.contains("/vod/")) {
                    val rawPart = uri.path.substringAfter("/vod/").substringBefore(".").substringBefore("?")
                    val decodedStr = ExtractorUtils.decodeBase64Utf8(rawPart)
                    if (decodedStr.isNotEmpty()) {
                        val vodJson = JsonParser.parseString(decodedStr).asJsonObject
                        vodJson.intOrNull("e")?.let { epNum = it }
                        vodJson.strOrNull("v")?.takeIf { SAFE_PATH_SEGMENT.matches(it) }?.let { edition = it }
                        vodJson.strOrNull("l")?.let { targetLabel = it }
                    }
                } else if (uri.query != null) {
                    val params = uri.query.split("&")
                    for (p in params) {
                        if (p.startsWith("ep=")) {
                            epNum = p.substringAfter("=").toIntOrNull() ?: epNum
                        } else if (p.startsWith("edition=")) {
                            val candidate = p.substringAfter("=")
                            if (SAFE_PATH_SEGMENT.matches(candidate)) {
                                edition = candidate
                            }
                        } else if (p.startsWith("voice=") || p.startsWith("studio=") || p.startsWith("l=")) {
                            targetLabel = p.substringAfter("=")
                        }
                    }
                }
            } catch (_: Exception) {}

            // 4. Запитуємо детальну інформацію про серію через Fenix Playback API
            val encSlug = URLEncoder.encode(slug, "UTF-8")
            val encEdition = URLEncoder.encode(edition, "UTF-8")
            val apiUrl = "https://fenixplay.xyz/api/playback/$encSlug/episodes/$encEdition/$epNum"
            val apiReq = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .header("Referer", url)
                .header("X-Fenix-Playback", playbackToken)
                .header("Accept", "application/json")
                .build()

            val apiBody = ExtractorUtils.executeForString(client, apiReq) ?: return emptyList()
            val apiObj = JsonParser.parseString(apiBody).asJsonObject
            val epObj = apiObj.get("episode")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()

            // 5. Вибір потрібного треку (озвучки) з tracks
            var selectedTrack: JsonObject? = null
            val desiredLabel = targetLabel
            if (epObj.has("tracks") && epObj.get("tracks").isJsonArray) {
                val tracks = epObj.getAsJsonArray("tracks")
                if (!desiredLabel.isNullOrEmpty()) {
                    for (tElem in tracks) {
                        if (!tElem.isJsonObject) continue
                        val tObj = tElem.asJsonObject
                        val tLabel = tObj.strOrNull("label") ?: ""
                        if (tLabel.equals(desiredLabel, ignoreCase = true) ||
                            tLabel.contains(desiredLabel, ignoreCase = true) ||
                            desiredLabel.contains(tLabel, ignoreCase = true)
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
                        if (tObj.boolOrNull("is_default") == true) {
                            selectedTrack = tObj
                            break
                        }
                    }
                    if (selectedTrack == null && tracks.size() > 0 && tracks.get(0).isJsonObject) {
                        selectedTrack = tracks.get(0).asJsonObject
                    }
                }
            }

            val rawMaster = selectedTrack?.strOrNull("hls_url") ?: epObj.strOrNull("hls_master_url")
            val rawCastAvc = selectedTrack?.strOrNull("cast_avc_url") ?: epObj.strOrNull("cast_avc_url")

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

        } catch (_: Exception) {
        }

        return streams
    }

    private fun authorizeUrl(url: String?, token: String?): String? {
        if (url.isNullOrEmpty()) return null
        if (token.isNullOrEmpty() || url.contains("token=")) return url
        val sep = if (url.contains("?")) "&" else "?"
        return "$url${sep}token=$token"
    }

    fun parseFenixPage(client: OkHttpClient, pageUrl: String): MediaInfo? {
        return try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", ExtractorUtils.DEFAULT_UA)
                .build()

            val html = ExtractorUtils.executeForString(client, req) ?: return null
            val mPlaylist = PLAYLIST_PATTERN.matcher(html)
            if (!mPlaylist.find()) return null

            val playlistJson = JsonParser.parseString(mPlaylist.group(1)).asJsonObject
            val titleUa = playlistJson.strOrNull("title_ua") ?: "Fenix Аніме"
            val titleOrig = playlistJson.strOrNull("title_orig")
            val posterUrl = playlistJson.strOrNull("poster_url")
            val slug = playlistJson.strOrNull("slug")?.takeIf { SAFE_PATH_SEGMENT.matches(it) } ?: "anime"

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

                val epNum = epObj.intOrNull("number") ?: (i + 1)
                val epTitle = epObj.strOrNull("title") ?: "Серія $epNum"
                val epEdition = epObj.strOrNull("edition")?.takeIf { SAFE_PATH_SEGMENT.matches(it) } ?: "tv"

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
