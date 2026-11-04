package ua.aniua.downloader.model

import java.io.Serializable

data class MediaInfo(
    var url: String = "",
    var siteType: String = "generic",
    var title: String = "",
    var originalTitle: String? = null,
    var posterUrl: String? = null,
    var description: String? = null,
    var isSeries: Boolean = true,
    var studios: MutableList<DubStudio> = mutableListOf(),
    var availableQualities: MutableList<String> = mutableListOf("1080p", "720p", "480p", "360p")
) : Serializable
