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
import ua.aniua.downloader.extractor.YouTubeExtractor
import ua.aniua.downloader.model.DownloadState
import ua.aniua.downloader.model.DownloadTask
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object HlsSegmentDownloader {

    private const val PROGRESS_THROTTLE_MS = 300L
    private const val SEGMENT_MAX_RETRIES = 3
    private const val YT_RANGE_CHUNK_BYTES = 2L * 1024L * 1024L
    private const val YT_PARALLEL_WORKERS = 4

    private val CONTENT_RANGE_TOTAL_PATTERN = Pattern.compile("bytes\\s+\\d+-\\d+/(\\d+)", Pattern.CASE_INSENSITIVE)

    private val KEY_PATTERN = Pattern.compile("METHOD=([^,\\s]+)")
    private val URI_PATTERN = Pattern.compile("URI=[\"']([^\"']+)[\"']")
    private val IV_PATTERN = Pattern.compile("IV=0x([0-9a-fA-F]+)")
    private val AUDIO_PATTERN = Pattern.compile("#EXT-X-MEDIA:TYPE=AUDIO.*?URI=[\"']([^\"']+)[\"']")
    private val RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)")
    private val MEDIA_SEQ_PATTERN = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)")

    fun interface DownloadProgressListener {
        fun onProgress(percent: Float, speed: String, eta: String, downloaded: Long, total: Long)
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        @Volatile
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
    }

    private data class SegmentKeySpec(
        val isAes128: Boolean,
        val keyUrl: String?,
        val ivHex: String?
    )

    fun download(
        context: Context,
        client: OkHttpClient,
        task: DownloadTask,
        outputStream: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ) {
        task.state = DownloadState.DOWNLOADING
        val countedOut = CountingOutputStream(outputStream)

        // 1. Отримуємо прямий потік відео (m3u8 або mp4) через GenericPlayerResolver
        var streamUrl = task.streamUrl
        var audioUrl = task.audioUrl
        var referer = task.referer.ifEmpty { task.iframeUrl }
        var extraHeaders: Map<String, String> = synchronized(task.headers) { task.headers.toMap() }

        fun applyBestQuality(best: ua.aniua.downloader.model.StreamQuality) {
            streamUrl = best.url
            audioUrl = best.audioUrl
            referer = best.headers["Referer"] ?: task.iframeUrl
            extraHeaders = best.headers.toMap()
            task.streamUrl = streamUrl
            task.audioUrl = audioUrl
            task.referer = referer
            synchronized(task.headers) {
                task.headers.clear()
                task.headers.putAll(best.headers)
            }
        }

        fun resolveFreshStream() {
            val resolved = GenericPlayerResolver.resolvePlayerStreams(client, task.iframeUrl, referer)
            if (resolved.isEmpty()) {
                throw IllegalStateException("Не вдалося отримати потік відео з плеєра: ${task.iframeUrl}")
            }
            applyBestQuality(ExtractorUtils.selectBestStream(resolved, task.quality))
        }

        if (streamUrl.isEmpty() || !ExtractorUtils.isDirectMediaUrl(streamUrl)) {
            resolveFreshStream()
        }

        val canRetryFresh = task.iframeUrl.isNotEmpty() &&
            task.iframeUrl != streamUrl &&
            !ExtractorUtils.isDirectMediaUrl(task.iframeUrl)

        run {
            try {
                executeStreamDownload(
                    context, client, streamUrl, audioUrl, referer, extraHeaders,
                    task.quality, countedOut, listener, isCancelled
                )
            } catch (e: Exception) {
                if (isCancelled?.get() == true) throw e
                // Не можна дописувати новий потік у той самий OutputStream, якщо вже записано часткові дані
                if (countedOut.bytesWritten > 0L) throw e

                val isYouTube = YouTubeExtractor.isYouTubeUrl(task.iframeUrl) ||
                    streamUrl.contains("googlevideo.com", ignoreCase = true)
                val msg = e.message.orEmpty()
                val isYouTubeBlocked = isYouTube && (msg.contains("403") || msg.contains("410") || msg.contains("404"))
                val isInitialPlaylistFailure = msg.contains("HLS m3u8") ||
                    msg.contains("Порожній m3u8") ||
                    msg.contains("медіа-плейлиста HLS")

                if (isYouTubeBlocked) {
                    YouTubeExtractor.invalidateCache(task.iframeUrl)
                    val fallbacks = YouTubeExtractor.resolveFallbackStreams(client, task.iframeUrl)
                    val targetHeight = ExtractorUtils.extractQualityHeight(task.quality) ?: 720
                    val eligibleFallbacks = if (targetHeight > 360) {
                        fallbacks.filter { it.height > 360 || it.isHls }
                    } else {
                        fallbacks
                    }
                    val orderedFallbacks = eligibleFallbacks.sortedWith(
                        compareBy<ua.aniua.downloader.model.StreamQuality> { it.url == streamUrl && it.audioUrl == audioUrl }
                            .thenBy { abs((if (it.height > 0) it.height else 720) - targetHeight) }
                            .thenBy {
                                when {
                                    it.audioUrl != null && !it.isHls -> 0
                                    it.isHls -> 1
                                    else -> 2
                                }
                            }
                    )
                    var lastErr: Exception = e
                    for (fb in orderedFallbacks) {
                        if (fb.url == streamUrl && fb.audioUrl == audioUrl) continue
                        if (countedOut.bytesWritten > 0L) break
                        applyBestQuality(fb)
                        try {
                            executeStreamDownload(
                                context, client, streamUrl, audioUrl, referer, extraHeaders,
                                task.quality, countedOut, listener, isCancelled
                            )
                            return@run
                        } catch (retryErr: Exception) {
                            lastErr = retryErr
                        }
                    }
                    if (targetHeight > 360) {
                        throw IllegalStateException(
                            "Не вдалося завантажити у ${task.quality}: потік вищої якості недоступний (${lastErr.message ?: "403"})",
                            lastErr
                        )
                    }
                    throw lastErr
                } else if (canRetryFresh && isInitialPlaylistFailure) {
                    resolveFreshStream()
                    executeStreamDownload(
                        context, client, streamUrl, audioUrl, referer, extraHeaders,
                        task.quality, countedOut, listener, isCancelled
                    )
                } else {
                    throw e
                }
            }
        }

        if (isCancelled?.get() == true) {
            task.state = DownloadState.CANCELLED
        } else {
            task.state = DownloadState.COMPLETED
            task.progress = 100f
        }
    }

    private fun executeStreamDownload(
        context: Context,
        client: OkHttpClient,
        streamUrl: String,
        audioUrl: String?,
        referer: String?,
        extraHeaders: Map<String, String>,
        desiredQuality: String,
        outputStream: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ) {
        if (ExtractorUtils.isHlsUrl(streamUrl)) {
            downloadHlsStream(context, client, streamUrl, referer, extraHeaders, desiredQuality, outputStream, listener, isCancelled)
        } else if (!audioUrl.isNullOrEmpty()) {
            downloadMuxedDirectStreams(context, client, streamUrl, audioUrl, referer, extraHeaders, outputStream, listener, isCancelled)
        } else {
            downloadDirectFile(client, streamUrl, referer, extraHeaders, outputStream, listener, isCancelled)
        }
    }

    internal fun parseContentRangeTotal(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val m = CONTENT_RANGE_TOTAL_PATTERN.matcher(contentRange.trim())
        return if (m.find()) m.group(1)?.toLongOrNull()?.takeIf { it > 0L } else null
    }

    private fun buildRequestWithHeaders(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        rangeHeader: String? = null
    ): Request {
        val builder = Request.Builder().url(url)
        val merged = ExtractorUtils.buildStreamHeaders(url, referer)
        for ((k, v) in extraHeaders) {
            if (v.isNotEmpty() && !(k.equals("Referer", ignoreCase = true) && url.contains("googlevideo.com", ignoreCase = true))) {
                merged[k] = v
            }
        }
        for ((k, v) in merged) {
            builder.header(k, v)
        }
        if (!rangeHeader.isNullOrEmpty()) {
            builder.header("Range", rangeHeader)
        }
        return builder.build()
    }

    private fun downloadMuxedDirectStreams(
        context: Context,
        client: OkHttpClient,
        videoUrl: String,
        audioUrl: String,
        referer: String?,
        extraHeaders: Map<String, String>,
        out: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ) {
        val tempVideoFile = File.createTempFile("yt_video_", ".mp4", context.cacheDir)
        val tempAudioFile = File.createTempFile("yt_audio_", ".mp4", context.cacheDir)
        try {
            downloadDirectToFile(client, videoUrl, referer, extraHeaders, tempVideoFile, listener, isCancelled, progressWeight = 0.75f, baseProgress = 0f)
            if (isCancelled?.get() == true) return

            downloadDirectToFile(client, audioUrl, referer, extraHeaders, tempAudioFile, listener, isCancelled, progressWeight = 0.20f, baseProgress = 75f)
            if (isCancelled?.get() == true) return

            listener?.onProgress(95f, "", "Об'єднання відео та аудіо...", 0L, 0L)
            muxVideoAndAudio(tempVideoFile, tempAudioFile, out)
            listener?.onProgress(100f, "", "Завершено", 0L, 0L)
        } finally {
            tempVideoFile.delete()
            tempAudioFile.delete()
        }
    }

    /**
     * Завантажує файл у локальний File; для googlevideo.com використовує 2 МБ Range-чанки
     * у кілька потоків, щоб обійти обмеження швидкості одиночного GET на YouTube.
     */
    private fun downloadDirectToFile(
        client: OkHttpClient,
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>,
        targetFile: File,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?,
        progressWeight: Float,
        baseProgress: Float
    ) {
        if (!url.contains("googlevideo.com", ignoreCase = true)) {
            FileOutputStream(targetFile).use { fileOut ->
                downloadDirectFile(client, url, referer, extraHeaders, fileOut, listener, isCancelled, progressWeight, baseProgress)
            }
            return
        }

        val startTime = System.currentTimeMillis()
        val lastUpdate = AtomicLong(0L)
        val totalDownloaded = AtomicLong(0L)

        fun reportProgress(totalBytes: Long, force: Boolean = false) {
            val now = System.currentTimeMillis()
            val prev = lastUpdate.get()
            if (!force && now - prev < PROGRESS_THROTTLE_MS) return
            if (!force && !lastUpdate.compareAndSet(prev, now)) return
            val dl = totalDownloaded.get()
            val localPct = if (totalBytes > 0L) (dl * 100f / totalBytes).coerceIn(0f, 100f) else 0f
            val pct = baseProgress + (localPct * progressWeight)
            val elapsedSec = max((now - startTime) / 1000.0, 0.1)
            val speedBytesPerSec = dl / elapsedSec
            val speedStr = formatSpeed(speedBytesPerSec)
            val etaStr = if (totalBytes > 0L) formatEta(((totalBytes - dl).coerceAtLeast(0L) / max(speedBytesPerSec, 1.0)).toLong()) else ""
            listener?.onProgress(pct, speedStr, etaStr, dl, totalBytes)
        }

        val firstEnd = YT_RANGE_CHUNK_BYTES - 1L
        val probeRequest = buildRequestWithHeaders(url, referer, extraHeaders, "bytes=0-$firstEnd")
        var totalBytes = -1L

        RandomAccessFile(targetFile, "rw").use { raf ->
            val rafLock = Any()

            client.newCall(probeRequest).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    throw IllegalStateException("HTTP помилка ${response.code} при завантаженні прямого файлу")
                }
                val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
                if (contentType.startsWith("image/") || contentType.startsWith("text/")) {
                    throw IllegalStateException("Отримано не відео ($contentType) замість відеофайлу")
                }

                val contentTotal = if (response.code == 206) parseContentRangeTotal(response.header("Content-Range")) else null
                if (contentTotal == null) {
                    val streamTotal = response.body?.contentLength() ?: 0L
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    var pos = 0L
                    response.body?.byteStream()?.use { input ->
                        while (input.read(buf).also { n = it } != -1) {
                            if (isCancelled?.get() == true) break
                            raf.write(buf, 0, n)
                            pos += n
                            totalDownloaded.set(pos)
                            reportProgress(streamTotal)
                        }
                    }
                    if (isCancelled?.get() != true && pos == 0L) {
                        throw IllegalStateException("Отримано порожній відеофайл")
                    }
                    return
                }

                totalBytes = contentTotal
                raf.setLength(totalBytes)
                val buf = ByteArray(64 * 1024)
                var n: Int
                var pos = 0L
                response.body?.byteStream()?.use { input ->
                    while (input.read(buf).also { n = it } != -1) {
                        if (isCancelled?.get() == true) break
                        synchronized(rafLock) {
                            raf.seek(pos)
                            raf.write(buf, 0, n)
                        }
                        pos += n
                        totalDownloaded.addAndGet(n.toLong())
                        reportProgress(totalBytes)
                    }
                }
            }

            if (isCancelled?.get() == true) return
            val downloadedSoFar = totalDownloaded.get()
            if (downloadedSoFar >= totalBytes) {
                reportProgress(totalBytes, force = true)
                return
            }

            val chunkRanges = mutableListOf<Pair<Long, Long>>()
            var cursor = downloadedSoFar
            while (cursor < totalBytes) {
                val end = min(cursor + YT_RANGE_CHUNK_BYTES - 1L, totalBytes - 1L)
                chunkRanges.add(cursor to end)
                cursor = end + 1L
            }

            val pool = Executors.newFixedThreadPool(min(YT_PARALLEL_WORKERS, chunkRanges.size.coerceAtLeast(1)))
            val firstError = AtomicReference<Exception?>(null)
            val futures = mutableListOf<Future<*>>()

            try {
                for ((rangeStart, rangeEnd) in chunkRanges) {
                    futures.add(pool.submit {
                        if (isCancelled?.get() == true || firstError.get() != null) return@submit
                        var chunkErr: Exception? = null
                        for (attempt in 1..SEGMENT_MAX_RETRIES) {
                            if (isCancelled?.get() == true || firstError.get() != null) return@submit
                            var offsetInChunk = 0L
                            try {
                                val reqStart = rangeStart
                                val req = buildRequestWithHeaders(url, referer, extraHeaders, "bytes=$reqStart-$rangeEnd")
                                client.newCall(req).execute().use { resp ->
                                    if (!resp.isSuccessful || resp.body == null) {
                                        throw IllegalStateException("HTTP помилка ${resp.code} при завантаженні чанка")
                                    }
                                    val buf = ByteArray(64 * 1024)
                                    var n: Int
                                    resp.body?.byteStream()?.use { input ->
                                        while (input.read(buf).also { n = it } != -1) {
                                            if (isCancelled?.get() == true || firstError.get() != null) break
                                            val writeAt = reqStart + offsetInChunk
                                            synchronized(rafLock) {
                                                raf.seek(writeAt)
                                                raf.write(buf, 0, n)
                                            }
                                            offsetInChunk += n
                                            totalDownloaded.addAndGet(n.toLong())
                                            reportProgress(totalBytes)
                                        }
                                    }
                                }
                                val expected = rangeEnd - rangeStart + 1L
                                if (isCancelled?.get() != true && firstError.get() == null && offsetInChunk < expected) {
                                    throw IllegalStateException("Неповний чанк ($offsetInChunk/$expected)")
                                }
                                chunkErr = null
                                break
                            } catch (e: Exception) {
                                if (offsetInChunk > 0L) {
                                    totalDownloaded.addAndGet(-offsetInChunk)
                                }
                                chunkErr = e
                                if (attempt < SEGMENT_MAX_RETRIES && isCancelled?.get() != true && firstError.get() == null) {
                                    try {
                                        Thread.sleep(200L * attempt)
                                    } catch (_: InterruptedException) {
                                        return@submit
                                    }
                                }
                            }
                        }
                        if (chunkErr != null) {
                            firstError.compareAndSet(null, chunkErr)
                        }
                    })
                }
                for (f in futures) {
                    if (isCancelled?.get() == true) break
                    f.get()
                }
            } finally {
                pool.shutdownNow()
                try {
                    pool.awaitTermination(3, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            firstError.get()?.let { throw it }
            if (isCancelled?.get() != true && totalDownloaded.get() < totalBytes) {
                throw IllegalStateException("Неповне завантаження (${totalDownloaded.get()}/$totalBytes)")
            }
            reportProgress(totalBytes, force = true)
        }
    }

    private fun downloadDirectFile(
        client: OkHttpClient,
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>,
        out: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?,
        progressWeight: Float = 1.0f,
        baseProgress: Float = 0f
    ) {
        if (url.contains("googlevideo.com", ignoreCase = true)) {
            downloadDirectRangeStream(client, url, referer, extraHeaders, out, listener, isCancelled, progressWeight, baseProgress)
            return
        }

        val request = buildRequestWithHeaders(url, referer, extraHeaders)

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) {
                throw IllegalStateException("HTTP помилка ${response.code} при завантаженні прямого файлу")
            }
            val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
            if (contentType.startsWith("image/") || contentType.startsWith("text/")) {
                throw IllegalStateException("Отримано не відео ($contentType) замість відеофайлу")
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
                        val localPct = if (totalBytes > 0) (downloaded * 100f / totalBytes) else 0f
                        val pct = baseProgress + (localPct * progressWeight)
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

    private fun downloadDirectRangeStream(
        client: OkHttpClient,
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>,
        out: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?,
        progressWeight: Float,
        baseProgress: Float
    ) {
        val startTime = System.currentTimeMillis()
        var lastUpdate = 0L
        var downloaded = 0L
        var totalBytes = 0L
        val buffer = ByteArray(64 * 1024)

        var offset = 0L
        while (true) {
            if (isCancelled?.get() == true) break
            val end = if (totalBytes > 0L) {
                min(offset + YT_RANGE_CHUNK_BYTES - 1L, totalBytes - 1L)
            } else {
                offset + YT_RANGE_CHUNK_BYTES - 1L
            }
            val request = buildRequestWithHeaders(url, referer, extraHeaders, "bytes=$offset-$end")
            var isChunkedRange = false

            var chunkBytesRead = 0L
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    throw IllegalStateException("HTTP помилка ${response.code} при завантаженні прямого файлу")
                }
                val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
                if (contentType.startsWith("image/") || contentType.startsWith("text/")) {
                    throw IllegalStateException("Отримано не відео ($contentType) замість відеофайлу")
                }

                if (offset == 0L) {
                    val crTotal = if (response.code == 206) parseContentRangeTotal(response.header("Content-Range")) else null
                    if (crTotal != null) {
                        totalBytes = crTotal
                        isChunkedRange = true
                    } else {
                        totalBytes = response.body?.contentLength() ?: 0L
                    }
                } else {
                    isChunkedRange = response.code == 206
                }

                var bytesRead: Int
                response.body?.byteStream()?.use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled?.get() == true) break
                        out.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        offset += bytesRead
                        chunkBytesRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_THROTTLE_MS) {
                            lastUpdate = now
                            val localPct = if (totalBytes > 0L) (downloaded * 100f / totalBytes) else 0f
                            val pct = baseProgress + (localPct * progressWeight)
                            val elapsedSec = max((now - startTime) / 1000.0, 0.1)
                            val speedBytesPerSec = downloaded / elapsedSec
                            val speedStr = formatSpeed(speedBytesPerSec)
                            val etaStr = if (totalBytes > 0L) formatEta(((totalBytes - downloaded) / max(speedBytesPerSec, 1.0)).toLong()) else ""
                            listener?.onProgress(pct, speedStr, etaStr, downloaded, totalBytes)
                        }
                    }
                }
            }

            if (isCancelled?.get() != true && chunkBytesRead == 0L) {
                throw IllegalStateException("Сервер повернув порожній фрагмент при завантаженні відео")
            }
            if (!isChunkedRange || totalBytes <= 0L || offset >= totalBytes) {
                break
            }
        }
        out.flush()
        if (isCancelled?.get() != true && downloaded == 0L) {
            throw IllegalStateException("Отримано порожній відеофайл")
        }
    }

    private fun downloadHlsStream(
        context: Context,
        client: OkHttpClient,
        m3u8Url: String,
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        desiredQuality: String,
        out: OutputStream,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ) {
        val cleanM3u8Url = ExtractorUtils.stripFragmentHint(m3u8Url)
        val baseUri = URI(cleanM3u8Url)
        var currentPlaylistUrl = cleanM3u8Url
        var audioPlaylistUrl: String? = null

        // 1. Завантажуємо первинний плейлист
        val req = buildRequestWithHeaders(currentPlaylistUrl, referer, extraHeaders)
        val content = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful || resp.body == null) {
                throw IllegalStateException("Помилка завантаження HLS m3u8 (${resp.code})")
            }
            resp.body?.string() ?: throw IllegalStateException("Порожній m3u8 плейлист")
        }

        // 2. Якщо це Master Playlist, обираємо найкращий варіант відео та аудіо-потік
        if (content.contains("#EXT-X-STREAM-INF")) {
            val fragmentHint = if (cleanM3u8Url != m3u8Url.trim()) m3u8Url.substringAfter("#", "") else ""
            val qualityHint = fragmentHint.ifEmpty { desiredQuality }
            val targetHeight = ExtractorUtils.extractQualityHeight(qualityHint) ?: 1080

            val lines = content.lines()
            val groupAttrPattern = Pattern.compile("GROUP-ID=[\"']([^\"']+)[\"']")
            val streamAudioGroupPattern = Pattern.compile("AUDIO=[\"']([^\"']+)[\"']")

            data class HlsAudioTrack(val groupId: String, val isDefault: Boolean, val uri: String)
            val audioTracks = mutableListOf<HlsAudioTrack>()
            for (line in lines) {
                val l = line.trim()
                if (l.startsWith("#EXT-X-MEDIA:") && l.contains("TYPE=AUDIO")) {
                    val uriM = URI_PATTERN.matcher(l)
                    if (uriM.find()) {
                        val rawAudio = uriM.group(1) ?: ""
                        if (rawAudio.isNotEmpty()) {
                            val resolved = if (rawAudio.startsWith("http")) rawAudio else baseUri.resolve(rawAudio).toString()
                            val gm = groupAttrPattern.matcher(l)
                            val gid = if (gm.find()) gm.group(1).orEmpty() else ""
                            val isDef = l.contains("DEFAULT=YES", ignoreCase = true) ||
                                l.contains("AUTOSELECT=YES", ignoreCase = true)
                            audioTracks.add(HlsAudioTrack(gid, isDef, resolved))
                        }
                    }
                }
            }

            var selectedSubUrl: String? = null
            var selectedAudioGroup: String? = null
            var bestDiff = Int.MAX_VALUE
            var bestCodecRank = -1
            var currentHeight = 0
            var currentCodecRank = 1
            var currentAudioGroup: String? = null

            for (line in lines) {
                val l = line.trim()
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    currentHeight = 0
                    val lowerLine = l.lowercase(Locale.ROOT)
                    currentCodecRank = when {
                        lowerLine.contains("avc1") -> 3
                        lowerLine.contains("hev1") || lowerLine.contains("hvc1") -> 2
                        !lowerLine.contains("av01") -> 1
                        else -> 0
                    }
                    val rm = RESOLUTION_PATTERN.matcher(l)
                    if (rm.find()) {
                        val w = rm.group(1)?.toIntOrNull() ?: 0
                        val h = rm.group(2)?.toIntOrNull() ?: 0
                        currentHeight = ExtractorUtils.normalizeResolutionTier(w, h)
                    }
                    val am = streamAudioGroupPattern.matcher(l)
                    currentAudioGroup = if (am.find()) am.group(1) else null
                } else if (!l.startsWith("#") && l.isNotEmpty()) {
                    val candidateUrl = if (l.startsWith("http://") || l.startsWith("https://")) l else baseUri.resolve(l).toString()
                    if (currentHeight > 0) {
                        val diff = abs(currentHeight - targetHeight)
                        if (diff < bestDiff || (diff == bestDiff && currentCodecRank > bestCodecRank)) {
                            bestDiff = diff
                            bestCodecRank = currentCodecRank
                            selectedSubUrl = candidateUrl
                            selectedAudioGroup = currentAudioGroup
                        }
                    } else if (selectedSubUrl == null) {
                        selectedSubUrl = candidateUrl
                        selectedAudioGroup = currentAudioGroup
                    }
                }
            }
            if (selectedSubUrl != null) {
                currentPlaylistUrl = selectedSubUrl
            }
            if (audioTracks.isNotEmpty()) {
                val chosenAudio = audioTracks
                    .filter { selectedAudioGroup.isNullOrEmpty() || it.groupId == selectedAudioGroup }
                    .maxByOrNull { it.isDefault }
                    ?: audioTracks.firstOrNull { it.isDefault }
                    ?: audioTracks.first()
                audioPlaylistUrl = chosenAudio.uri
            }
        }

        // 3. Завантаження сегментів
        if (audioPlaylistUrl != null) {
            // Роздільні потоки відео і аудіо (наприклад, Fenix fMP4)
            val tempVideoFile = File.createTempFile("temp_video_", ".mp4", context.cacheDir)
            val tempAudioFile = File.createTempFile("temp_audio_", ".mp4", context.cacheDir)

            try {
                FileOutputStream(tempVideoFile).use { videoOut ->
                    downloadPlaylistSegments(client, currentPlaylistUrl, referer, extraHeaders, videoOut, 0.70f, 0f, listener, isCancelled)
                }

                if (isCancelled?.get() == true) return

                FileOutputStream(tempAudioFile).use { audioOut ->
                    downloadPlaylistSegments(client, audioPlaylistUrl, referer, extraHeaders, audioOut, 0.25f, 70f, listener, isCancelled)
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
            downloadPlaylistSegments(client, currentPlaylistUrl, referer, extraHeaders, out, 1.0f, 0f, listener, isCancelled)
        }
    }

    private fun downloadPlaylistSegments(
        client: OkHttpClient,
        playlistUrl: String,
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        out: OutputStream,
        progressWeight: Float,
        baseProgress: Float,
        listener: DownloadProgressListener?,
        isCancelled: AtomicBoolean?
    ): Long {
        val mediaReq = buildRequestWithHeaders(playlistUrl, referer, extraHeaders)
        val mediaContent = client.newCall(mediaReq).execute().use { mediaResp ->
            if (!mediaResp.isSuccessful || mediaResp.body == null) {
                throw IllegalStateException("Помилка завантаження медіа-плейлиста HLS (${mediaResp.code})")
            }
            mediaResp.body?.string() ?: ""
        }
        val mediaBaseUri = URI(playlistUrl)

        val segments = mutableListOf<Pair<String, SegmentKeySpec>>()
        var initSegmentUrl: String? = null
        var currentKeySpec = SegmentKeySpec(isAes128 = false, keyUrl = null, ivHex = null)
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
                            val mUri = URI_PATTERN.matcher(l)
                            val keyUrl = if (mUri.find()) {
                                val rawKeyUri = mUri.group(1) ?: ""
                                if (rawKeyUri.startsWith("http")) rawKeyUri else mediaBaseUri.resolve(rawKeyUri).toString()
                            } else {
                                currentKeySpec.keyUrl
                            }
                            val mIv = IV_PATTERN.matcher(l)
                            val ivHex = if (mIv.find()) mIv.group(1) else null
                            currentKeySpec = SegmentKeySpec(isAes128 = true, keyUrl = keyUrl, ivHex = ivHex)
                        } else if (method.equals("NONE", ignoreCase = true)) {
                            currentKeySpec = SegmentKeySpec(isAes128 = false, keyUrl = null, ivHex = null)
                        }
                    }
                } else if (!l.startsWith("#") && l.isNotEmpty()) {
                    val segUrl = if (l.startsWith("http://") || l.startsWith("https://")) l else mediaBaseUri.resolve(l).toString()
                    segments.add(segUrl to currentKeySpec)
                }
            }
        }

        var totalDownloaded = 0L

        // Завантаження fMP4 ініціалізаційного сегмента (#EXT-X-MAP)
        val targetInitUrl = initSegmentUrl
        if (!targetInitUrl.isNullOrEmpty()) {
            val initReq = buildRequestWithHeaders(targetInitUrl, referer, extraHeaders)
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

        val totalSegments = segments.size
        if (totalSegments == 0) {
            throw IllegalStateException("Не знайдено відео-сегментів у HLS плейлисті")
        }

        // Кеш AES-128 ключів (підтримка ротації ключів у плейлисті)
        val aesKeyCache = mutableMapOf<String, ByteArray>()
        fun getAesKey(keyUrl: String): ByteArray {
            return aesKeyCache.getOrPut(keyUrl) {
                val keyReq = buildRequestWithHeaders(keyUrl, referer, extraHeaders)
                var fetched: ByteArray? = null
                client.newCall(keyReq).execute().use { keyResp ->
                    if (keyResp.isSuccessful && keyResp.body != null) {
                        fetched = keyResp.body?.bytes()
                    }
                }
                val valid = fetched
                if (valid == null || valid.size != 16) {
                    throw IllegalStateException("Не вдалося завантажити ключ шифрування AES-128")
                }
                valid
            }
        }

        val startTime = System.currentTimeMillis()
        var lastUpdate = 0L
        var failedSegments = 0

        for (i in 0 until totalSegments) {
            if (isCancelled?.get() == true) break

            val (segUrl, keySpec) = segments[i]
            val segBytes = downloadSegmentWithRetry(client, segUrl, referer, extraHeaders, isCancelled)
            if (segBytes != null && segBytes.isNotEmpty()) {
                val finalBytes = if (keySpec.isAes128 && !keySpec.keyUrl.isNullOrEmpty()) {
                    val keyBytes = getAesKey(keySpec.keyUrl)
                    decryptAes128(segBytes, keyBytes, keySpec.ivHex, mediaSequence + i)
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
        extraHeaders: Map<String, String> = emptyMap(),
        isCancelled: AtomicBoolean?
    ): ByteArray? {
        val segReq = buildRequestWithHeaders(segUrl, referer, extraHeaders)
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

    internal fun selectMuxerOutputFormat(videoMime: String, audioMime: String): Int {
        val v = videoMime.lowercase(Locale.ROOT)
        val a = audioMime.lowercase(Locale.ROOT)
        val audioIsWebm = a.contains("opus") || a.contains("vorbis")
        val videoIsVpx = v.contains("vp9") || v.contains("vp8")
        return if (audioIsWebm || (videoIsVpx && a.isEmpty())) {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        } else {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }
    }

    private fun muxVideoAndAudio(videoFile: File, audioFile: File, outStream: OutputStream) {
        val tempMuxFile = File.createTempFile("muxed_", ".tmp", videoFile.parentFile)
        var muxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var startedWritingOutput = false

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            var selectedVideoFormat: MediaFormat? = null
            var videoMime = ""
            var requiredBufferSize = 4 * 1024 * 1024
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        requiredBufferSize = max(requiredBufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    selectedVideoFormat = format
                    videoMime = mime
                    videoExtractor.selectTrack(i)
                    break
                }
            }

            if (selectedVideoFormat == null) {
                throw IllegalStateException("У відеопотоці не знайдено відеодоріжку")
            }

            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
            var selectedAudioFormat: MediaFormat? = null
            var audioMime = ""
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        requiredBufferSize = max(requiredBufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    selectedAudioFormat = format
                    audioMime = mime
                    audioExtractor.selectTrack(i)
                    break
                }
            }

            val outputFormat = selectMuxerOutputFormat(videoMime, audioMime)
            muxer = MediaMuxer(tempMuxFile.absolutePath, outputFormat)
            val videoTrackIdx = muxer.addTrack(selectedVideoFormat)
            var audioTrackIdx = -1
            if (selectedAudioFormat != null) {
                audioTrackIdx = try {
                    muxer.addTrack(selectedAudioFormat)
                } catch (_: Exception) {
                    -1
                }
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(requiredBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var hasVideo = true
            var hasAudio = audioTrackIdx != -1

            while (hasVideo || hasAudio) {
                val videoTime = if (hasVideo) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (hasAudio) audioExtractor.sampleTime else Long.MAX_VALUE

                val writeVideo = hasVideo && (!hasAudio || videoTime <= audioTime)
                if (writeVideo) {
                    buffer.clear()
                    bufferInfo.offset = 0
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        hasVideo = false
                    } else {
                        bufferInfo.presentationTimeUs = videoTime.coerceAtLeast(0L)
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(videoTrackIdx, buffer, bufferInfo)
                        if (!videoExtractor.advance()) {
                            hasVideo = false
                        }
                    }
                } else if (hasAudio) {
                    buffer.clear()
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        hasAudio = false
                    } else {
                        bufferInfo.presentationTimeUs = audioTime.coerceAtLeast(0L)
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(audioTrackIdx, buffer, bufferInfo)
                        if (!audioExtractor.advance()) {
                            hasAudio = false
                        }
                    }
                }
            }

            muxer.stop()
            startedWritingOutput = true
            tempMuxFile.inputStream().use { it.copyTo(outStream) }
        } catch (e: Exception) {
            if (startedWritingOutput) {
                throw e
            }
            // У разі помилки муксування (до запису в outStream) копіюємо оригінальний відеофайл
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
            throw IllegalStateException("Помилка розшифрування AES-128 сегмента HLS: ${e.message}", e)
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
