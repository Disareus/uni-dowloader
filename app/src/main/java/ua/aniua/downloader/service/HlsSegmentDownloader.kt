package ua.aniua.downloader.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.aniua.downloader.extractor.ExtractorUtils
import ua.aniua.downloader.extractor.GenericPlayerResolver
import ua.aniua.downloader.model.DownloadState
import ua.aniua.downloader.model.DownloadTask
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
import kotlin.math.abs
import kotlin.math.max

object HlsSegmentDownloader {

    private const val PROGRESS_THROTTLE_MS = 300L
    private const val SEGMENT_MAX_RETRIES = 3

    private val KEY_PATTERN = Pattern.compile("METHOD=([^,\\s]+)")
    private val URI_PATTERN = Pattern.compile("URI=[\"']([^\"']+)[\"']")
    private val IV_PATTERN = Pattern.compile("IV=0x([0-9a-fA-F]+)")
    private val AUDIO_PATTERN = Pattern.compile("#EXT-X-MEDIA:TYPE=AUDIO.*?URI=[\"']([^\"']+)[\"']")
    private val RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)")
    private val MEDIA_SEQ_PATTERN = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)")

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

        fun resolveFreshStream() {
            val resolved = GenericPlayerResolver.resolvePlayerStreams(client, task.iframeUrl, referer)
            if (resolved.isEmpty()) {
                throw IllegalStateException("Не вдалося отримати потік відео з плеєра: ${task.iframeUrl}")
            }
            val best = ExtractorUtils.selectBestStream(resolved, task.quality)
            streamUrl = best.url
            referer = best.headers["Referer"] ?: task.iframeUrl
            task.streamUrl = streamUrl
            task.referer = referer
        }

        if (streamUrl.isEmpty() || !ExtractorUtils.isDirectMediaUrl(streamUrl)) {
            resolveFreshStream()
        }

        val canRetryFresh = task.iframeUrl.isNotEmpty() &&
            task.iframeUrl != streamUrl &&
            !ExtractorUtils.isDirectMediaUrl(task.iframeUrl)

        if (ExtractorUtils.isHlsUrl(streamUrl)) {
            try {
                downloadHlsStream(context, client, streamUrl, referer, task.quality, outputStream, listener, isCancelled)
            } catch (e: Exception) {
                val isInitialPlaylistFailure = e.message?.let {
                    it.contains("HLS m3u8") || it.contains("Порожній m3u8") || it.contains("медіа-плейлиста HLS")
                } == true
                if (isCancelled?.get() != true && canRetryFresh && isInitialPlaylistFailure) {
                    resolveFreshStream()
                    if (ExtractorUtils.isHlsUrl(streamUrl)) {
                        downloadHlsStream(context, client, streamUrl, referer, task.quality, outputStream, listener, isCancelled)
                    } else {
                        downloadDirectFile(client, streamUrl, referer, outputStream, listener, isCancelled)
                    }
                } else {
                    throw e
                }
            }
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

    private fun buildRequestWithHeaders(url: String, referer: String?): Request {
        val builder = Request.Builder().url(url)
        for ((k, v) in ExtractorUtils.buildStreamHeaders(url, referer)) {
            builder.header(k, v)
        }
        return builder.build()
    }

    private fun downloadDirectFile(
        client: OkHttpClient,
        url: String,
        referer: String?,
        out: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ) {
        val request = buildRequestWithHeaders(url, referer)

        client.newCall(request).execute().use { response ->
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
                    if (now - lastUpdate >= PROGRESS_THROTTLE_MS) {
                        lastUpdate = now
                        val pct = if (totalBytes > 0) (downloaded * 100f / totalBytes) else 0f
                        val elapsedSec = max((now - startTime) / 1000.0, 0.1)
                        val speedBytesPerSec = downloaded / elapsedSec
                        val speedStr = formatSpeed(speedBytesPerSec)
                        val etaStr = if (totalBytes > 0) formatEta(((totalBytes - downloaded) / max(speedBytesPerSec, 1.0)).toLong()) else ""

                        listener?.onProgress(pct, speedStr, etaStr, downloaded, totalBytes)
                    }
                }
                out.flush()
            }

            if (isCancelled?.get() != true && downloaded == 0L) {
                throw IllegalStateException("Отримано порожній відеофайл")
            }
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
        val req = buildRequestWithHeaders(currentPlaylistUrl, referer)
        val content = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful || resp.body == null) {
                throw IllegalStateException("Помилка завантаження HLS m3u8 (${resp.code})")
            }
            resp.body?.string() ?: throw IllegalStateException("Порожній m3u8 плейлист")
        }

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

            for (line in lines) {
                val l = line.trim()
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    currentHeight = 0
                    val rm = RESOLUTION_PATTERN.matcher(l)
                    if (rm.find()) {
                        currentHeight = rm.group(2)?.toIntOrNull() ?: 0
                    }
                } else if (!l.startsWith("#") && l.isNotEmpty()) {
                    val candidateUrl = if (l.startsWith("http://") || l.startsWith("https://")) l else baseUri.resolve(l).toString()
                    val diff = if (currentHeight > 0) abs(currentHeight - targetHeight) else (bestDiff - 1)
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
        val mediaReq = buildRequestWithHeaders(playlistUrl, referer)
        val mediaContent = client.newCall(mediaReq).execute().use { mediaResp ->
            if (!mediaResp.isSuccessful || mediaResp.body == null) {
                throw IllegalStateException("Помилка завантаження медіа-плейлиста HLS (${mediaResp.code})")
            }
            mediaResp.body?.string() ?: ""
        }
        val mediaBaseUri = URI(playlistUrl)

        val segmentUrls = mutableListOf<String>()
        var initSegmentUrl: String? = null
        var aesKeyUrl: String? = null
        var aesIvHex: String? = null
        var isAes128 = false
        var mediaSequence = 0L

        BufferedReader(InputStreamReader(mediaContent.byteInputStream())).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                    val seqM = MEDIA_SEQ_PATTERN.matcher(l)
                    if (seqM.find()) {
                        mediaSequence = seqM.group(1)?.toLongOrNull() ?: 0L
                    }
                } else if (l.startsWith("#EXT-X-MAP")) {
                    val mUri = URI_PATTERN.matcher(l)
                    if (mUri.find()) {
                        val rawMapUri = mUri.group(1) ?: ""
                        initSegmentUrl = if (rawMapUri.startsWith("http")) rawMapUri else mediaBaseUri.resolve(rawMapUri).toString()
                    }
                } else if (l.startsWith("#EXT-X-KEY")) {
                    val mMethod = KEY_PATTERN.matcher(l)
                    if (mMethod.find()) {
                        val method = mMethod.group(1)
                        if (method.equals("AES-128", ignoreCase = true)) {
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
                        } else if (method.equals("NONE", ignoreCase = true)) {
                            isAes128 = false
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
            val initReq = buildRequestWithHeaders(targetInitUrl, referer)
            try {
                client.newCall(initReq).execute().use { initResp ->
                    if (initResp.isSuccessful && initResp.body != null) {
                        val initBytes = initResp.body?.bytes()
                        if (initBytes != null && initBytes.isNotEmpty()) {
                            out.write(initBytes)
                            out.flush()
                            totalDownloaded += initBytes.size
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val totalSegments = segmentUrls.size
        if (totalSegments == 0) {
            throw IllegalStateException("Не знайдено відео-сегментів у HLS плейлисті")
        }

        // Завантаження AES-128 ключа
        var aesKeyBytes: ByteArray? = null
        val targetKeyUrl = aesKeyUrl
        if (isAes128 && !targetKeyUrl.isNullOrEmpty()) {
            val keyReq = buildRequestWithHeaders(targetKeyUrl, referer)
            client.newCall(keyReq).execute().use { keyResp ->
                if (keyResp.isSuccessful && keyResp.body != null) {
                    aesKeyBytes = keyResp.body?.bytes()
                }
            }
            if (aesKeyBytes == null || aesKeyBytes?.size != 16) {
                throw IllegalStateException("Не вдалося завантажити ключ шифрування AES-128")
            }
        }

        val keyBytes = aesKeyBytes
        val startTime = System.currentTimeMillis()
        var lastUpdate = 0L
        var failedSegments = 0

        for (i in 0 until totalSegments) {
            if (isCancelled?.get() == true) break

            val segUrl = segmentUrls[i]
            val segBytes = downloadSegmentWithRetry(client, segUrl, referer, isCancelled)
            if (segBytes != null && segBytes.isNotEmpty()) {
                val finalBytes = if (isAes128 && keyBytes != null) {
                    decryptAes128(segBytes, keyBytes, aesIvHex, mediaSequence + i)
                } else {
                    segBytes
                }
                out.write(finalBytes)
                totalDownloaded += finalBytes.size
            } else if (isCancelled?.get() != true) {
                failedSegments++
            }

            val now = System.currentTimeMillis()
            if (now - lastUpdate >= PROGRESS_THROTTLE_MS || i == totalSegments - 1) {
                lastUpdate = now
                val localPct = ((i + 1) * 100f) / totalSegments
                val currentPercent = baseProgress + (localPct * progressWeight)

                val elapsedSec = max((now - startTime) / 1000.0, 0.1)
                val speedBytes = totalDownloaded / elapsedSec
                val speedStr = formatSpeed(speedBytes)
                val remainingSegs = totalSegments - (i + 1)
                val segPerSec = (i + 1) / elapsedSec
                val etaStr = formatEta((remainingSegs / max(segPerSec, 0.1)).toLong())

                listener?.onProgress(currentPercent, speedStr, etaStr, totalDownloaded, 0L)
            }
        }

        out.flush()
        if (isCancelled?.get() != true && (totalDownloaded == 0L || failedSegments > totalSegments / 2)) {
            throw IllegalStateException("Не вдалося завантажити сегменти HLS ($failedSegments/$totalSegments помилок)")
        }
        return totalDownloaded
    }

    private fun downloadSegmentWithRetry(
        client: OkHttpClient,
        segUrl: String,
        referer: String?,
        isCancelled: AtomicBoolean?
    ): ByteArray? {
        val segReq = buildRequestWithHeaders(segUrl, referer)
        for (attempt in 1..SEGMENT_MAX_RETRIES) {
            if (isCancelled?.get() == true) return null
            try {
                client.newCall(segReq).execute().use { segResp ->
                    if (segResp.isSuccessful && segResp.body != null) {
                        val bytes = segResp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            return bytes
                        }
                    }
                }
            } catch (_: Exception) {}
            if (attempt < SEGMENT_MAX_RETRIES) {
                try {
                    Thread.sleep(250L * attempt)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
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

    private fun decryptAes128(encrypted: ByteArray, key: ByteArray, ivHex: String?, seqNum: Long): ByteArray {
        return try {
            val iv = if (!ivHex.isNullOrEmpty()) {
                parseHexBinary(ivHex)
            } else {
                ByteBuffer.allocate(16).putLong(0L).putLong(seqNum).array()
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            encrypted
        }
    }

    private fun parseHexBinary(hex: String): ByteArray {
        val paddedHex = (if (hex.length % 2 == 1) "0$hex" else hex).takeLast(32).padStart(32, '0')
        val data = ByteArray(16)
        for (i in 0 until 16) {
            val hi = Character.digit(paddedHex[i * 2], 16).coerceAtLeast(0)
            val lo = Character.digit(paddedHex[i * 2 + 1], 16).coerceAtLeast(0)
            data[i] = ((hi shl 4) or lo).toByte()
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
