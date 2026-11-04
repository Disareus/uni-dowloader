package ua.aniua.downloader.model

import java.io.Serializable

data class PlayerSource(
    var id: String = "",
    var name: String = "",
    var episodes: MutableList<EpisodeItem> = mutableListOf()
) : Serializable
