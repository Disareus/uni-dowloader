package ua.aniua.downloader.model

import java.io.Serializable

data class StreamQuality(
    var label: String = "1080p",
    var height: Int = 1080,
    var url: String = "",
    var isHls: Boolean = false,
    val headers: MutableMap<String, String> = mutableMapOf(),
    var subtitles: String? = null
) : Serializable
