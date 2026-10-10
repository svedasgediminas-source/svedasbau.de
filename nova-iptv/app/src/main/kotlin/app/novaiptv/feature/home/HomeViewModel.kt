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
import app.novaiptv.feature.livetv.isAdultChannel
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

/** Movie / series hero (artwork backdrop). */
data class HomeHero(
    val source: Recommendations.Hero,
    val title: String,
    val description: String?,
    val imageUrl: String?,
    val meta: String?,
    /** Unfinished episode to resume when the hero is a series (Play button). */
    val resumeEpisodeId: String? = null,
)

/** LIVE hero: a channel and what it airs right now ([now] null when there is no EPG for it). */
data class LiveHero(val channel: HomeChannel, val now: EpgProgram?, val next: EpgProgram?)

data class HomeUiState(
    val isLoading: Boolean = true,
    val hasPlaylists: Boolean = true,
    val failed: Boolean = false,
    val nowMillis: Long = 0L,
    /** Movie/series hero; when null, [liveHero] is shown instead. */
    val hero: HomeHero? = null,
    val liveHero: LiveHero? = null,
    val continueWatching: List<WatchHistory> = emptyList(),
    val favoriteChannels: List<ChannelNow> = emptyList(),
    val recentChannels: List<ChannelNow> = emptyList(),
    val nowOnTv: List<ChannelNow> = emptyList(),
    val groupRails: List<GroupRailUi> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val recentlyAdded: List<AddedItem> = emptyList(),
    val favoriteChannelIds: Set<String> = emptySet(),
    val showChannelNumbers: Boolean = true,
) {
    val isEmpty: Boolean
        get() = hero == null && liveHero == null && continueWatching.isEmpty() && nowOnTv.isEmpty() &&
            favoriteChannels.isEmpty() && recentChannels.isEmpty() && groupRails.isEmpty() &&
            movies.isEmpty() && series.isEmpty() && recentlyAdded.isEmpty()
}

/** A group rail with now/next resolved; [name] "" = ungrouped. */
data class GroupRailUi(val name: String, val channels: List<ChannelNow>)

private data class HomeContext(val playlistId: Long?, val hideAdult: Boolean, val showNumbers: Boolean)

private data class ChannelsPart(
    val favoriteIds: List<String>,
    /** Full channels, needed by [Recommendations.pickHero]. */
    val favoriteChannels: List<Channel>,
    val favorites: List<HomeChannel>,
    val recent: List<HomeChannel>,
    val recentHistory: List<WatchHistory>,
    val nowOnTv: List<HomeChannel>,
)

private data class VodPart(
    val continueWatching: List<WatchHistory>,
    val movies: List<Movie>,
    val series: List<Series>,
)

private data class Catalog(val channels: ChannelsPart, val groups: List<GroupRail>, val vod: VodPart)

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
            ids.mapNotNull { byId[it] }.filter { !(hideAdult && isAdultChannel(it)) }
        }

    private fun contentFlow(playlistId: Long, ctx: HomeContext): Flow<HomeUiState> {
        val favoriteIds: Flow<List<String>> = container.favorites.observeFavorites(ContentType.Channel)
            .map { favs -> favs.sortedBy { it.sortOrder }.map { it.contentId } }
            .distinctUntilChanged()

        val favorites: Flow<Pair<List<String>, List<Channel>>> = favoriteIds
            .flatMapLatest { ids -> channelsByIds(ids, ctx.hideAdult).map { ids to it } }

        val recentHistory: Flow<List<WatchHistory>> = container.history.observeRecentChannels(20)

        val recentChannels: Flow<List<Channel>> = recentHistory
            .map { list -> list.map { it.contentId }.distinct() }
            .distinctUntilChanged()
            .flatMapLatest { ids -> channelsByIds(ids, ctx.hideAdult) }

        // LIMIT query: never loads the whole playlist. Over-fetch a little so hidden adult channels
        // do not leave the rail short.
        val nowOnTv: Flow<List<HomeChannel>> = container.channels
            .observeFirstChannels(playlistId, if (ctx.hideAdult) HomeLogic.NOW_ON_TV_FETCH else HomeLogic.NOW_ON_TV)
            .map { first -> HomeLogic.visibleChannels(first, ctx.hideAdult, HomeLogic.NOW_ON_TV) }

        val channelsPart: Flow<ChannelsPart> =
            combine(favorites, recentChannels, recentHistory, nowOnTv) { favs, recent, history, now ->
                ChannelsPart(
                    favoriteIds = favs.first,
                    favoriteChannels = favs.second,
                    favorites = favs.second.map { it.toHomeChannel() },
                    recent = recent.map { it.toHomeChannel() },
                    recentHistory = history,
                    nowOnTv = now,
                )
            }

        // One flow per selected group (at most MAX_GROUP_RAILS), never one per card.
        val groupRails: Flow<List<GroupRail>> = container.channels.observeGroups(playlistId)
            .map { groups -> HomeLogic.selectGroups(groups, ctx.hideAdult).map { it.name } }
            .distinctUntilChanged()
            .flatMapLatest { names ->
                if (names.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        names.map { name ->
                            container.channels.observeGroupItems(playlistId, name, limit = 60) // headroom for the adult filter
                                .map { items -> GroupRail(name, HomeLogic.capGroupRail(items, ctx.hideAdult)) }
                                .distinctUntilChanged()
                        },
                    ) { rails -> rails.filter { it.channels.isNotEmpty() } }
                }
            }
            .catch { emit(emptyList()) }

        val vodPart: Flow<VodPart> = combine(
            container.history.observeContinueWatching(20),
            container.vod.observeRecentMovies(playlistId, 30),
            container.vod.observeRecentSeries(playlistId, 30),
        ) { cw, movies, series ->
            VodPart(cw.filter { it.contentType != ContentType.Channel && !it.isFinished }, movies, series)
        }

        val catalog: Flow<Catalog> = combine(channelsPart, groupRails, vodPart) { ch, groups, vod -> Catalog(ch, groups, vod) }

        // One batched now/next query for every channel on Home, re-run each minute (and when the
        // catalogue changes) so programmes advance.
        return combine(catalog, minuteTicker) { cat, now -> cat to now }
            .flatMapLatest { (cat, now) ->
                val ids = HomeLogic.epgIds(
                    listOf(cat.channels.favorites, cat.channels.recent, cat.channels.nowOnTv) + cat.groups.map { it.channels },
                )
                val nowNext: Flow<Map<String, NowNext>> =
                    if (ids.isEmpty()) flowOf(emptyMap())
                    else container.epg.observeNowNext(ids, now).catch { emit(emptyMap()) }
                nowNext.map { nn -> buildState(cat, now, nn, ctx) }
            }
            .flowOn(Dispatchers.Default)
    }

    private fun buildState(cat: Catalog, nowMillis: Long, nowNext: Map<String, NowNext>, ctx: HomeContext): HomeUiState {
        val ch = cat.channels
        val vod = cat.vod
        fun HomeChannel.withNow(): ChannelNow {
            val nn = epgId?.let { nowNext[it] }
            return ChannelNow(this, HomeLogic.currentProgram(nn, nowMillis), nn?.next)
        }

        val picked = Recommendations.pickHero(
            history = vod.continueWatching + ch.recentHistory,
            favoriteChannels = ch.favoriteChannels,
            recentMovies = vod.movies,
            recentSeries = vod.series,
        )
        // Movie/series hero only; a channel pick (or none) becomes the LIVE hero below.
        val vodHero = picked?.let { toHomeHero(it, vod.continueWatching) }
        // No movie/series hero (or a channel won): LIVE hero, preferring favourites, then the most
        // watched channels, then the first channel with a programme on air.
        val liveHero = if (vodHero != null) {
            null
        } else {
            HomeLogic.pickLiveHero(
                favorites = ch.favorites,
                recent = ch.recent,
                history = ch.recentHistory,
                others = ch.nowOnTv + cat.groups.flatMap { it.channels },
                nowNext = nowNext,
                nowMillis = nowMillis,
                hideAdult = ctx.hideAdult,
            )?.let { c ->
                val n = c.withNow()
                LiveHero(c, n.now, n.next)
            }
        }

        val recentlyAdded = (vod.movies.map { AddedItem.MovieItem(it) } + vod.series.map { AddedItem.SeriesItem(it) })
            .filter { it.addedAtMillis > 0L }
            .sortedByDescending { it.addedAtMillis }
            .take(20)

        return HomeUiState(
            isLoading = false,
            hasPlaylists = true,
            nowMillis = nowMillis,
            hero = vodHero,
            liveHero = liveHero,
            continueWatching = vod.continueWatching,
            favoriteChannels = ch.favorites.map { it.withNow() },
            recentChannels = ch.recent.map { it.withNow() },
            nowOnTv = ch.nowOnTv.map { it.withNow() },
            groupRails = cat.groups.map { g -> GroupRailUi(g.name, g.channels.map { it.withNow() }) },
            movies = vod.movies,
            series = vod.series,
            recentlyAdded = recentlyAdded,
            favoriteChannelIds = ch.favoriteIds.toHashSet(),
            showChannelNumbers = ctx.showNumbers,
        )
    }

    private fun toHomeHero(hero: Recommendations.Hero, continueWatching: List<WatchHistory>): HomeHero? = when (hero) {
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
        is Recommendations.Hero.ChannelHero -> null
    }
}
