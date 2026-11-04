package ua.aniua.downloader.model

import java.io.Serializable

data class DubStudio(
    var id: String = "",
    var name: String = "",
    var players: MutableList<PlayerSource> = mutableListOf()
) : Serializable
