package ua.aniua.downloader.extractor

import android.net.Uri
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.model.StreamQuality
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

object MoonAnimeExtractor {

    fun extractStreams(client: OkHttpClient, iframeUrl: String, referer: String?): List<StreamQuality> {
        return try {
            val request = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", referer ?: "https://anitube.in.ua/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) return emptyList()

            val html = response.body?.string() ?: ""

            // 1. Двоступеневе XOR декодування MoonAnime
            val outerJs = decodeMoonanimeOuterJs(html)
            if (outerJs.isNotEmpty()) {
                val decryptedFile = decodeMoonanimeInnerFile(outerJs)
                if (!decryptedFile.isNullOrEmpty()) {
                    val qualities = PlayerJsDecoder.parsePlayerJsQualities(decryptedFile, iframeUrl)
                    if (qualities.isNotEmpty()) return qualities
                }
            }

            // 2. Звичайний file: "..."
            val filePattern = Pattern.compile("file\\s*:\\s*[\"']([^\"']+)[\"']")
            val fm = filePattern.matcher(html)
            if (fm.find()) {
                val fStr = fm.group(1)?.trim() ?: ""
                val qualities = PlayerJsDecoder.parsePlayerJsQualities(fStr, iframeUrl)
                if (qualities.isNotEmpty()) return qualities
            }

            // 3. Прямий m3u8
            val m3u8Pattern = Pattern.compile("(https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)")
            val mm = m3u8Pattern.matcher(html)
            if (mm.find()) {
                val m3u8Url = mm.group(1) ?: ""
                return listOf(StreamQuality("Auto (HLS)", 720, m3u8Url, true))
            }

            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeMoonanimeOuterJs(html: String): String {
        val pattern = Pattern.compile("atob\\([\"']([A-Za-z0-9+/=]{100,})[\"']\\)")
        val m = pattern.matcher(html)
        if (!m.find()) return ""
        return try {
            val b = Base64.decode(m.group(1), Base64.DEFAULT)
            if (b.size <= 33) return ""
            var state = b[0].toInt() and 0xFF
            val key = b.sliceArray(1..32)
            val out = ByteArray(b.size - 33)
            for (i in out.indices) {
                val k = key[i % 32].toInt() and 0xFF
                val bi = b[i + 33].toInt() and 0xFF
                out[i] = (bi xor k xor state).toByte()
                state = (bi + k) and 0xFF
            }
            String(out, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun decodeMoonanimeInnerFile(decodedJs: String): String? {
        val keyPattern = Pattern.compile("function\\s+_0xd\\([^)]*\\)\\s*\\{\\s*var\\s+k\\s*=\\s*[\"']([^\"']+)[\"']")
        val filePattern = Pattern.compile("file\\s*:\\s*_0xd\\([\"']([^\"']+)[\"']\\)")

        val km = keyPattern.matcher(decodedJs)
        val fm = filePattern.matcher(decodedJs)
        if (!km.find() || !fm.find()) return null

        val key = km.group(1) ?: return null
        val encodedFile = fm.group(1) ?: return null

        return try {
            val raw = Base64.decode(encodedFile, Base64.DEFAULT)
            val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArray(raw.size)
            for (i in raw.indices) {
                out[i] = (raw[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            val unquoted = String(out, StandardCharsets.UTF_8)
            Uri.decode(unquoted)
        } catch (e: Exception) {
            null
        }
    }
}
