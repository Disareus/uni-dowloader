package ua.aniua.downloader.model

enum class DownloadState {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    ERROR,
    CANCELLED
}
