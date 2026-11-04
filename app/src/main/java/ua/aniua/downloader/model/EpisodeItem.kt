package ua.aniua.downloader.model

import java.io.Serializable

data class EpisodeItem(
    var id: String = "",
    var number: String = "1",
    var title: String = "",
    var season: String = "1",
    var iframeUrl: String = "",
    var isSelected: Boolean = false
) : Serializable
