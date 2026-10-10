package app.novaiptv.feature.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.common.UrlValidator
import app.novaiptv.core.domain.ImportProgress
import app.novaiptv.core.domain.ImportSummary
import app.novaiptv.core.model.XtreamCredentials
import app.novaiptv.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which field failed local validation (shown next to the form). */
enum class FormProblem { NameMissing, UrlInvalid, EpgUrlInvalid, FileMissing, ServerInvalid, UsernameMissing, PasswordMissing }

data class AddPlaylistUiState(
    val name: String = "",
    val url: String = "",
    val epgUrl: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val fileUri: String? = null,
    val fileDisplayName: String? = null,
    val submitting: Boolean = false,
    val problem: FormProblem? = null,
    val error: AppError? = null,
    val result: ImportSummary? = null,
)

class AddPlaylistViewModel(private val container: AppContainer, val type: String) : ViewModel() {
    private val _state = MutableStateFlow(AddPlaylistUiState())
    val state: StateFlow<AddPlaylistUiState> = _state.asStateFlow()

    val importProgress: StateFlow<ImportProgress> = container.playlists.observeImportProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportProgress.Idle)

    fun onName(v: String) = _state.update { it.copy(name = v, problem = null) }
    fun onUrl(v: String) = _state.update { it.copy(url = v, problem = null) }
    fun onEpgUrl(v: String) = _state.update { it.copy(epgUrl = v, problem = null) }
    fun onServer(v: String) = _state.update { it.copy(server = v, problem = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v, problem = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, problem = null) }
    fun onFilePicked(uri: String, displayName: String) = _state.update {
        it.copy(fileUri = uri, fileDisplayName = displayName, name = it.name.ifBlank { displayName.substringBeforeLast('.') }, problem = null)
    }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val epg = s.epgUrl.trim().ifEmpty { null }
        if (epg != null && UrlValidator.validateSourceUrl(epg) is AppResult.Err) return fail(FormProblem.EpgUrlInvalid)

        _state.update { it.copy(submitting = true, problem = null, error = null) }
        viewModelScope.launch {
            val result: AppResult<ImportSummary>? = when (type) {
                PlaylistType.M3U_FILE -> {
                    val uri = s.fileUri
                    if (uri == null) { fail(FormProblem.FileMissing); null } else {
                        container.playlists.addM3uFile(
                            name = s.name.trim().ifEmpty { s.fileDisplayName ?: "Playlist" },
                            uri = uri,
                            displayName = s.fileDisplayName ?: uri,
                            epgUrl = epg,
                        )
                    }
                }
                PlaylistType.XTREAM -> {
                    val server = UrlValidator.normalizeServerUrl(s.server)
                    when {
                        server !is AppResult.Ok -> { fail(FormProblem.ServerInvalid); null }
                        s.username.isBlank() -> { fail(FormProblem.UsernameMissing); null }
                        s.password.isEmpty() -> { fail(FormProblem.PasswordMissing); null }
                        else -> container.playlists.addXtream(
                            name = s.name.trim().ifEmpty { hostOf(server.value) },
                            credentials = XtreamCredentials(server.value, s.username.trim(), s.password),
                        )
                    }
                }
                else -> {
                    val url = UrlValidator.validateSourceUrl(s.url.trim())
                    if (url !is AppResult.Ok) { fail(FormProblem.UrlInvalid); null } else {
                        container.playlists.addM3uUrl(
                            name = s.name.trim().ifEmpty { hostOf(url.value) },
                            url = url.value,
                            epgUrl = epg,
                        )
                    }
                }
            }
            when (result) {
                null -> Unit
                is AppResult.Ok -> {
                    // The first playlist becomes the active one automatically.
                    val prefs = container.preferences.preferences.first()
                    if (prefs.activePlaylistId == null) container.preferences.setActivePlaylist(result.value.playlistId)
                    _state.update { it.copy(submitting = false, result = result.value, password = "") }
                }
                is AppResult.Err -> _state.update { it.copy(submitting = false, error = result.error) }
            }
        }
    }

    private fun fail(problem: FormProblem) = _state.update { it.copy(submitting = false, problem = problem) }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: "Playlist"
}
