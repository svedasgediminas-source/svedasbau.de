package app.novaiptv.core.data.repository

import android.database.SQLException
import app.novaiptv.core.common.Clock
import app.novaiptv.core.data.db.FavoriteDao
import app.novaiptv.core.data.db.HistoryDao
import app.novaiptv.core.data.logic.ContinueWatching
import app.novaiptv.core.data.mapper.toEntity
import app.novaiptv.core.data.mapper.toModel
import app.novaiptv.core.domain.FavoritesRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.Favorite
import app.novaiptv.core.model.WatchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class FavoritesRepositoryImpl(
    private val dao: FavoriteDao,
    private val clock: Clock,
) : FavoritesRepository {

    override fun observeFavorites(type: ContentType?): Flow<List<Favorite>> =
        (if (type == null) dao.observeAll() else dao.observeByType(type.name))
            .map { list -> list.mapNotNull { it.toModel() } }.flowOn(Dispatchers.Default)

    override fun observeIsFavorite(type: ContentType, contentId: String): Flow<Boolean> =
        dao.observeExists(type.name, contentId).distinctUntilChanged()

    override suspend fun toggle(type: ContentType, contentId: String, playlistId: Long): Boolean =
        withContext(Dispatchers.IO) { dao.toggle(type.name, contentId, playlistId, clock.nowMillis()) }

    override suspend fun move(type: ContentType, contentId: String, newIndex: Int) {
        withContext(Dispatchers.IO) { dao.move(type.name, contentId, newIndex) }
    }
}

class HistoryRepositoryImpl(
    private val dao: HistoryDao,
) : HistoryRepository {

    override fun observeContinueWatching(limit: Int): Flow<List<WatchHistory>> =
        // Over-fetch: finished items and older episodes of the same series are filtered in Kotlin.
        dao.observeNonChannel(ContentType.Channel.name, (limit.coerceAtLeast(1) * 5).coerceAtMost(500))
            .map { rows -> ContinueWatching.select(rows.mapNotNull { it.toModel() }, limit) }.flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    override fun observeRecentChannels(limit: Int): Flow<List<WatchHistory>> =
        dao.observeByType(ContentType.Channel.name, limit.coerceAtLeast(0))
            .map { rows -> rows.mapNotNull { it.toModel() } }.flowOn(Dispatchers.Default)

    override suspend fun get(type: ContentType, contentId: String): WatchHistory? =
        withContext(Dispatchers.IO) { dao.get(type.name, contentId)?.toModel() }

    override suspend fun save(entry: WatchHistory) {
        withContext(Dispatchers.IO) {
            try {
                dao.upsert(entry.toEntity())
            } catch (e: SQLException) {
                // Playlist removed while playing (foreign key): nothing to remember.
            }
        }
    }

    override suspend fun remove(type: ContentType, contentId: String) {
        withContext(Dispatchers.IO) { dao.delete(type.name, contentId) }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { dao.clear() }
    }
}
