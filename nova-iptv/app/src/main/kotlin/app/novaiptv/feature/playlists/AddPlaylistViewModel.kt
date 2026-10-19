package app.novaiptv.feature.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.common.UrlValidator
import app.novaiptv.core.common.XtreamUrlDetector
import app.novaiptv.core.common.getOrNull
import app.novaiptv.core.domain.ImportProgress
import app.novaiptv.core.domain.ImportSummary
import app.novaiptv.core.model.XtreamCredentials
import app.novaiptv.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Credentials found in an M3U URL; non-null while the "add as Xtream?" dialog is shown. */
    val xtreamOffer: XtreamCredentials? = null,
) {
    // Never expose the password through the generated toString (logs, crash reports).
    override fun toString(): String =
        "AddPlaylistUiState(name=$name, server=$server, username=***, password=***, submitting=$submitting, problem=$problem, error=$error, xtreamOffer=${xtreamOffer != null})"
}

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

    fun submit() = submitInternal(allowXtreamOffer = true)

    /** The user accepted converting an M3U link with an embedded login into an Xtream account. */
    fun acceptXtreamOffer() {
        val s = _state.value
        val creds = s.xtreamOffer ?: return
        if (s.submitting) return
        _state.update { it.copy(xtreamOffer = null, submitting = true, problem = null, error = null) }
        val name = s.name.trim().ifEmpty { hostOf(creds.serverUrl) }
        val playlists = container.playlists
        // Keep a custom EPG URL typed on the M3U form; it replaces the portal's XMLTV feed.
        val epg = s.epgUrl.trim().ifEmpty { null }
        val m3uUrl = s.url.trim()
        startImport(
            suspend {
                val xtream = playlists.addXtream(name = name, credentials = creds)
                // Many panels serve get.php without the Xtream API (player_api.php answers 404 or
                // non-JSON). Then the link still works as a plain M3U playlist: fall back to that.
                val result = if (xtream is AppResult.Err && xtream.error.meansNoXtreamApi()) {
                    playlists.addM3uUrl(name = name, url = m3uUrl, epgUrl = epg)
                } else {
                    xtream
                }
                if (epg != null && xtream is AppResult.Ok) {
                    playlists.setEpgUrls(xtream.value.playlistId, listOf(epg)) // also schedules the EPG refresh
                }
                result
            },
        )
    }

    /** The user wants to keep the link as a plain M3U playlist. */
    fun keepAsM3u() {
        _state.update { it.copy(xtreamOffer = null) }
        submitInternal(allowXtreamOffer = false)
    }

    /** Dialog dismissed (Back): nothing is imported, the form stays as it is. */
    fun dismissXtreamOffer() = _state.update { it.copy(xtreamOffer = null) }

    private fun submitInternal(allowXtreamOffer: Boolean) {
        val s = _state.value
        if (s.submitting) return
        val epg = s.epgUrl.trim().ifEmpty { null }
        if (epg != null && UrlValidator.validateSourceUrl(epg) is AppResult.Err) return fail(FormProblem.EpgUrlInvalid)

        // Validate locally first; the import itself runs in the app scope (see ImportTracker) so it
        // keeps going if the user leaves this screen. The lambdas capture only local values, not
        // the ViewModel.
        val playlists = container.playlists
        val importCall: suspend () -> AppResult<ImportSummary> = when (type) {
            PlaylistType.M3U_FILE -> {
                val uri = s.fileUri ?: return fail(FormProblem.FileMissing)
                val name = s.name.trim().ifEmpty { s.fileDisplayName ?: "Playlist" }
                val displayName = s.fileDisplayName ?: uri
                suspend { playlists.addM3uFile(name = name, uri = uri, displayName = displayName, epgUrl = epg) }
            }
            PlaylistType.XTREAM -> {
                val serverUrl = UrlValidator.normalizeServerUrl(s.server).getOrNull()
                    ?: return fail(FormProblem.ServerInvalid)
                if (s.username.isBlank()) return fail(FormProblem.UsernameMissing)
                if (s.password.isEmpty()) return fail(FormProblem.PasswordMissing)
                val name = s.name.trim().ifEmpty { hostOf(serverUrl) }
                val creds = XtreamCredentials(serverUrl, s.username.trim(), s.password)
                suspend { playlists.addXtream(name = name, credentials = creds) }
            }
            else -> {
                val url = UrlValidator.validateSourceUrl(s.url.trim())
                if (url !is AppResult.Ok) return fail(FormProblem.UrlInvalid)
                if (allowXtreamOffer) {
                    val detected = XtreamUrlDetector.detect(url.value)
                    if (detected != null) {
                        // Ask before storing a provider login in plain text as an M3U URL.
                        _state.update { it.copy(xtreamOffer = detected, problem = null, error = null) }
                        return
                    }
                }
                val name = s.name.trim().ifEmpty { hostOf(url.value) }
                val sourceUrl = url.value
                suspend { playlists.addM3uUrl(name = name, url = sourceUrl, epgUrl = epg) }
            }
        }
        _state.update { it.copy(submitting = true, problem = null, error = null) }
        startImport(importCall)
    }

    /**
     * Starts [importCall] in the application scope and waits for it here. If the ViewModel is cleared
     * (user pressed Back), only the waiting stops; the import finishes and the tracker still makes
     * the first playlist active.
     */
    private fun startImport(importCall: suspend () -> AppResult<ImportSummary>) {
        val pending = container.imports.start(importCall)
        viewModelScope.launch {
            when (val result = pending.await()) {
                is AppResult.Ok -> _state.update { it.copy(submitting = false, result = result.value, password = "") }
                is AppResult.Err -> _state.update { it.copy(submitting = false, error = result.error) }
            }
        }
    }

    private fun fail(problem: FormProblem) = _state.update { it.copy(submitting = false, problem = problem) }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: "Playlist"
}

/** Errors that mean the server has no (working) Xtream API, as opposed to a bad login or no network. */
internal fun AppError.meansNoXtreamApi(): Boolean = this is AppError.Http || this is AppError.Parse
