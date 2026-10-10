package app.novaiptv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.domain.ImportProgress
import app.novaiptv.core.model.AppLanguage
import app.novaiptv.core.model.Playlist
import app.novaiptv.core.model.UserPreferences
import app.novaiptv.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SettingsMessage {
    data class EpgRefreshed(val programs: Int) : SettingsMessage
    data class PlaylistsRefreshed(val count: Int) : SettingsMessage
    data object HistoryCleared : SettingsMessage
    data object NothingToRefresh : SettingsMessage
    data class Failed(val error: AppError) : SettingsMessage
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = container.preferences.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    val playlists: StateFlow<List<Playlist>> = container.playlists.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val importProgress: StateFlow<ImportProgress> = container.playlists.observeImportProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportProgress.Idle)

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun update(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { container.preferences.update(transform) }
    }

    /** The activity observes the preference and applies the locale right away. */
    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { container.preferences.setLanguage(language) }
    }

    fun refreshEpg() = runBusy {
        val list = playlists.value
        if (list.isEmpty()) {
            _message.value = SettingsMessage.NothingToRefresh
            return@runBusy
        }
        var total = 0
        var error: AppError? = null
        for (p in list) {
            when (val r = container.epg.refresh(p.id)) {
                is AppResult.Ok -> total += r.value
                is AppResult.Err -> error = r.error
            }
        }
        val failure = error
        _message.value = if (failure != null && total == 0) SettingsMessage.Failed(failure) else SettingsMessage.EpgRefreshed(total)
    }

    fun refreshAllPlaylists() = runBusy {
        val list = playlists.value
        if (list.isEmpty()) {
            _message.value = SettingsMessage.NothingToRefresh
            return@runBusy
        }
        var ok = 0
        var error: AppError? = null
        for (p in list) {
            when (val r = container.playlists.refresh(p.id)) {
                is AppResult.Ok -> ok++
                is AppResult.Err -> error = r.error
            }
        }
        val failure = error
        _message.value = if (failure != null) SettingsMessage.Failed(failure) else SettingsMessage.PlaylistsRefreshed(ok)
    }

    fun clearHistory() = runBusy {
        container.history.clear()
        _message.value = SettingsMessage.HistoryCleared
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}
