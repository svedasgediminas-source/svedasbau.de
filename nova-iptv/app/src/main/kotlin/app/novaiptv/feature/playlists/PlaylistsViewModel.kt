package app.novaiptv.feature.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.common.UrlValidator
import app.novaiptv.core.domain.ImportProgress
import app.novaiptv.core.domain.ImportSummary
import app.novaiptv.core.model.Playlist
import app.novaiptv.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Result of the last user action, shown as a banner. */
sealed interface PlaylistMessage {
    data class Refreshed(val summary: ImportSummary) : PlaylistMessage
    data class Failed(val error: AppError) : PlaylistMessage
    data object EpgUrlInvalid : PlaylistMessage
    data object Deleted : PlaylistMessage
    data object Saved : PlaylistMessage
}

data class PlaylistsUiState(
    val playlists: List<Playlist> = emptyList(),
    val activePlaylistId: Long? = null,
    val loading: Boolean = true,
)

class PlaylistsViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<PlaylistsUiState> =
        combine(container.playlists.observePlaylists(), container.preferences.preferences) { playlists, prefs ->
            val active = playlists.firstOrNull { it.id == prefs.activePlaylistId } ?: playlists.firstOrNull()
            PlaylistsUiState(playlists, active?.id, loading = false)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    val importProgress: StateFlow<ImportProgress> = container.playlists.observeImportProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportProgress.Idle)

    private val _message = MutableStateFlow<PlaylistMessage?>(null)
    val message: StateFlow<PlaylistMessage?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun refresh(id: Long) = launchBusy {
        _message.value = when (val r = container.playlists.refresh(id)) {
            is AppResult.Ok -> PlaylistMessage.Refreshed(r.value)
            is AppResult.Err -> PlaylistMessage.Failed(r.error)
        }
    }

    fun rename(id: Long, name: String) {
        if (name.isBlank()) return
        launchBusy {
            container.playlists.rename(id, name.trim())
            _message.value = PlaylistMessage.Saved
        }
    }

    /** Accepts one URL or several separated by spaces, commas or new lines. Empty clears. */
    fun setEpgUrls(id: Long, raw: String) {
        val urls = raw.split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.any { UrlValidator.validateSourceUrl(it) is AppResult.Err }) {
            _message.value = PlaylistMessage.EpgUrlInvalid
            return
        }
        launchBusy {
            container.playlists.setEpgUrls(id, urls)
            _message.value = PlaylistMessage.Saved
        }
    }

    fun setActive(id: Long) = launchBusy { container.preferences.setActivePlaylist(id) }

    fun delete(id: Long) = launchBusy {
        container.playlists.delete(id)
        if (container.preferences.preferences.first().activePlaylistId == id) {
            container.preferences.setActivePlaylist(null)
        }
        _message.value = PlaylistMessage.Deleted
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}
