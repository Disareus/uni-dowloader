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

    fun getAllTasks(): List<DownloadTask> = tasks.values.sortedByDescending { it.createdAt }

    fun getTask(taskId: String): DownloadTask? = tasks[taskId]

    fun addListener(listener: (DownloadTask) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (DownloadTask) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyUpdate(task: DownloadTask) {
        mainHandler.post {
            for (listener in listeners) {
                listener(task)
            }
        }
    }

    fun enqueueDownload(task: DownloadTask) {
        tasks[task.id] = task
        val cancelToken = AtomicBoolean(false)
        cancelTokens[task.id] = cancelToken

        notifyUpdate(task)

        executor.execute {
            var outStream: OutputStream? = null
            var contentUri: Uri? = null

            try {
                val safeTitle = downloadSecurity.sanitizeFileName(task.mediaTitle)
                val safeStudio = if (task.studioName.isNotEmpty() && task.studioName != "Оригінал") {
                    downloadSecurity.sanitizeFileName(task.studioName)
                } else {
                    ""
                }
                val animeFolder = if (safeStudio.isNotEmpty()) "$safeTitle [$safeStudio]" else safeTitle
                val folderToUse = task.folderName?.ifEmpty { null } ?: animeFolder
                task.folderName = folderToUse

                val safeEp = if (task.episodeNumber.length == 1) "0${task.episodeNumber}" else task.episodeNumber
                val fileName = "$safeTitle - Серія $safeEp [${task.quality}].${task.format}"

                contentUri = downloadSecurity.createScopedVideoUri(fileName, "video/${task.format}", folderToUse)
                outStream = if (contentUri != null) {
                    task.savedFilePath = contentUri.toString()
                    context.contentResolver.openOutputStream(contentUri)
                } else {
                    val targetDir = downloadSecurity.getGuardedStorageDir(folderToUse)
                    val finalFile = File(targetDir, fileName)
                    downloadSecurity.validateCanonicalDestination(finalFile, targetDir)
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

                if (contentUri != null && task.state == DownloadState.COMPLETED) {
                    downloadSecurity.publishPendingVideo(contentUri)
                }

                notifyUpdate(task)

            } catch (e: Exception) {
                task.state = DownloadState.ERROR
                task.errorMessage = e.message ?: "Помилка завантаження"
                notifyUpdate(task)
            } finally {
                try {
                    outStream?.close()
                } catch (ignored: Exception) {}
            }
        }
    }

    fun cancelTask(taskId: String) {
        cancelTokens[taskId]?.set(true)
        tasks[taskId]?.let {
            it.state = DownloadState.CANCELLED
            notifyUpdate(it)
        }
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
