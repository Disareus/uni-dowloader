package ua.aniua.downloader.model

import java.io.Serializable

data class DownloadTask(
    var id: String = "",
    var mediaTitle: String = "",
    var studioName: String = "",
    var episodeNumber: String = "1",
    var episodeTitle: String = "",
    var iframeUrl: String = "",
    var streamUrl: String = "",
    var referer: String = "",
    var quality: String = "1080p",
    var format: String = "mp4",
    var folderName: String? = null,
    var state: DownloadState = DownloadState.PENDING,
    var progress: Float = 0f,
    var speed: String = "",
    var eta: String = "",
    var downloadedBytes: Long = 0L,
    var totalBytes: Long = 0L,
    var savedFilePath: String? = null,
    var errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) : Serializable
