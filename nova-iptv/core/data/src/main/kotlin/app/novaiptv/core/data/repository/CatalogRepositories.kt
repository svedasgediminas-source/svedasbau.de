package app.novaiptv.core.data.repository

import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.data.db.ChannelDao
import app.novaiptv.core.data.db.ChannelEntity
import app.novaiptv.core.data.db.PlaylistDao
import app.novaiptv.core.data.db.PlaylistTypes
import app.novaiptv.core.data.db.SQL_IN_CHUNK
import app.novaiptv.core.data.db.SeasonEntity
import app.novaiptv.core.data.db.VodDao
import app.novaiptv.core.data.db.VodKinds
import app.novaiptv.core.data.logic.SearchText
import app.novaiptv.core.data.mapper.assembleSeasons
import app.novaiptv.core.data.mapper.toEntity
import app.novaiptv.core.data.mapper.toListItem
import app.novaiptv.core.data.mapper.toModel
import app.novaiptv.core.domain.ChannelListItem
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.CredentialStore
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ChannelGroup
import app.novaiptv.core.model.Episode
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.Season
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.VodCategory
import app.novaiptv.data.xtream.XtreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs an `IN (:ids)` query per chunk of [SQL_IN_CHUNK] ids and merges the results. */
internal inline fun <reified E> chunkedInFlow(
    ids: List<String>,
    crossinline query: (List<String>) -> Flow<List<E>>,
): Flow<List<E>> {
    val distinct = ids.distinct()
    if (distinct.isEmpty()) return flowOf(emptyList())
    val chunks = distinct.chunked(SQL_IN_CHUNK)
    if (chunks.size == 1) return query(chunks[0])
    return combine(chunks.map { query(it) }) { parts -> parts.flatMap { it } }
}

class ChannelRepositoryImpl(
    private val dao: ChannelDao,
) : ChannelRepository {

    override fun observeGroups(playlistId: Long): Flow<List<ChannelGroup>> =
        dao.observeGroups(playlistId).map { rows -> rows.map { it.toModel(playlistId) } }.flowOn(Dispatchers.Default)

    override fun observeChannels(playlistId: Long, group: String?): Flow<List<Channel>> =
        (if (group == null) dao.observeAll(playlistId) else dao.observeByGroup(playlistId, group))
            .map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.Default)

    override fun observeGroupItems(playlistId: Long, group: String, limit: Int): Flow<List<ChannelListItem>> =
        dao.observeListItemsByGroupLimited(playlistId, group, limit.coerceAtLeast(0))
            .map { rows ->
                val groupPool = HashMap<String, String>()
                rows.map { it.toListItem(groupPool) }
            }.flowOn(Dispatchers.Default)

    override fun observeChannelItems(playlistId: Long, group: String?): Flow<List<ChannelListItem>> =
        (if (group == null) dao.observeListItems(playlistId) else dao.observeListItemsByGroup(playlistId, group))
            .map { rows ->
                val groupPool = HashMap<String, String>()
                rows.map { it.toListItem(groupPool) }
            }.flowOn(Dispatchers.Default)

    override fun observeFirstChannels(playlistId: Long, limit: Int): Flow<List<Channel>> =
        dao.observeFirst(playlistId, limit.coerceAtLeast(0))
            .map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.Default)

    /** Playlist order within each chunk; all channels sharing one EPG id land in the same chunk. */
    override suspend fun findByEpgIds(playlistId: Long, epgIds: List<String>): List<Channel> =
        withContext(Dispatchers.IO) {
            val ids = epgIds.filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) return@withContext emptyList()
            ids.chunked(SQL_IN_CHUNK).flatMap { chunk -> dao.findByEffectiveEpgIds(playlistId, chunk) }.map { it.toModel() }
        }

    /** Results follow the order of [ids] (e.g. favourites order). */
    override fun observeChannelsByIds(ids: List<String>): Flow<List<Channel>> =
        chunkedInFlow<ChannelEntity>(ids) { dao.observeByIds(it) }.map { rows ->
            val byId = rows.associateBy { it.id }
            ids.distinct().mapNotNull { byId[it]?.toModel() }
        }.flowOn(Dispatchers.Default)

    override suspend fun getChannel(id: String): Channel? = withContext(Dispatchers.IO) { dao.get(id)?.toModel() }

    override suspend fun adjacentChannel(current: Channel, group: String?, offset: Int): Channel? =
        withContext(Dispatchers.IO) {
            if (offset == 0) return@withContext current
            val entity = dao.get(current.id) ?: return@withContext null
            val pid = entity.playlistId
            val skip = kotlin.math.abs(offset) - 1
            val found = if (group == null) {
                if (offset > 0) dao.after(pid, entity.sortIndex, skip) ?: dao.first(pid)
                else dao.before(pid, entity.sortIndex, skip) ?: dao.last(pid)
            } else {
                if (offset > 0) dao.afterInGroup(pid, group, entity.sortIndex, skip) ?: dao.firstInGroup(pid, group)
                else dao.beforeInGroup(pid, group, entity.sortIndex, skip) ?: dao.lastInGroup(pid, group)
            }
            found?.toModel()
        }

    override suspend fun findByNumber(playlistId: Long, number: Int): Channel? =
        withContext(Dispatchers.IO) { dao.findByNumber(playlistId, number)?.toModel() }

    override suspend fun search(playlistId: Long, query: String, limit: Int): List<Channel> =
        withContext(Dispatchers.IO) {
            val pattern = SearchText.containsPattern(query) ?: return@withContext emptyList()
            dao.search(playlistId, pattern, SearchText.prefixPattern(query), limit.coerceAtLeast(0)).map { it.toModel() }
        }
}

class VodRepositoryImpl(
    private val vodDao: VodDao,
    private val playlistDao: PlaylistDao,
    private val credentials: CredentialStore,
    private val xtream: XtreamClient,
) : VodRepository {

    private val seasonsMutex = Mutex()

    override fun observeMovieCategories(playlistId: Long): Flow<List<VodCategory>> =
        vodDao.observeCategories(playlistId, VodKinds.MOVIE).map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.Default)

    override fun observeMovies(playlistId: Long, categoryId: String?): Flow<List<Movie>> =
        (if (categoryId == null) vodDao.observeMovies(playlistId) else vodDao.observeMoviesInCategory(playlistId, categoryId))
            .map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.Default)

    override fun observeRecentMovies(playlistId: Long, limit: Int): Flow<List<Movie>> =
        vodDao.observeRecentMovies(playlistId, limit.coerceAtLeast(0)).map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.Default)

    override suspend fun getMovie(id: String): Movie? = withContext(Dispatchers.IO) { vodDao.getMovie(id)?.toModel() }

    override fun observeSeriesCategories(playlistId: Long): Flow<List<VodCategory>> =
        vodDao.observeCategories(playlistId, VodKinds.SERIES).map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.Default)

    override fun observeSeries(playlistId: Long, categoryId: String?): Flow<List<Series>> =
        (if (categoryId == null) vodDao.observeSeries(playlistId) else vodDao.observeSeriesInCategory(playlistId, categoryId))
            .map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.Default)

    override fun observeRecentSeries(playlistId: Long, limit: Int): Flow<List<Series>> =
        vodDao.observeRecentSeries(playlistId, limit.coerceAtLeast(0)).map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.Default)

    override suspend fun getSeries(id: String): Series? = withContext(Dispatchers.IO) { vodDao.getSeries(id)?.toModel() }

    override suspend fun getSeasons(seriesId: String): AppResult<List<Season>> = withContext(Dispatchers.IO) {
        try {
            seasonsMutex.withLock { loadSeasons(seriesId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            AppResult.Err(AppError.Unknown(e.javaClass.simpleName))
        }
    }

    private suspend fun loadSeasons(seriesId: String): AppResult<List<Season>> {
        val cachedSeasons = vodDao.seasons(seriesId)
        val cachedEpisodes = vodDao.episodes(seriesId)
        if (cachedSeasons.isNotEmpty() || cachedEpisodes.isNotEmpty()) {
            return AppResult.Ok(assembleSeasons(cachedSeasons, cachedEpisodes))
        }
        val series = vodDao.getSeries(seriesId) ?: return AppResult.Err(AppError.InvalidInput("series"))
        val playlist = playlistDao.get(series.playlistId) ?: return AppResult.Err(AppError.InvalidInput("playlist"))
        if (playlist.type != PlaylistTypes.XTREAM) return AppResult.Ok(emptyList())
        val creds = playlist.credentialKey?.let { credentials.get(it) } ?: return AppResult.Err(AppError.AuthFailed)
        return when (val r = xtream.seasons(creds, playlist.id, seriesId)) {
            is AppResult.Err -> r
            is AppResult.Ok -> {
                val seasons = r.value.map { s ->
                    s.copy(seriesId = seriesId, episodes = s.episodes.map { it.copy(seriesId = seriesId, seasonNumber = s.number) })
                }
                vodDao.replaceSeasons(
                    seriesId,
                    seasons.map { SeasonEntity(seriesId = seriesId, number = it.number, name = it.name) },
                    seasons.flatMap { s -> s.episodes.map { it.toEntity() } },
                )
                AppResult.Ok(seasons)
            }
        }
    }

    override suspend fun getEpisode(id: String): Episode? = withContext(Dispatchers.IO) { vodDao.getEpisode(id)?.toModel() }

    override suspend fun searchMovies(playlistId: Long, query: String, limit: Int): List<Movie> =
        withContext(Dispatchers.IO) {
            val pattern = SearchText.containsPattern(query) ?: return@withContext emptyList()
            vodDao.searchMovies(playlistId, pattern, SearchText.prefixPattern(query), limit.coerceAtLeast(0)).map { it.toModel() }
        }

    override suspend fun searchSeries(playlistId: Long, query: String, limit: Int): List<Series> =
        withContext(Dispatchers.IO) {
            val pattern = SearchText.containsPattern(query) ?: return@withContext emptyList()
            vodDao.searchSeries(playlistId, pattern, SearchText.prefixPattern(query), limit.coerceAtLeast(0)).map { it.toModel() }
        }
}
