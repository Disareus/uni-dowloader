package ua.aniua.downloader.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.extractor.GenericPlayerResolver
import ua.aniua.downloader.model.DownloadState
import ua.aniua.downloader.model.DownloadTask
import ua.aniua.downloader.model.StreamQuality
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object HlsSegmentDownloader {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val KEY_PATTERN = Pattern.compile("METHOD=([^,\\s]+)")
    private val URI_PATTERN = Pattern.compile("URI=[\"']([^\"']+)[\"']")
    private val IV_PATTERN = Pattern.compile("IV=0x([0-9a-fA-F]+)")
    private val AUDIO_PATTERN = Pattern.compile("#EXT-X-MEDIA:TYPE=AUDIO.*?URI=[\"']([^\"']+)[\"']")

    fun interface DownloadProgressListener {
        fun onProgress(percent: Float, speed: String, eta: String, downloaded: Long, total: Long)
    }

    fun download(
        context: Context,
        client: OkHttpClient,
        task: DownloadTask,
        outputStream: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ) {
        task.state = DownloadState.DOWNLOADING

        // 1. Отримуємо прямий потік відео (m3u8 або mp4) через GenericPlayerResolver
        var streamUrl = task.streamUrl
        var referer = task.referer.ifEmpty { task.iframeUrl }

        if (streamUrl.isEmpty() || (!streamUrl.contains(".m3u8") && !streamUrl.contains(".mp4"))) {
            val resolved = GenericPlayerResolver.resolvePlayerStreams(client, task.iframeUrl, referer)
            if (resolved.isEmpty()) {
                throw IllegalStateException("Не вдалося отримати потік відео з плеєра: ${task.iframeUrl}")
            }
            val best = selectBestStream(resolved, task.quality)
            streamUrl = best.url
            referer = best.headers["Referer"] ?: task.iframeUrl
            task.streamUrl = streamUrl
        }

        if (streamUrl.contains(".m3u8")) {
            downloadHlsStream(context, client, streamUrl, referer, task.quality, outputStream, listener, isCancelled)
        } else {
            downloadDirectFile(client, streamUrl, referer, outputStream, listener, isCancelled)
        }

        if (isCancelled?.get() == true) {
            task.state = DownloadState.CANCELLED
        } else {
            task.state = DownloadState.COMPLETED
            task.progress = 100f
        }
    }

    private fun selectBestStream(streams: List<StreamQuality>, desiredQuality: String): StreamQuality {
        val targetHeight = desiredQuality.filter { it.isDigit() }.toIntOrNull() ?: 720
        return streams.minByOrNull { Math.abs((it.height.takeIf { h -> h > 0 } ?: 720) - targetHeight) }
            ?: streams[0]
    }

    private fun downloadDirectFile(
        client: OkHttpClient,
        url: String,
        referer: String?,
        out: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
        if (!referer.isNullOrEmpty()) builder.header("Referer", referer)

        val response = client.newCall(builder.build()).execute()
        if (!response.isSuccessful || response.body == null) {
            throw IllegalStateException("HTTP помилка ${response.code} при завантаженні прямого файлу")
        }

        val totalBytes = response.body?.contentLength() ?: 0L
        var downloaded = 0L
        val buffer = ByteArray(64 * 1024)
        var bytesRead: Int

        val startTime = System.currentTimeMillis()
        var lastUpdate = 0L

        response.body?.byteStream()?.use { input ->
            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled?.get() == true) break

                out.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdate > 300) {
                    lastUpdate = now
                    val pct = if (totalBytes > 0) (downloaded * 100f / totalBytes) else 0f
                    val elapsedSec = Math.max((now - startTime) / 1000.0, 0.1)
                    val speedBytesPerSec = downloaded / elapsedSec
                    val speedStr = formatSpeed(speedBytesPerSec)
                    val etaStr = if (totalBytes > 0) formatEta(((totalBytes - downloaded) / Math.max(speedBytesPerSec, 1.0)).toLong()) else ""

                    listener?.onProgress(pct, speedStr, etaStr, downloaded, totalBytes)
                }
            }
            out.flush()
        }
    }

    private fun downloadHlsStream(
        context: Context,
        client: OkHttpClient,
        m3u8Url: String,
        referer: String?,
        desiredQuality: String,
        out: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ) {
        val cleanM3u8Url = m3u8Url.substringBefore("#")
        val baseUri = URI(cleanM3u8Url)
        var currentPlaylistUrl = cleanM3u8Url
        var audioPlaylistUrl: String? = null

        // 1. Завантажуємо первинний плейлист
        val req = Request.Builder()
            .url(currentPlaylistUrl)
            .header("User-Agent", BROWSER_UA)
        if (!referer.isNullOrEmpty()) req.header("Referer", referer)
        if (cleanM3u8Url.contains("fenix") || referer?.contains("fenix") == true) {
            req.header("Origin", "https://fenixplay.xyz")
        } else if (cleanM3u8Url.contains("moon") || referer?.contains("moon") == true) {
            req.header("Origin", "https://moonanime.net")
        }

        val resp = client.newCall(req.build()).execute()
        if (!resp.isSuccessful || resp.body == null) {
            throw IllegalStateException("Помилка завантаження HLS m3u8 (${resp.code})")
        }

        val content = resp.body?.string() ?: throw IllegalStateException("Порожній m3u8 плейлист")

        // 2. Якщо це Master Playlist, обираємо найкращий варіант відео та аудіо-потік
        if (content.contains("#EXT-X-STREAM-INF")) {
            val audioMatcher = AUDIO_PATTERN.matcher(content)
            if (audioMatcher.find()) {
                val rawAudio = audioMatcher.group(1) ?: ""
                audioPlaylistUrl = if (rawAudio.startsWith("http")) rawAudio else baseUri.resolve(rawAudio).toString()
            }

            val qualityHint = if (m3u8Url.contains("#")) m3u8Url.substringAfter("#") else desiredQuality
            val targetHeight = qualityHint.filter { it.isDigit() }.toIntOrNull() ?: 1080

            val lines = content.lines()
            var selectedSubUrl: String? = null
            var bestDiff = Int.MAX_VALUE
            var currentHeight = 0
            val resPattern = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)")

            for (line in lines) {
                val l = line.trim()
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    currentHeight = 0
                    val rm = resPattern.matcher(l)
                    if (rm.find()) {
                        currentHeight = rm.group(2)?.toIntOrNull() ?: 0
                    }
                } else if (!l.startsWith("#") && l.isNotEmpty()) {
                    val candidateUrl = if (l.startsWith("http://") || l.startsWith("https://")) l else baseUri.resolve(l).toString()
                    val diff = if (currentHeight > 0) Math.abs(currentHeight - targetHeight) else (bestDiff - 1)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        selectedSubUrl = candidateUrl
                    }
                }
            }
            if (selectedSubUrl != null) {
                currentPlaylistUrl = selectedSubUrl
            }
        }

        // 3. Завантаження сегментів
        if (audioPlaylistUrl != null) {
            // Роздільні потоки відео і аудіо (наприклад, Fenix fMP4)
            val tempVideoFile = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
            val tempAudioFile = File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.mp4")

            try {
                FileOutputStream(tempVideoFile).use { videoOut ->
                    downloadPlaylistSegments(client, currentPlaylistUrl, referer, videoOut, 0.70f, 0f, listener, isCancelled)
                }

                if (isCancelled?.get() == true) return

                FileOutputStream(tempAudioFile).use { audioOut ->
                    downloadPlaylistSegments(client, audioPlaylistUrl, referer, audioOut, 0.25f, 70f, listener, isCancelled)
                }

                if (isCancelled?.get() == true) return

                listener?.onProgress(95f, "", "Об'єднання доріжок...", 0L, 0L)
                muxVideoAndAudio(tempVideoFile, tempAudioFile, out)
                listener?.onProgress(100f, "", "Завершено", 0L, 0L)
            } finally {
                tempVideoFile.delete()
                tempAudioFile.delete()
            }
        } else {
            // Стандартний сумісний HLS потік (MPEG-TS або єдиний fMP4)
            downloadPlaylistSegments(client, currentPlaylistUrl, referer, out, 1.0f, 0f, listener, isCancelled)
        }
    }

    private fun downloadPlaylistSegments(
        client: OkHttpClient,
        playlistUrl: String,
        referer: String?,
        out: OutputStream,
        progressWeight: Float,
        baseProgress: Float,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ): Long {
        val mediaReq = Request.Builder()
            .url(playlistUrl)
            .header("User-Agent", BROWSER_UA)
        if (!referer.isNullOrEmpty()) mediaReq.header("Referer", referer)
        if (playlistUrl.contains("fenix") || referer?.contains("fenix") == true) {
            mediaReq.header("Origin", "https://fenixplay.xyz")
        } else if (playlistUrl.contains("moon") || referer?.contains("moon") == true) {
            mediaReq.header("Origin", "https://moonanime.net")
        }

        val mediaResp = client.newCall(mediaReq.build()).execute()
        if (!mediaResp.isSuccessful || mediaResp.body == null) {
            throw IllegalStateException("Помилка завантаження медіа-плейлиста HLS")
        }

        val mediaContent = mediaResp.body?.string() ?: ""
        val mediaBaseUri = URI(playlistUrl)

        val segmentUrls = mutableListOf<String>()
        var initSegmentUrl: String? = null
        var aesKeyUrl: String? = null
        var aesIvHex: String? = null
        var isAes128 = false

        BufferedReader(InputStreamReader(mediaContent.byteInputStream())).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("#EXT-X-MAP")) {
                    val mUri = URI_PATTERN.matcher(l)
                    if (mUri.find()) {
                        val rawMapUri = mUri.group(1) ?: ""
                        initSegmentUrl = if (rawMapUri.startsWith("http")) rawMapUri else mediaBaseUri.resolve(rawMapUri).toString()
                    }
                } else if (l.startsWith("#EXT-X-KEY")) {
                    val mMethod = KEY_PATTERN.matcher(l)
                    if (mMethod.find() && mMethod.group(1).equals("AES-128", ignoreCase = true)) {
                        isAes128 = true
                        val mUri = URI_PATTERN.matcher(l)
                        if (mUri.find()) {
                            val rawKeyUri = mUri.group(1) ?: ""
                            aesKeyUrl = if (rawKeyUri.startsWith("http")) rawKeyUri else mediaBaseUri.resolve(rawKeyUri).toString()
                        }
                        val mIv = IV_PATTERN.matcher(l)
                        if (mIv.find()) {
                            aesIvHex = mIv.group(1)
                        }
                    }
                } else if (!l.startsWith("#") && l.isNotEmpty()) {
                    val segUrl = if (l.startsWith("http://") || l.startsWith("https://")) l else mediaBaseUri.resolve(l).toString()
                    segmentUrls.add(segUrl)
                }
            }
        }

        var totalDownloaded = 0L

        // Завантаження fMP4 ініціалізаційного сегмента (#EXT-X-MAP)
        val targetInitUrl = initSegmentUrl
        if (!targetInitUrl.isNullOrEmpty()) {
            val initReq = Request.Builder()
                .url(targetInitUrl)
                .header("User-Agent", BROWSER_UA)
            if (!referer.isNullOrEmpty()) initReq.header("Referer", referer)

            try {
                client.newCall(initReq.build()).execute().use { initResp ->
                    if (initResp.isSuccessful && initResp.body != null) {
                        val initBytes = initResp.body?.bytes()
                        if (initBytes != null && initBytes.isNotEmpty()) {
                            out.write(initBytes)
                            out.flush()
                            totalDownloaded += initBytes.size
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        val totalSegments = segmentUrls.size
        if (totalSegments == 0) {
            throw IllegalStateException("Не знайдено відео-сегментів у HLS плейлисті")
        }

        // Завантаження AES-128 ключа
        var aesKeyBytes: ByteArray? = null
        val targetKeyUrl = aesKeyUrl
        if (isAes128 && !targetKeyUrl.isNullOrEmpty()) {
            val keyReq = Request.Builder()
                .url(targetKeyUrl)
                .header("User-Agent", BROWSER_UA)
            if (!referer.isNullOrEmpty()) keyReq.header("Referer", referer)

            try {
                client.newCall(keyReq.build()).execute().use { keyResp ->
                    if (keyResp.isSuccessful && keyResp.body != null) {
                        aesKeyBytes = keyResp.body?.bytes()
                    }
                }
            } catch (ignored: Exception) {}
        }

        val keyBytes = aesKeyBytes
        val startTime = System.currentTimeMillis()

        for (i in 0 until totalSegments) {
            if (isCancelled?.get() == true) break

            val segUrl = segmentUrls[i]
            val segReq = Request.Builder()
                .url(segUrl)
                .header("User-Agent", BROWSER_UA)
            if (!referer.isNullOrEmpty()) segReq.header("Referer", referer)
            if (segUrl.contains("fenix") || referer?.contains("fenix") == true) {
                segReq.header("Origin", "https://fenixplay.xyz")
            } else if (segUrl.contains("moon") || referer?.contains("moon") == true) {
                segReq.header("Origin", "https://moonanime.net")
            }

            try {
                client.newCall(segReq.build()).execute().use { segResp ->
                    if (segResp.isSuccessful && segResp.body != null) {
                        val bodyBytes = segResp.body?.bytes() ?: ByteArray(0)
                        val finalBytes = if (isAes128 && keyBytes != null) {
                            decryptAes128(bodyBytes, keyBytes, aesIvHex, i)
                        } else {
                            bodyBytes
                        }
                        out.write(finalBytes)
                        totalDownloaded += finalBytes.size
                    }
                }
            } catch (ignored: Exception) {}

            val localPct = ((i + 1) * 100f) / totalSegments
            val currentPercent = baseProgress + (localPct * progressWeight)

            val now = System.currentTimeMillis()
            val elapsedSec = Math.max((now - startTime) / 1000.0, 0.1)
            val speedBytes = totalDownloaded / elapsedSec
            val speedStr = formatSpeed(speedBytes)
            val remainingSegs = totalSegments - (i + 1)
            val segPerSec = (i + 1) / elapsedSec
            val etaStr = formatEta((remainingSegs / Math.max(segPerSec, 0.1)).toLong())

            listener?.onProgress(currentPercent, speedStr, etaStr, totalDownloaded, 0L)
        }

        out.flush()
        return totalDownloaded
    }

    private fun muxVideoAndAudio(videoFile: File, audioFile: File, outStream: OutputStream) {
        val tempMuxFile = File.createTempFile("muxed_", ".mp4", videoFile.parentFile)
        var muxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null

        try {
            muxer = MediaMuxer(tempMuxFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            var videoTrackIdx = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIdx = muxer.addTrack(format)
                    videoExtractor.selectTrack(i)
                    break
                }
            }

            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
            var audioTrackIdx = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = muxer.addTrack(format)
                    audioExtractor.selectTrack(i)
                    break
                }
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            if (videoTrackIdx != -1) {
                while (true) {
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                    bufferInfo.flags = videoExtractor.sampleFlags
                    muxer.writeSampleData(videoTrackIdx, buffer, bufferInfo)
                    videoExtractor.advance()
                }
            }

            if (audioTrackIdx != -1) {
                while (true) {
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    bufferInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(audioTrackIdx, buffer, bufferInfo)
                    audioExtractor.advance()
                }
            }

            muxer.stop()
            tempMuxFile.inputStream().use { it.copyTo(outStream) }
        } catch (e: Exception) {
            // У разі помилки муксування копіюємо оригінальний відеофайл
            videoFile.inputStream().use { it.copyTo(outStream) }
        } finally {
            try { muxer?.release() } catch (ignored: Exception) {}
            try { videoExtractor?.release() } catch (ignored: Exception) {}
            try { audioExtractor?.release() } catch (ignored: Exception) {}
            tempMuxFile.delete()
        }
    }

    private fun decryptAes128(encrypted: ByteArray, key: ByteArray, ivHex: String?, seqNum: Int): ByteArray {
        return try {
            val iv = if (!ivHex.isNullOrEmpty()) {
                parseHexBinary(ivHex)
            } else {
                ByteBuffer.allocate(16).putLong(0L).putLong(seqNum.toLong()).array()
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            encrypted
        }
    }

    private fun parseHexBinary(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(16)
        var i = 0
        while (i < len && i / 2 < 16) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return if (bytesPerSec > 1024 * 1024) {
            String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024))
        } else {
            String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024)
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return "0с"
        if (seconds < 60) return "${seconds}с"
        return "${seconds / 60}хв ${seconds % 60}с"
    }
}
