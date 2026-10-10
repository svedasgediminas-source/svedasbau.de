package app.novaiptv.feature.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.domain.FavoritesRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.Season
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.WatchHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface SeriesInfoState {
    data object Loading : SeriesInfoState
    data object NotFound : SeriesInfoState
    data class Loaded(val series: Series) : SeriesInfoState
}

internal sealed interface SeasonsState {
    data object Loading : SeasonsState
    data class Error(val error: AppError) : SeasonsState
    data class Loaded(val seasons: List<Season>) : SeasonsState
}

internal class SeriesDetailViewModel(
    private val seriesId: String,
    private val vod: VodRepository,
    private val favorites: FavoritesRepository,
    private val history: HistoryRepository,
) : ViewModel() {
    private val _info = MutableStateFlow<SeriesInfoState>(SeriesInfoState.Loading)
    val info: StateFlow<SeriesInfoState> = _info.asStateFlow()

    private val _seasons = MutableStateFlow<SeasonsState>(SeasonsState.Loading)
    val seasons: StateFlow<SeasonsState> = _seasons.asStateFlow()

    /** Watch history of this series' episodes, keyed by episode id. */
    private val _history = MutableStateFlow<Map<String, WatchHistory>>(emptyMap())
    val episodeHistory: StateFlow<Map<String, WatchHistory>> = _history.asStateFlow()

    private val _selectedSeason = MutableStateFlow<Int?>(null)
    val selectedSeason: StateFlow<Int?> = _selectedSeason.asStateFlow()

    val nextUp: StateFlow<EpisodeLogic.NextUp?> = combine(_seasons, _history) { s, h ->
        (s as? SeasonsState.Loaded)?.let { EpisodeLogic.nextUp(it.seasons, h) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isFavorite: StateFlow<Boolean> = favorites.observeIsFavorite(ContentType.Series, seriesId)
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var seasonsJob: Job? = null

    init {
        viewModelScope.launch {
            _info.value = try {
                vod.getSeries(seriesId)?.let { SeriesInfoState.Loaded(it) } ?: SeriesInfoState.NotFound
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SeriesInfoState.NotFound
            }
        }
        loadSeasons()
    }

    fun loadSeasons() {
        seasonsJob?.cancel()
        seasonsJob = viewModelScope.launch {
            _seasons.value = SeasonsState.Loading
            val result = try {
                vod.getSeasons(seriesId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Err(AppError.Unknown(e.message))
            }
            when (result) {
                is AppResult.Err -> _seasons.value = SeasonsState.Error(result.error)
                is AppResult.Ok -> {
                    val sorted = EpisodeLogic.sortSeasons(result.value)
                    val historyMap = loadHistory(sorted)
                    _history.value = historyMap
                    _seasons.value = SeasonsState.Loaded(sorted)
                    if (_selectedSeason.value == null || sorted.none { it.number == _selectedSeason.value }) {
                        _selectedSeason.value = EpisodeLogic.initialSeason(sorted, EpisodeLogic.nextUp(sorted, historyMap))
                    }
                }
            }
        }
    }

    /** Re-reads episode progress, e.g. when returning from the player. */
    fun refreshHistory() {
        val loaded = _seasons.value as? SeasonsState.Loaded ?: return
        viewModelScope.launch { _history.value = loadHistory(loaded.seasons) }
    }

    fun selectSeason(number: Int) {
        _selectedSeason.value = number
    }

    fun toggleFavorite() {
        val series = (_info.value as? SeriesInfoState.Loaded)?.series ?: return
        viewModelScope.launch {
            try {
                favorites.toggle(ContentType.Series, series.id, series.playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
            }
        }
    }

    private suspend fun loadHistory(seasons: List<Season>): Map<String, WatchHistory> {
        val map = HashMap<String, WatchHistory>()
        for (season in seasons) {
            for (episode in season.episodes) {
                val entry = try {
                    history.get(ContentType.Episode, episode.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (entry != null) map[episode.id] = entry
            }
        }
        return map
    }
}
