package app.novaiptv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.domain.Recommendations
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.NowNext
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.WatchHistory
import app.novaiptv.di.AppContainer
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

/** A channel with its current/next programme for Home rails. */
data class ChannelNow(val channel: Channel, val now: EpgProgram?, val next: EpgProgram?) {
    fun progress(nowMillis: Long): Float? {
        val p = now ?: return null
        if (p.durationMillis <= 0) return null
        return ((nowMillis - p.startMillis).toFloat() / p.durationMillis).coerceIn(0f, 1f)
    }
}

/** Item of the mixed "Recently added" rail. */
sealed interface AddedItem {
    val addedAtMillis: Long
    data class MovieItem(val movie: Movie) : AddedItem {
        override val addedAtMillis: Long get() = movie.addedAtMillis ?: 0L
    }
    data class SeriesItem(val series: Series) : AddedItem {
        override val addedAtMillis: Long get() = series.addedAtMillis ?: 0L
    }
}

data class HomeHero(
    val source: Recommendations.Hero,
    val title: String,
    val description: String?,
    val imageUrl: String?,
    val meta: String?,
    /** Unfinished episode to resume when the hero is a series (Play button). */
    val resumeEpisodeId: String? = null,
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val hasPlaylists: Boolean = true,
    val failed: Boolean = false,
    val nowMillis: Long = 0L,
    val hero: HomeHero? = null,
    val continueWatching: List<WatchHistory> = emptyList(),
    val nowOnTv: List<ChannelNow> = emptyList(),
    /** True when [nowOnTv] is built from favourites; the separate favourites rail is then skipped. */
    val nowOnTvFromFavorites: Boolean = false,
    val favoriteChannels: List<ChannelNow> = emptyList(),
    val recentChannels: List<ChannelNow> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val recentlyAdded: List<AddedItem> = emptyList(),
    val favoriteChannelIds: Set<String> = emptySet(),
    val showChannelNumbers: Boolean = true,
) {
    val isEmpty: Boolean
        get() = hero == null && continueWatching.isEmpty() && nowOnTv.isEmpty() && favoriteChannels.isEmpty() &&
            recentChannels.isEmpty() && movies.isEmpty() && series.isEmpty() && recentlyAdded.isEmpty()
}

/** Size of the "Now on TV" rail, and how many leading channels to read when adult ones are hidden. */
private const val NOW_ON_TV = 20
private const val NOW_ON_TV_FETCH = 60

private data class HomeContext(val playlistId: Long?, val hideAdult: Boolean, val showNumbers: Boolean)

private data class ChannelsPart(
    val nowOnTv: List<Channel>,
    val nowOnTvFromFavorites: Boolean,
    val favorites: List<Channel>,
    val recent: List<Channel>,
    val recentHistory: List<WatchHistory>,
)

private data class VodPart(
    val continueWatching: List<WatchHistory>,
    val movies: List<Movie>,
    val series: List<Series>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(container.clock.nowMillis())
            delay(60_000)
        }
    }

    val uiState: StateFlow<HomeUiState> =
        combine(container.playlists.observePlaylists(), container.preferences.preferences) { playlists, prefs ->
            val active = playlists.firstOrNull { it.id == prefs.activePlaylistId } ?: playlists.firstOrNull()
            HomeContext(active?.id, prefs.hideAdultContent, prefs.showChannelNumbers)
        }
            .distinctUntilChanged()
            .flatMapLatest { ctx ->
                if (ctx.playlistId == null) flowOf(HomeUiState(isLoading = false, hasPlaylists = false))
                else contentFlow(ctx.playlistId, ctx)
            }
            .catch { emit(HomeUiState(isLoading = false, failed = true)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun channelsByIds(ids: List<String>, hideAdult: Boolean): Flow<List<Channel>> =
        if (ids.isEmpty()) flowOf(emptyList())
        else container.channels.observeChannelsByIds(ids).map { channels ->
            val byId = channels.associateBy { it.id }
            ids.mapNotNull { byId[it] }.filter { !(hideAdult && it.isAdult) }
        }

    private fun contentFlow(playlistId: Long, ctx: HomeContext): Flow<HomeUiState> {
        val favoriteChannels: Flow<List<Channel>> = container.favorites.observeFavorites(ContentType.Channel)
            .map { favs -> favs.sortedBy { it.sortOrder }.map { it.contentId } }
            .distinctUntilChanged()
            .flatMapLatest { ids -> channelsByIds(ids, ctx.hideAdult) }

        val recentHistory: Flow<List<WatchHistory>> = container.history.observeRecentChannels(20)

        val recentChannels: Flow<List<Channel>> = recentHistory
            .map { list -> list.map { it.contentId }.distinct() }
            .distinctUntilChanged()
            .flatMapLatest { ids -> channelsByIds(ids, ctx.hideAdult) }

        // "Now on TV": favourites of the active playlist, otherwise its first 20 channels.
        val nowOnTv: Flow<Pair<List<Channel>, Boolean>> = favoriteChannels
            .map { favs -> favs.filter { it.playlistId == playlistId } }
            .distinctUntilChanged()
            .flatMapLatest { favs ->
                if (favs.isNotEmpty()) {
                    flowOf(favs.take(NOW_ON_TV) to true)
                } else {
                    // LIMIT query: never loads the whole playlist. Over-fetch a little so hidden
                    // adult channels do not leave the rail short.
                    val fetch = if (ctx.hideAdult) NOW_ON_TV_FETCH else NOW_ON_TV
                    container.channels.observeFirstChannels(playlistId, fetch).map { first ->
                        first.asSequence().filter { !(ctx.hideAdult && it.isAdult) }.take(NOW_ON_TV).toList() to false
                    }
                }
            }

        val channelsPart: Flow<ChannelsPart> =
            combine(nowOnTv, favoriteChannels, recentChannels, recentHistory) { now, favs, recent, history ->
                ChannelsPart(now.first, now.second, favs, recent, history)
            }

        val vodPart: Flow<VodPart> = combine(
            container.history.observeContinueWatching(20),
            container.vod.observeRecentMovies(playlistId, 30),
            container.vod.observeRecentSeries(playlistId, 30),
        ) { cw, movies, series ->
            VodPart(cw.filter { it.contentType != ContentType.Channel && !it.isFinished }, movies, series)
        }

        val epgIds: Flow<List<String>> = channelsPart
            .map { part -> (part.nowOnTv + part.favorites + part.recent).mapNotNull { it.epgId }.distinct() }
            .distinctUntilChanged()

        val nowNext: Flow<Pair<Long, Map<String, NowNext>>> = combine(epgIds, minuteTicker) { ids, now -> ids to now }
            .flatMapLatest { (ids, now) ->
                if (ids.isEmpty()) flowOf(now to emptyMap())
                else container.epg.observeNowNext(ids, now).map { now to it }
            }

        return combine(channelsPart, vodPart, nowNext, container.favorites.observeFavorites(ContentType.Channel)) { ch, vod, nn, favs ->
            buildState(ch, vod, nn.first, nn.second, favs.map { it.contentId }.toSet(), ctx.showNumbers)
        }
    }

    private fun buildState(
        ch: ChannelsPart,
        vod: VodPart,
        nowMillis: Long,
        nowNext: Map<String, NowNext>,
        favoriteIds: Set<String>,
        showNumbers: Boolean,
    ): HomeUiState {
        fun Channel.withNow(): ChannelNow {
            val nn = epgId?.let { nowNext[it] }
            return ChannelNow(this, nn?.now, nn?.next)
        }

        val hero = Recommendations.pickHero(
            history = vod.continueWatching + ch.recentHistory,
            favoriteChannels = ch.favorites,
            recentMovies = vod.movies,
            recentSeries = vod.series,
        )?.let { toHomeHero(it, vod.continueWatching, nowNext) }

        val recentlyAdded = (vod.movies.map { AddedItem.MovieItem(it) } + vod.series.map { AddedItem.SeriesItem(it) })
            .filter { it.addedAtMillis > 0L }
            .sortedByDescending { it.addedAtMillis }
            .take(20)

        return HomeUiState(
            isLoading = false,
            hasPlaylists = true,
            nowMillis = nowMillis,
            hero = hero,
            continueWatching = vod.continueWatching,
            nowOnTv = ch.nowOnTv.map { it.withNow() },
            nowOnTvFromFavorites = ch.nowOnTvFromFavorites,
            favoriteChannels = ch.favorites.map { it.withNow() },
            recentChannels = ch.recent.map { it.withNow() },
            movies = vod.movies,
            series = vod.series,
            recentlyAdded = recentlyAdded,
            favoriteChannelIds = favoriteIds,
            showChannelNumbers = showNumbers,
        )
    }

    private fun toHomeHero(
        hero: Recommendations.Hero,
        continueWatching: List<WatchHistory>,
        nowNext: Map<String, NowNext>,
    ): HomeHero = when (hero) {
        is Recommendations.Hero.MovieHero -> {
            val m = hero.movie
            HomeHero(
                source = hero,
                title = m.name,
                description = m.plot,
                imageUrl = m.posterUrl,
                meta = listOfNotNull(m.year?.toString(), m.genre, m.rating?.let { "★ %.1f".format(it) }).joinToString("  ·  ").ifBlank { null },
            )
        }
        is Recommendations.Hero.SeriesHero -> {
            val s = hero.series
            val resume = continueWatching.firstOrNull { it.contentType == ContentType.Episode && it.parentId == s.id }
            HomeHero(
                source = hero,
                title = s.name,
                description = s.plot,
                imageUrl = s.posterUrl,
                meta = listOfNotNull(s.year?.toString(), s.genre, s.rating?.let { "★ %.1f".format(it) }).joinToString("  ·  ").ifBlank { null },
                resumeEpisodeId = resume?.contentId,
            )
        }
        is Recommendations.Hero.ChannelHero -> {
            val c = hero.channel
            val nn = c.epgId?.let { nowNext[it] }
            HomeHero(
                source = hero,
                title = c.name,
                description = nn?.now?.let { listOfNotNull(it.title, it.description).joinToString("\n") },
                imageUrl = c.logoUrl,
                meta = c.groupName,
            )
        }
    }
}
