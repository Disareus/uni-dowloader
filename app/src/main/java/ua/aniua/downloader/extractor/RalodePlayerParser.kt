package ua.aniua.downloader.extractor

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.EpisodeItem
import ua.aniua.downloader.model.PlayerSource
import java.util.regex.Pattern

object RalodePlayerParser {

    private val IFRAME_SRC_PATTERN = Pattern.compile("src\\s*=\\\\?[\"']([^\\\\\"']+)")
    private val NUMBER_PATTERN = Pattern.compile("(\\d+)")

    fun parseRalodeScript(html: String, baseOrigin: String): List<DubStudio> {
        val initPos = html.indexOf("RalodePlayer.init")
        if (initPos == -1) return emptyList()

        val sub = html.substring(initPos + "RalodePlayer.init".length).trim()
        val body = extractParenthesesBody(sub) ?: return emptyList()

        val jsonArgs = splitTopLevelJsonArgs(body)
        if (jsonArgs.size < 2) return emptyList()

        return try {
            val catElement = JsonParser.parseString(jsonArgs[0])
            val playElement = JsonParser.parseString(jsonArgs[1])

            if (!playElement.isJsonArray) return emptyList()

            val rawCategories = if (catElement.isJsonArray) catElement.asJsonArray else JsonArray()
            val rawPlaylists = playElement.asJsonArray

            val grouped = linkedMapOf<String, LinkedHashMap<String, PlayerSource>>()

            for (idx in 0 until rawPlaylists.size()) {
                var catLabel = "Озвучка ${idx + 1}"
                if (idx < rawCategories.size()) {
                    val catItem = rawCategories.get(idx)
                    if (catItem.isJsonPrimitive) {
                        catLabel = catItem.asString.trim()
                    } else if (catItem.isJsonObject) {
                        val obj = catItem.asJsonObject
                        if (obj.has("name")) catLabel = obj.get("name").asString
                        else if (obj.has("title")) catLabel = obj.get("title").asString
                    }
                }

                val (studioName, playerHint) = splitStudioAndPlayer(catLabel)

                val epListElem = rawPlaylists.get(idx)
                if (!epListElem.isJsonArray) continue
                val epList = epListElem.asJsonArray

                for (eIdx in 0 until epList.size()) {
                    val epObjElem = epList.get(eIdx)
                    if (!epObjElem.isJsonObject) continue
                    val epObj = epObjElem.asJsonObject

                    val epName = if (epObj.has("name")) epObj.get("name").asString else "Серія ${eIdx + 1}"
                    var epCode = ""
                    if (epObj.has("code")) epCode = epObj.get("code").asString
                    else if (epObj.has("file")) epCode = epObj.get("file").asString
                    else if (epObj.has("url")) epCode = epObj.get("url").asString

                    val iframeUrl = extractUrlFromCode(epCode, baseOrigin) ?: continue

                    var pId = "player"
                    var pName = "Плеєр"
                    if (iframeUrl.contains("ashdi")) {
                        pId = "ashdi"; pName = "Ashdi"
                    } else if (iframeUrl.contains("tortuga")) {
                        pId = "tortuga"; pName = "Tortuga"
                    } else if (iframeUrl.contains("moonanime")) {
                        pId = "moonanime"; pName = "MoonAnime"
                    }

                    if (!playerHint.isNullOrEmpty()) {
                        pId = pId + "_" + playerHint.lowercase().replace(Regex("[^a-z0-9]+"), "_")
                        pName = "$playerHint ($pName)"
                    }

                    val studioBucket = grouped.getOrPut(studioName) { LinkedHashMap() }
                    val ps = studioBucket.getOrPut(pId) { PlayerSource(pId, pName) }

                    val numM = NUMBER_PATTERN.matcher(epName)
                    val epNum = if (numM.find()) numM.group(1) ?: "${eIdx + 1}" else "${eIdx + 1}"

                    val ep = EpisodeItem(
                        id = "ralode_${idx}_$eIdx",
                        number = epNum,
                        title = epName,
                        season = "1",
                        iframeUrl = iframeUrl
                    )
                    ps.episodes.add(ep)
                }
            }

            val result = mutableListOf<DubStudio>()
            var sIdx = 0
            for ((name, players) in grouped) {
                val studio = DubStudio("studio_ralode_$sIdx", name)
                studio.players.addAll(players.values)
                result.add(studio)
                sIdx++
            }
            result
        } catch (ignored: Exception) {
            emptyList()
        }
    }

    private fun splitStudioAndPlayer(catLabel: String): Pair<String, String?> {
        val parts = catLabel.split(Regex("[/|•]"))
        if (parts.size >= 2) {
            val studioParts = mutableListOf<String>()
            var playerPart: String? = null
            for (p in parts) {
                val trimmed = p.trim()
                val low = trimmed.lowercase()
                if (low.contains("плеєр") || low.contains("player") || low.contains("ashdi") ||
                    low.contains("tortuga") || low.contains("moon") || low.contains("sibnet")
                ) {
                    playerPart = trimmed
                } else if (trimmed.isNotEmpty()) {
                    studioParts.add(trimmed)
                }
            }
            if (studioParts.isNotEmpty()) {
                return Pair(studioParts.joinToString(" / "), playerPart)
            }
        }
        return Pair(catLabel.trim().ifEmpty { "Українська озвучка" }, null)
    }

    private fun extractUrlFromCode(code: String?, baseOrigin: String): String? {
        if (code == null) return null
        val clean = code.replace("\\\"", "\"").replace("\\/", "/").trim()
        if (clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("//")) {
            return DlePlaylistParser.normalizeUrl(clean, baseOrigin)
        }
        val m = IFRAME_SRC_PATTERN.matcher(clean)
        if (m.find()) {
            val raw = m.group(1) ?: return null
            return DlePlaylistParser.normalizeUrl(raw, baseOrigin)
        }
        return null
    }

    private fun extractParenthesesBody(text: String?): String? {
        if (text.isNullOrEmpty() || text[0] != '(') return null
        var depth = 0
        var inStr = false
        var strChar = '\u0000'
        var escape = false

        for (i in text.indices) {
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
            if (ch == '(') depth++
            else if (ch == ')') {
                depth--
                if (depth == 0) return text.substring(1, i)
            }
        }
        return null
    }

    private fun splitTopLevelJsonArgs(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var inStr = false
        var strChar = '\u0000'
        var escape = false

        for (i in text.indices) {
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
            if (ch == '[' || ch == '{') depth++
            else if (ch == ']' || ch == '}') depth--
            else if (ch == ',' && depth == 0) {
                parts.add(text.substring(start, i).trim())
                start = i + 1
            }
        }
        val last = text.substring(start).trim()
        if (last.isNotEmpty()) parts.add(last)
        return parts
    }
}
