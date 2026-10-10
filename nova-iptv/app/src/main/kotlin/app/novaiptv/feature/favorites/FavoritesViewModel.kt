package app.novaiptv.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.Clock
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.FavoritesRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class FavoritesUiState(
    val isLoading: Boolean = true,
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val recentChannels: List<Channel> = emptyList(),
    /** Channel id -> title of the programme on now. */
    val nowTitles: Map<String, String> = emptyMap(),
) {
    val hasFavorites: Boolean get() = channels.isNotEmpty() || movies.isNotEmpty() || series.isNotEmpty()
    val isEmpty: Boolean get() = !hasFavorites && recentChannels.isEmpty()
    val favoriteChannelIds: Set<String> get() = channels.mapTo(HashSet()) { it.id }
}

private data class ResolvedFavorites(
    val channels: List<Channel>,
    val movies: List<Movie>,
    val series: List<Series>,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class FavoritesViewModel(
    favorites: FavoritesRepository,
    private val channels: ChannelRepository,
    private val vod: VodRepository,
    history: HistoryRepository,
    private val epg: EpgRepository,
    private val clock: Clock,
) : ViewModel() {

    private val resolvedFavorites: Flow<ResolvedFavorites> = favorites.observeFavorites(null)
        .map { favs ->
            Triple(
                FavoritesLogic.idsOf(favs, ContentType.Channel),
                FavoritesLogic.idsOf(favs, ContentType.Movie),
                FavoritesLogic.idsOf(favs, ContentType.Series),
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (channelIds, movieIds, seriesIds) ->
            combine(
                channelsInOrder(channelIds),
                flow { emit(movieIds.mapNotNull { id -> safe { vod.getMovie(id) } }) },
                flow { emit(seriesIds.mapNotNull { id -> safe { vod.getSeries(id) } }) },
            ) { ch, mv, se -> ResolvedFavorites(ch, mv, se) }
        }
        .catch { emit(ResolvedFavorites(emptyList(), emptyList(), emptyList())) }

    private val recentChannels: Flow<List<Channel>> = history.observeRecentChannels(RECENT_LIMIT)
        .map { FavoritesLogic.recentChannelIds(it) }
        .distinctUntilChanged()
        .flatMapLatest { ids -> channelsInOrder(ids) }
        .catch { emit(emptyList()) }

    val state: StateFlow<FavoritesUiState> = combine(resolvedFavorites, recentChannels) { fav, recent -> fav to recent }
        .flatMapLatest { (fav, recent) ->
            val all = fav.channels + recent
            val epgIds = all.mapNotNull { it.epgId }.distinct()
            val base = FavoritesUiState(
                isLoading = false,
                channels = fav.channels,
                movies = fav.movies,
                series = fav.series,
                recentChannels = recent,
            )
            if (epgIds.isEmpty()) {
                flowOf(base)
            } else {
                minuteTicker()
                    .flatMapLatest { now -> epg.observeNowNext(epgIds, now) }
                    .map { nowNext ->
                        val titles = all.mapNotNull { ch ->
                            ch.epgId?.let { nowNext[it] }?.now?.title?.let { ch.id to it }
                        }.toMap()
                        base.copy(nowTitles = titles)
                    }
                    .catch { emit(base) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUiState())

    private fun channelsInOrder(ids: List<String>): Flow<List<Channel>> =
        if (ids.isEmpty()) flowOf(emptyList())
        else channels.observeChannelsByIds(ids).map { list -> FavoritesLogic.orderByIds(list, ids) { it.id } }

    private fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(60_000)
        }
    }

    private suspend fun <T> safe(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val RECENT_LIMIT = 20
    }
}
