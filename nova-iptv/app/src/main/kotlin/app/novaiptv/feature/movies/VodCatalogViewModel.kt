package app.novaiptv.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.model.VodCategory
import app.novaiptv.core.model.WatchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class CatalogState<T>(
    val isLoading: Boolean = true,
    val hasPlaylist: Boolean = true,
    val categories: List<VodCategory> = emptyList(),
    /** null = "All". */
    val selectedCategoryId: String? = null,
    val items: List<T> = emptyList(),
    val continueWatching: List<WatchHistory> = emptyList(),
)

/** What differs between the Movies and Series catalogues. */
internal interface CatalogSource<T> {
    fun categories(playlistId: Long): Flow<List<VodCategory>>
    fun items(playlistId: Long, categoryId: String?): Flow<List<T>>
    fun idOf(item: T): String
    fun categoryOf(item: T): String?
    fun continueWatching(history: List<WatchHistory>, playlistId: Long): List<WatchHistory>
}

/** prefs.activePlaylistId if it still exists, else the first playlist; null when there is none. */
internal fun activePlaylistIdFlow(prefs: PreferencesRepository, playlists: PlaylistRepository): Flow<Long?> =
    combine(
        prefs.preferences.map { it.activePlaylistId }.distinctUntilChanged(),
        playlists.observePlaylists().map { list -> list.map { it.id } }.distinctUntilChanged(),
    ) { preferred, ids -> ActivePlaylist.resolve(preferred, ids) }
        .distinctUntilChanged()

@OptIn(ExperimentalCoroutinesApi::class)
internal open class VodCatalogViewModel<T : Any>(
    private val source: CatalogSource<T>,
    prefs: PreferencesRepository,
    playlists: PlaylistRepository,
    private val history: HistoryRepository,
) : ViewModel() {

    private val selectedCategory = MutableStateFlow<String?>(null)

    val state: StateFlow<CatalogState<T>> =
        combine(
            activePlaylistIdFlow(prefs, playlists),
            prefs.preferences.map { it.hideAdultContent }.distinctUntilChanged(),
        ) { playlistId, hideAdult -> playlistId to hideAdult }
            .distinctUntilChanged()
            .flatMapLatest { (playlistId, hideAdult) ->
                if (playlistId == null) flowOf(CatalogState<T>(isLoading = false, hasPlaylist = false))
                else catalogFor(playlistId, hideAdult)
            }
            // Filtering/de-duplicating large catalogues happens off the main thread.
            .flowOn(Dispatchers.Default)
            .catch { emit(CatalogState(isLoading = false)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogState())

    fun selectCategory(categoryId: String?) {
        selectedCategory.value = categoryId
    }

    private fun catalogFor(playlistId: Long, hideAdult: Boolean): Flow<CatalogState<T>> {
        val split = source.categories(playlistId)
            .map { AdultContentFilter.split(it, hideAdult) }
            .distinctUntilChanged()
        val continueWatching = history.observeContinueWatching(CONTINUE_LIMIT)
            .map { list -> source.continueWatching(list, playlistId).distinctBy { it.contentType to it.contentId } }
            .catch { emit(emptyList()) }
            .distinctUntilChanged()

        return combine(split, selectedCategory) { s, selected ->
            // A selection that is no longer visible (hidden adult, removed) falls back to "All".
            s to selected?.takeIf { id -> s.visible.any { it.id == id } }
        }
            .distinctUntilChanged()
            .flatMapLatest { (s, selected) ->
                val items = source.items(playlistId, selected).map { list ->
                    AdultContentFilter.filterItems(list, s.hiddenIds) { source.categoryOf(it) }
                        .distinctBy { source.idOf(it) }
                }
                combine(items, continueWatching) { list, cont ->
                    CatalogState(
                        isLoading = false,
                        hasPlaylist = true,
                        categories = s.visible,
                        selectedCategoryId = selected,
                        items = list,
                        continueWatching = cont,
                    )
                }
            }
    }

    private companion object {
        const val CONTINUE_LIMIT = 60
    }
}
