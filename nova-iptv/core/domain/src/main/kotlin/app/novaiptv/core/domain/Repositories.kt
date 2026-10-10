package app.novaiptv.core.domain

import app.novaiptv.core.common.AppResult
import app.novaiptv.core.model.AppLanguage
import app.novaiptv.core.model.AspectRatioMode
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ChannelGroup
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.Episode
import app.novaiptv.core.model.Favorite
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.NowNext
import app.novaiptv.core.model.Playlist
import app.novaiptv.core.model.Season
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.model.UserPreferences
import app.novaiptv.core.model.VodCategory
import app.novaiptv.core.model.WatchHistory
import app.novaiptv.core.model.XtreamCredentials
import kotlinx.coroutines.flow.Flow

/** Outcome of importing or refreshing a playlist. */
data class ImportSummary(
    val playlistId: Long,
    val channels: Int,
    val groups: Int,
    val movies: Int = 0,
    val series: Int = 0,
    val skippedEntries: Int = 0,
    val warnings: List<String> = emptyList(),
)

sealed interface ImportProgress {
    data object Idle : ImportProgress
    data class Running(val playlistId: Long?, val stage: Stage, val itemsProcessed: Int = 0) : ImportProgress
    data class Done(val summary: ImportSummary) : ImportProgress
    data class Failed(val playlistId: Long?, val error: app.novaiptv.core.common.AppError) : ImportProgress

    enum class Stage { Downloading, Parsing, Saving, LoadingEpg, LoadingVod }
}

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observeImportProgress(): Flow<ImportProgress>
    suspend fun getPlaylist(id: Long): Playlist?

    suspend fun addM3uUrl(name: String, url: String, epgUrl: String?): AppResult<ImportSummary>
    suspend fun addM3uFile(name: String, uri: String, displayName: String, epgUrl: String?): AppResult<ImportSummary>
    suspend fun addXtream(name: String, credentials: XtreamCredentials): AppResult<ImportSummary>

    suspend fun refresh(playlistId: Long): AppResult<ImportSummary>
    suspend fun rename(playlistId: Long, name: String)
    suspend fun setEpgUrls(playlistId: Long, urls: List<String>)
    suspend fun delete(playlistId: Long)
}

interface ChannelRepository {
    fun observeGroups(playlistId: Long): Flow<List<ChannelGroup>>
    /** [group] null = all channels. */
    fun observeChannels(playlistId: Long, group: String?): Flow<List<Channel>>
    fun observeChannelsByIds(ids: List<String>): Flow<List<Channel>>
    suspend fun getChannel(id: String): Channel?
    /** Neighbour in the same list, for channel up/down while in full-screen playback. */
    suspend fun adjacentChannel(current: Channel, group: String?, offset: Int): Channel?
    suspend fun findByNumber(playlistId: Long, number: Int): Channel?
    suspend fun search(playlistId: Long, query: String, limit: Int = 50): List<Channel>
}

interface EpgRepository {
    fun observeNowNext(epgIds: List<String>, nowMillis: Long): Flow<Map<String, NowNext>>
    suspend fun programs(epgId: String, fromMillis: Long, toMillis: Long): List<EpgProgram>
    suspend fun programsForChannels(epgIds: List<String>, fromMillis: Long, toMillis: Long): Map<String, List<EpgProgram>>
    suspend fun refresh(playlistId: Long): AppResult<Int>
    suspend fun searchPrograms(query: String, fromMillis: Long, limit: Int = 50): List<EpgProgram>
    /** Manual mapping when tvg-id does not match the XMLTV id. */
    suspend fun setManualMapping(channelId: String, epgId: String?)
}

interface VodRepository {
    fun observeMovieCategories(playlistId: Long): Flow<List<VodCategory>>
    fun observeMovies(playlistId: Long, categoryId: String?): Flow<List<Movie>>
    fun observeRecentMovies(playlistId: Long, limit: Int = 30): Flow<List<Movie>>
    suspend fun getMovie(id: String): Movie?

    fun observeSeriesCategories(playlistId: Long): Flow<List<VodCategory>>
    fun observeSeries(playlistId: Long, categoryId: String?): Flow<List<Series>>
    fun observeRecentSeries(playlistId: Long, limit: Int = 30): Flow<List<Series>>
    suspend fun getSeries(id: String): Series?
    /** Loads seasons/episodes, fetching from the portal on first access. */
    suspend fun getSeasons(seriesId: String): AppResult<List<Season>>
    suspend fun getEpisode(id: String): Episode?

    suspend fun searchMovies(playlistId: Long, query: String, limit: Int = 50): List<Movie>
    suspend fun searchSeries(playlistId: Long, query: String, limit: Int = 50): List<Series>
}

interface FavoritesRepository {
    fun observeFavorites(type: ContentType? = null): Flow<List<Favorite>>
    fun observeIsFavorite(type: ContentType, contentId: String): Flow<Boolean>
    suspend fun toggle(type: ContentType, contentId: String, playlistId: Long): Boolean
    suspend fun move(type: ContentType, contentId: String, newIndex: Int)
}

interface HistoryRepository {
    fun observeContinueWatching(limit: Int = 20): Flow<List<WatchHistory>>
    fun observeRecentChannels(limit: Int = 20): Flow<List<WatchHistory>>
    suspend fun get(type: ContentType, contentId: String): WatchHistory?
    suspend fun save(entry: WatchHistory)
    suspend fun remove(type: ContentType, contentId: String)
    suspend fun clear()
}

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun update(transform: (UserPreferences) -> UserPreferences)
    suspend fun setLanguage(language: AppLanguage) = update { it.copy(language = language) }
    suspend fun setAspectRatio(mode: AspectRatioMode) = update { it.copy(aspectRatio = mode) }
    suspend fun setActivePlaylist(id: Long?) = update { it.copy(activePlaylistId = id) }
}

/** Encrypted storage for portal credentials. Implementations must never log values. */
interface CredentialStore {
    suspend fun put(credentials: XtreamCredentials): String
    suspend fun get(key: String): XtreamCredentials?
    suspend fun delete(key: String)
}

/** Builds archive URLs. Returns null when the channel/programme is not in the archive window. */
interface CatchupResolver {
    suspend fun resolve(channel: Channel, program: EpgProgram, nowMillis: Long): StreamSource?
}

/**
 * Xtream stream URLs are stored with the placeholders [USER_PLACEHOLDER] / [PASS_PLACEHOLDER]
 * instead of real credentials, so the database never holds passwords. The player calls
 * [resolve] right before playback to substitute values from [CredentialStore].
 */
interface StreamResolver {
    suspend fun resolve(playlistId: Long, stream: StreamSource): StreamSource

    companion object {
        const val USER_PLACEHOLDER = app.novaiptv.core.common.StreamPlaceholders.USER
        const val PASS_PLACEHOLDER = app.novaiptv.core.common.StreamPlaceholders.PASS
    }
}
