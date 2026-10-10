package app.novaiptv.di

import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.domain.ImportSummary
import app.novaiptv.core.domain.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Runs "add playlist" imports in the application scope, so they keep going when the user leaves
 * the add screen (the ViewModel only awaits the result). Live progress stays available through
 * `PlaylistRepository.observeImportProgress()`; this class adds what must also happen when no
 * screen is listening: the first playlist becomes the active one.
 */
class ImportTracker(
    private val scope: CoroutineScope,
    private val preferences: PreferencesRepository,
) {
    private val _running = MutableStateFlow(0)

    /** Number of add-imports currently started from the UI and not yet finished. */
    val running: StateFlow<Int> = _running.asStateFlow()

    /** Starts [block] in the app scope. Awaiting the result may be cancelled without stopping the import. */
    fun start(block: suspend () -> AppResult<ImportSummary>): Deferred<AppResult<ImportSummary>> {
        _running.update { it + 1 }
        return scope.async {
            try {
                val result = try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppResult.Err(AppError.Unknown(e.javaClass.simpleName))
                }
                if (result is AppResult.Ok) activateIfFirst(result.value.playlistId)
                result
            } finally {
                _running.update { it - 1 }
            }
        }
    }

    private suspend fun activateIfFirst(playlistId: Long) {
        try {
            if (preferences.preferences.first().activePlaylistId == null) preferences.setActivePlaylist(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Preferences unavailable: the user can still pick the playlist manually.
        }
    }
}
