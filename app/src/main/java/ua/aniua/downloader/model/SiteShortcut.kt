package ua.aniua.downloader.model

import java.io.Serializable

data class SiteShortcut(
    val id: String,
    val name: String,
    val url: String
) : Serializable
