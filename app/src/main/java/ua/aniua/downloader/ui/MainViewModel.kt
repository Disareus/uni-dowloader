package ua.aniua.downloader.ui

import androidx.lifecycle.ViewModel
import ua.aniua.downloader.model.DubStudio
import ua.aniua.downloader.model.MediaInfo
import ua.aniua.downloader.model.PlayerSource

/**
 * Зберігає стан проаналізованого медіа та вибору користувача при зміні конфігурації (повороті екрану).
 */
class MainViewModel : ViewModel() {
    var lastUrl: String = ""
    var currentMediaInfo: MediaInfo? = null
    var currentStudioId: String? = null
    var currentPlayerId: String? = null
    var currentStudio: DubStudio? = null
    var currentPlayer: PlayerSource? = null
    var allSelected: Boolean = false
    var isDescExpanded: Boolean = false
}
