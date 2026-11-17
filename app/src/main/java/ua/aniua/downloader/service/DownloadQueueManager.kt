package ua.aniua.downloader.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import ua.aniua.downloader.model.DownloadState
import ua.aniua.downloader.model.DownloadTask
import ua.security.network.NetworkSecurityManager
import ua.security.storage.DownloadSecurityManager
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DownloadQueueManager private constructor(context: Context) {

    private val context = context.applicationContext
    private val httpClient = NetworkSecurityManager.getInstance(context).hardenedHttpClient
    private val downloadSecurity = DownloadSecurityManager.getInstance(context)

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val cancelTokens = ConcurrentHashMap<String, AtomicBoolean>()
    private val listeners = CopyOnWriteArrayList<(DownloadTask) -> Unit>()

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile
        private var sInstance: DownloadQueueManager? = null

        @JvmStatic
        fun getInstance(context: Context): DownloadQueueManager {
            return sInstance ?: synchronized(DownloadQueueManager::class.java) {
                sInstance ?: DownloadQueueManager(context).also { sInstance = it }
            }
        }
    }

    fun getAllTasks(): List<DownloadTask> = tasks.values.map { it.copy() }.sortedByDescending { it.createdAt }

    fun getActiveCount(): Int = tasks.values.count {
        it.state == DownloadState.PENDING || it.state == DownloadState.DOWNLOADING
    }

    fun addListener(listener: (DownloadTask) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (DownloadTask) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyUpdate(task: DownloadTask) {
        val snapshot = task.copy()
        mainHandler.post {
            for (listener in listeners) {
                listener(snapshot)
            }
        }
    }

    fun enqueueDownload(task: DownloadTask) {
        task.state = DownloadState.PENDING
        task.progress = 0f
        task.speed = ""
        task.eta = ""
        task.errorMessage = null

        tasks[task.id] = task
        val cancelToken = AtomicBoolean(false)
        cancelTokens[task.id] = cancelToken

        notifyUpdate(task)

        executor.execute {
            var outStream: OutputStream? = null
            var contentUri: Uri? = null
            var legacyFile: File? = null

            try {
                if (cancelToken.get()) {
                    task.state = DownloadState.CANCELLED
                    notifyUpdate(task)
                    return@execute
                }

                val safeTitle = downloadSecurity.sanitizeFileName(task.mediaTitle)
                val safeStudio = if (task.studioName.isNotEmpty() && task.studioName != "Оригінал") {
                    downloadSecurity.sanitizeFileName(task.studioName)
                } else {
                    ""
                }
                val animeFolder = if (safeStudio.isNotEmpty()) "$safeTitle [$safeStudio]" else safeTitle
                val folderToUse = task.folderName?.ifEmpty { null } ?: animeFolder
                task.folderName = folderToUse

                val isSeriesFolder = !task.folderName.isNullOrEmpty() && task.folderName != "Downloads"
                val isSingleVideo = task.isSingle || (!isSeriesFolder && (
                    task.episodeNumber == "0" || task.episodeNumber.isEmpty() ||
                        task.episodeTitle.contains("Повне відео") ||
                        task.episodeTitle.contains("YouTube") ||
                        task.episodeTitle.contains("TikTok") ||
                        task.episodeTitle.contains("Instagram") ||
                        task.mediaTitle == task.episodeTitle
                    ))

                val fileName = if (isSingleVideo) {
                    "$safeTitle [${task.quality}].${task.format}"
                } else {
                    val safeEp = if (task.episodeNumber.length == 1) "0${task.episodeNumber}" else task.episodeNumber
                    "$safeTitle - Серія $safeEp [${task.quality}].${task.format}"
                }

                val mimeType = when {
                    task.format.equals("mkv", ignoreCase = true) -> "video/x-matroska"
                    task.format.equals("webm", ignoreCase = true) -> "video/webm"
                    else -> "video/mp4"
                }
                contentUri = downloadSecurity.createScopedVideoUri(fileName, mimeType, folderToUse)
                outStream = if (contentUri != null) {
                    task.savedFilePath = contentUri.toString()
                    context.contentResolver.openOutputStream(contentUri)
                } else {
                    val targetDir = downloadSecurity.getGuardedStorageDir(folderToUse)
                    val finalFile = File(targetDir, fileName)
                    downloadSecurity.validateCanonicalDestination(finalFile, targetDir)
                    legacyFile = finalFile
                    task.savedFilePath = finalFile.absolutePath
                    FileOutputStream(finalFile)
                }

                if (outStream == null) {
                    throw IllegalStateException("Не вдалося відкрити потік для запису відео")
                }

                HlsSegmentDownloader.download(
                    context,
                    httpClient,
                    task,
                    outStream,
                    { pct, speed, eta, dl, total ->
                        task.progress = pct
                        task.speed = speed
                        task.eta = eta
                        task.downloadedBytes = dl
                        task.totalBytes = total
                        notifyUpdate(task)
                    },
                    cancelToken
                )

                try {
                    outStream.close()
                } catch (_: Exception) {}
                outStream = null

                if (task.state == DownloadState.COMPLETED && contentUri != null) {
                    downloadSecurity.publishPendingVideo(contentUri)
                } else if (task.state != DownloadState.COMPLETED) {
                    cleanupPartialOutput(contentUri, legacyFile)
                }

                notifyUpdate(task)
            } catch (e: Exception) {
                try {
                    outStream?.close()
                } catch (_: Exception) {}
                outStream = null
                task.state = if (cancelToken.get()) DownloadState.CANCELLED else DownloadState.ERROR
                task.errorMessage = if (task.state == DownloadState.ERROR) (e.message ?: "Помилка завантаження") else null
                cleanupPartialOutput(contentUri, legacyFile)
                notifyUpdate(task)
            } finally {
                try {
                    outStream?.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun cleanupPartialOutput(contentUri: Uri?, legacyFile: File?) {
        if (contentUri != null) {
            try {
                context.contentResolver.delete(contentUri, null, null)
            } catch (_: Exception) {}
        }
        if (legacyFile != null && legacyFile.exists()) {
            try {
                legacyFile.delete()
            } catch (_: Exception) {}
        }
    }

    fun cancelTask(taskId: String) {
        cancelTokens[taskId]?.set(true)
        tasks[taskId]?.let {
            if (it.state == DownloadState.PENDING || it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED) {
                it.state = DownloadState.CANCELLED
                notifyUpdate(it)
            }
        }
    }

    fun retryTask(taskId: String) {
        val existing = tasks[taskId] ?: return
        if (existing.state == DownloadState.ERROR || existing.state == DownloadState.CANCELLED) {
            enqueueDownload(existing)
        }
    }

    fun removeTask(taskId: String) {
        cancelTokens[taskId]?.set(true)
        cancelTokens.remove(taskId)
        tasks.remove(taskId)
    }

    fun clearFinished() {
        val toRemove = tasks.values.filter {
            it.state == DownloadState.COMPLETED || it.state == DownloadState.ERROR || it.state == DownloadState.CANCELLED
        }
        for (t in toRemove) {
            tasks.remove(t.id)
            cancelTokens.remove(t.id)
        }
    }
}
