package app.novaiptv.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.Clock
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.Series
import app.novaiptv.feature.movies.AdultContentFilter
import app.novaiptv.feature.movies.activePlaylistIdFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

internal data class SearchResults(
    val query: String,
    val channels: List<Channel>,
    /** Channel id -> title of the programme on now. */
    val nowTitles: Map<String, String>,
    val movies: List<Movie>,
    val series: List<Series>,
    val programmes: List<ProgrammeHit>,
    val nowMillis: Long,
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() && programmes.isEmpty()
}

internal sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object NoPlaylist : SearchUiState
    data object Loading : SearchUiState
    data class Results(val results: SearchResults) : SearchUiState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class SearchViewModel(
    private val channels: ChannelRepository,
    private val vod: VodRepository,
    private val epg: EpgRepository,
    private val prefs: PreferencesRepository,
    playlists: PlaylistRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** EPG id -> channel for the active playlist, built once per playlist (programme search). */
    private var epgIndex: Pair<Long, Map<String, Channel>>? = null
    private var showingResults = false

    val state: StateFlow<SearchUiState> =
        combine(
            _query.map { SearchQuery.normalize(it) }
                .distinctUntilChanged()
                // Clearing is instant; typing is debounced.
                .debounce { if (it.length < SearchQuery.MIN_LENGTH) 0L else SearchQuery.DEBOUNCE_MILLIS },
            activePlaylistIdFlow(prefs, playlists),
        ) { q, playlistId -> q to playlistId }
            .distinctUntilChanged()
            .transformLatest { (q, playlistId) ->
                when {
                    playlistId == null -> {
                        showingResults = false
                        emit(SearchUiState.NoPlaylist)
                    }
                    q.length < SearchQuery.MIN_LENGTH -> {
                        showingResults = false
                        emit(SearchUiState.Idle)
                    }
                    else -> {
                        // Keep the previous results on screen while the next query runs.
                        if (!showingResults) emit(SearchUiState.Loading)
                        val results = search(playlistId, q)
                        showingResults = true
                        emit(SearchUiState.Results(results))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Idle)

    fun onQueryChange(value: String) {
        _query.value = value
    }

    private suspend fun search(playlistId: Long, q: String): SearchResults = coroutineScope {
        val now = clock.nowMillis()
        val hideAdult = safe(true) { prefs.preferences.first().hideAdultContent }
        val channelsAsync = async { safe(emptyList()) { channels.search(playlistId, q, LIMIT) } }
        val moviesAsync = async { safe(emptyList()) { vod.searchMovies(playlistId, q, LIMIT) } }
        val seriesAsync = async { safe(emptyList()) { vod.searchSeries(playlistId, q, LIMIT) } }
        val programmesAsync = async { safe(emptyList()) { epg.searchPrograms(q, now, PROGRAMME_FETCH_LIMIT) } }
        val hiddenMovieCats = async {
            if (!hideAdult) emptySet<String>()
            else safe(emptySet()) { AdultContentFilter.split(vod.observeMovieCategories(playlistId).first(), true).hiddenIds }
        }
        val hiddenSeriesCats = async {
            if (!hideAdult) emptySet<String>()
            else safe(emptySet()) { AdultContentFilter.split(vod.observeSeriesCategories(playlistId).first(), true).hiddenIds }
        }

        val foundChannels = SearchRanking.rank(
            channelsAsync.await().filter { !(hideAdult && it.isAdult) },
            q, { it.id }, { it.name },
        )
        val movies = SearchRanking.rank(
            AdultContentFilter.filterItems(moviesAsync.await(), hiddenMovieCats.await()) { it.categoryId },
            q, { it.id }, { it.name },
        )
        val series = SearchRanking.rank(
            AdultContentFilter.filterItems(seriesAsync.await(), hiddenSeriesCats.await()) { it.categoryId },
            q, { it.id }, { it.name },
        )
        val programs = programmesAsync.await()
        val programmes = if (programs.isEmpty()) {
            emptyList()
        } else {
            val index = channelIndex(playlistId)
            SearchRanking.programmeHits(programs, index, now, PROGRAMME_LIMIT)
                .filter { !(hideAdult && it.channel.isAdult) }
        }

        val epgIds = foundChannels.mapNotNull { it.epgId }.distinct()
        val nowTitles = if (epgIds.isEmpty()) {
            emptyMap()
        } else {
            val nowNext = safe(emptyMap()) { epg.observeNowNext(epgIds, now).first() }
            foundChannels.mapNotNull { ch -> ch.epgId?.let { nowNext[it] }?.now?.title?.let { ch.id to it } }.toMap()
        }

        SearchResults(q, foundChannels, nowTitles, movies, series, programmes, now)
    }

    /**
     * There is no repository lookup by EPG id, so programme hits are mapped to channels through
     * an index of the active playlist's channels, built once and cached per playlist.
     */
    private suspend fun channelIndex(playlistId: Long): Map<String, Channel> {
        epgIndex?.takeIf { it.first == playlistId }?.let { return it.second }
        val all = safe(emptyList()) { channels.observeChannels(playlistId, null).first() }
        val index = withContext(Dispatchers.Default) { SearchRanking.indexByEpgId(all) }
        epgIndex = playlistId to index
        return index
    }

    private suspend fun <T> safe(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }

    private companion object {
        const val LIMIT = 40
        const val PROGRAMME_FETCH_LIMIT = 80
        const val PROGRAMME_LIMIT = 30
    }
}
