package app.novaiptv.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.novaiptv.core.data.logic.Reorder
import kotlinx.coroutines.flow.Flow

/** SQLite's bind-parameter limit is 999 on older Android versions. */
const val SQL_IN_CHUNK = 500
const val INSERT_CHUNK = 500
const val PROGRAM_BATCH = 1000

/** Columns of [ChannelListRow]; names must match its constructor parameters. */
const val CHANNEL_LIST_COLUMNS =
    "id, playlistId, number, name, groupName, logoUrl, epgId, resolvedEpgId, manualEpgId, " +
        "catchupType, catchupDays, isAdult"

/** Room projection of `channels` for long lists (see [ChannelDao.observeListItems]). */
data class ChannelListRow(
    val id: String,
    val playlistId: Long,
    val number: Int,
    val name: String,
    val groupName: String,
    val logoUrl: String?,
    val epgId: String?,
    val resolvedEpgId: String?,
    val manualEpgId: String?,
    val catchupType: String?,
    val catchupDays: Int,
    val isAdult: Boolean,
)

@Dao
abstract class PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    abstract fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY id")
    abstract suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT COUNT(*) FROM playlists")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(playlist: PlaylistEntity): Long

    /** Never use REPLACE for playlists: it would cascade-delete channels, favourites and history. */
    @Update
    abstract suspend fun update(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("UPDATE playlists SET lastError = :error WHERE id = :id")
    abstract suspend fun setLastError(id: Long, error: String?)

    @Query("UPDATE playlists SET epgUrls = :urls, epgUrlsManual = :manual WHERE id = :id")
    abstract suspend fun setEpgUrls(id: Long, urls: String, manual: Boolean)

    @Query("DELETE FROM episodes WHERE seriesId IN (SELECT id FROM series WHERE playlistId = :id)")
    abstract suspend fun deleteEpisodes(id: Long)

    @Query("DELETE FROM seasons WHERE seriesId IN (SELECT id FROM series WHERE playlistId = :id)")
    abstract suspend fun deleteSeasons(id: Long)

    @Query("DELETE FROM series WHERE playlistId = :id")
    abstract suspend fun deleteSeries(id: Long)

    @Query("DELETE FROM movies WHERE playlistId = :id")
    abstract suspend fun deleteMovies(id: Long)

    @Query("DELETE FROM vod_categories WHERE playlistId = :id")
    abstract suspend fun deleteCategories(id: Long)

    @Query("DELETE FROM channels WHERE playlistId = :id")
    abstract suspend fun deleteChannels(id: Long)

    @Query("DELETE FROM favorites WHERE playlistId = :id")
    abstract suspend fun deleteFavorites(id: Long)

    @Query("DELETE FROM watch_history WHERE playlistId = :id")
    abstract suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deleteRow(id: Long)

    /** Explicit deletes (does not rely on foreign_keys being enabled), in one transaction. */
    @Transaction
    open suspend fun deleteFully(id: Long) {
        deleteEpisodes(id)
        deleteSeasons(id)
        deleteSeries(id)
        deleteMovies(id)
        deleteCategories(id)
        deleteChannels(id)
        deleteFavorites(id)
        deleteHistory(id)
        deleteRow(id)
    }
}

@Dao
abstract class ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY sortIndex")
    abstract fun observeAll(playlistId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND groupName = :group ORDER BY sortIndex")
    abstract fun observeByGroup(playlistId: Long, group: String): Flow<List<ChannelEntity>>

    /** Slim list rows: only the columns a list row needs (no stream, template or extras JSON). */
    @Query("SELECT $CHANNEL_LIST_COLUMNS FROM channels WHERE playlistId = :playlistId ORDER BY sortIndex")
    abstract fun observeListItems(playlistId: Long): Flow<List<ChannelListRow>>

    @Query(
        "SELECT $CHANNEL_LIST_COLUMNS FROM channels WHERE playlistId = :playlistId AND groupName = :group " +
            "ORDER BY sortIndex",
    )
    abstract fun observeListItemsByGroup(playlistId: Long, group: String): Flow<List<ChannelListRow>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY sortIndex LIMIT :limit")
    abstract fun observeFirst(playlistId: Long, limit: Int): Flow<List<ChannelEntity>>

    /** Matches the effective EPG id (see `effectiveEpgId`: manual, then resolved, then tvg-id; blanks skipped). */
    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND " +
            "COALESCE(" +
            "CASE WHEN TRIM(manualEpgId) <> '' THEN manualEpgId END, " +
            "CASE WHEN TRIM(resolvedEpgId) <> '' THEN resolvedEpgId END, " +
            "CASE WHEN TRIM(epgId) <> '' THEN epgId END" +
            ") IN (:epgIds) ORDER BY sortIndex",
    )
    abstract suspend fun findByEffectiveEpgIds(playlistId: Long, epgIds: List<String>): List<ChannelEntity>

    @Query(
        "SELECT groupName AS name, COUNT(*) AS channelCount, MIN(sortIndex) AS firstIndex " +
            "FROM channels WHERE playlistId = :playlistId GROUP BY groupName ORDER BY firstIndex",
    )
    abstract fun observeGroups(playlistId: Long): Flow<List<ChannelGroupRow>>

    @Query("SELECT * FROM channels WHERE id IN (:ids)")
    abstract fun observeByIds(ids: List<String>): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    abstract suspend fun get(id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND number = :number ORDER BY sortIndex LIMIT 1")
    abstract suspend fun findByNumber(playlistId: Long, number: Int): ChannelEntity?

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND nameNormalized LIKE :pattern ESCAPE '\\' " +
            "ORDER BY CASE WHEN nameNormalized LIKE :prefix ESCAPE '\\' THEN 0 ELSE 1 END, sortIndex LIMIT :limit",
    )
    abstract suspend fun search(playlistId: Long, pattern: String, prefix: String, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND sortIndex > :sortIndex ORDER BY sortIndex ASC LIMIT 1 OFFSET :skip")
    abstract suspend fun after(playlistId: Long, sortIndex: Int, skip: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND sortIndex < :sortIndex ORDER BY sortIndex DESC LIMIT 1 OFFSET :skip")
    abstract suspend fun before(playlistId: Long, sortIndex: Int, skip: Int): ChannelEntity?

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND groupName = :group AND sortIndex > :sortIndex " +
            "ORDER BY sortIndex ASC LIMIT 1 OFFSET :skip",
    )
    abstract suspend fun afterInGroup(playlistId: Long, group: String, sortIndex: Int, skip: Int): ChannelEntity?

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND groupName = :group AND sortIndex < :sortIndex " +
            "ORDER BY sortIndex DESC LIMIT 1 OFFSET :skip",
    )
    abstract suspend fun beforeInGroup(playlistId: Long, group: String, sortIndex: Int, skip: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY sortIndex ASC LIMIT 1")
    abstract suspend fun first(playlistId: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY sortIndex DESC LIMIT 1")
    abstract suspend fun last(playlistId: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND groupName = :group ORDER BY sortIndex ASC LIMIT 1")
    abstract suspend fun firstInGroup(playlistId: Long, group: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND groupName = :group ORDER BY sortIndex DESC LIMIT 1")
    abstract suspend fun lastInGroup(playlistId: Long, group: String): ChannelEntity?

    @Query("SELECT COUNT(DISTINCT groupName) FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun groupCount(playlistId: Long): Int

    @Query(
        "SELECT id, playlistId, name, epgId, manualEpgId, resolvedEpgId, catchupDays, extrasJson " +
            "FROM channels WHERE playlistId = :playlistId",
    )
    abstract suspend fun epgRows(playlistId: Long): List<ChannelEpgRow>

    @Query("SELECT COALESCE(MAX(catchupDays), 0) FROM channels")
    abstract suspend fun maxCatchupDays(): Int

    @Query("UPDATE channels SET manualEpgId = :epgId WHERE id = :id")
    abstract suspend fun setManualEpgId(id: String, epgId: String?)

    @Query("UPDATE channels SET resolvedEpgId = :epgId WHERE id = :id")
    abstract suspend fun setResolvedEpgId(id: String, epgId: String?)

    @Transaction
    open suspend fun setResolvedEpgIds(values: Map<String, String?>) {
        for ((id, epgId) in values) setResolvedEpgId(id, epgId)
    }

    @Query(
        "SELECT id, manualEpgId, resolvedEpgId FROM channels " +
            "WHERE playlistId = :playlistId AND (manualEpgId IS NOT NULL OR resolvedEpgId IS NOT NULL)",
    )
    abstract suspend fun mappings(playlistId: Long): List<ChannelMappingRow>

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    abstract suspend fun deleteForPlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(channels: List<ChannelEntity>)

    /**
     * Replaces all channels of a playlist in one transaction. EPG mappings (manual overrides and
     * name-resolved ids) of channels that still exist are carried over.
     */
    @Transaction
    open suspend fun replaceForPlaylist(playlistId: Long, channels: List<ChannelEntity>) {
        val kept = mappings(playlistId).associateBy { it.id }
        deleteForPlaylist(playlistId)
        for (chunk in channels.chunked(INSERT_CHUNK)) {
            insertAll(
                chunk.map { c ->
                    val m = kept[c.id]
                    if (m == null) c else c.copy(manualEpgId = m.manualEpgId, resolvedEpgId = m.resolvedEpgId)
                },
            )
        }
    }
}

@Dao
abstract class ProgramDao {
    @Query(
        "SELECT * FROM epg_programs WHERE epgId IN (:epgIds) AND endMillis > :fromMillis AND startMillis < :toMillis " +
            "ORDER BY epgId, startMillis",
    )
    abstract fun observeRange(epgIds: List<String>, fromMillis: Long, toMillis: Long): Flow<List<ProgramEntity>>

    @Query(
        "SELECT * FROM epg_programs WHERE epgId IN (:epgIds) AND endMillis > :fromMillis AND startMillis < :toMillis " +
            "ORDER BY epgId, startMillis",
    )
    abstract suspend fun range(epgIds: List<String>, fromMillis: Long, toMillis: Long): List<ProgramEntity>

    @Query(
        "SELECT * FROM epg_programs WHERE title LIKE :pattern ESCAPE '\\' AND endMillis > :fromMillis " +
            "ORDER BY startMillis LIMIT :limit",
    )
    abstract suspend fun search(pattern: String, fromMillis: Long, limit: Int): List<ProgramEntity>

    @Query("DELETE FROM epg_programs WHERE endMillis < :beforeMillis")
    abstract suspend fun deleteEndedBefore(beforeMillis: Long)

    @Query(
        "DELETE FROM epg_programs WHERE epgId NOT IN (" +
            "SELECT epgId FROM channels WHERE epgId IS NOT NULL " +
            "UNION SELECT resolvedEpgId FROM channels WHERE resolvedEpgId IS NOT NULL " +
            "UNION SELECT manualEpgId FROM channels WHERE manualEpgId IS NOT NULL)",
    )
    abstract suspend fun deleteOrphans()

    // Blocking variants: used from inside the streaming XMLTV parser callbacks (already on IO).

    @Query("DELETE FROM epg_programs WHERE epgId IN (:epgIds)")
    abstract fun deleteForIdsBlocking(epgIds: List<String>)

    @Insert
    abstract fun insertBlocking(programs: List<ProgramEntity>)

    /** Deletes the old programmes of [clearEpgIds], then inserts [programs], atomically. */
    @Transaction
    open fun replaceBatchBlocking(clearEpgIds: List<String>, programs: List<ProgramEntity>) {
        for (chunk in clearEpgIds.chunked(SQL_IN_CHUNK)) deleteForIdsBlocking(chunk)
        if (programs.isNotEmpty()) insertBlocking(programs)
    }
}

@Dao
abstract class VodDao {
    @Query("SELECT * FROM vod_categories WHERE playlistId = :playlistId AND kind = :kind ORDER BY sortIndex")
    abstract fun observeCategories(playlistId: Long, kind: String): Flow<List<VodCategoryEntity>>

    // Movies

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId ORDER BY sortIndex")
    abstract fun observeMovies(playlistId: Long): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND categoryId = :categoryId ORDER BY sortIndex")
    abstract fun observeMoviesInCategory(playlistId: Long, categoryId: String): Flow<List<MovieEntity>>

    @Query(
        "SELECT * FROM movies WHERE playlistId = :playlistId " +
            "ORDER BY addedAt IS NULL, addedAt DESC, sortIndex LIMIT :limit",
    )
    abstract fun observeRecentMovies(playlistId: Long, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE id = :id")
    abstract suspend fun getMovie(id: String): MovieEntity?

    @Query(
        "SELECT * FROM movies WHERE playlistId = :playlistId AND nameNormalized LIKE :pattern ESCAPE '\\' " +
            "ORDER BY CASE WHEN nameNormalized LIKE :prefix ESCAPE '\\' THEN 0 ELSE 1 END, sortIndex LIMIT :limit",
    )
    abstract suspend fun searchMovies(playlistId: Long, pattern: String, prefix: String, limit: Int): List<MovieEntity>

    // Series

    @Query("SELECT * FROM series WHERE playlistId = :playlistId ORDER BY sortIndex")
    abstract fun observeSeries(playlistId: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND categoryId = :categoryId ORDER BY sortIndex")
    abstract fun observeSeriesInCategory(playlistId: Long, categoryId: String): Flow<List<SeriesEntity>>

    @Query(
        "SELECT * FROM series WHERE playlistId = :playlistId " +
            "ORDER BY addedAt IS NULL, addedAt DESC, sortIndex LIMIT :limit",
    )
    abstract fun observeRecentSeries(playlistId: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE id = :id")
    abstract suspend fun getSeries(id: String): SeriesEntity?

    @Query(
        "SELECT * FROM series WHERE playlistId = :playlistId AND nameNormalized LIKE :pattern ESCAPE '\\' " +
            "ORDER BY CASE WHEN nameNormalized LIKE :prefix ESCAPE '\\' THEN 0 ELSE 1 END, sortIndex LIMIT :limit",
    )
    abstract suspend fun searchSeries(playlistId: Long, pattern: String, prefix: String, limit: Int): List<SeriesEntity>

    // Seasons / episodes cache

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY number")
    abstract suspend fun seasons(seriesId: String): List<SeasonEntity>

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY seasonNumber, episodeNumber")
    abstract suspend fun episodes(seriesId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    abstract suspend fun getEpisode(id: String): EpisodeEntity?

    // Writes

    @Query("DELETE FROM vod_categories WHERE playlistId = :playlistId AND kind = :kind")
    abstract suspend fun deleteCategories(playlistId: Long, kind: String)

    @Query("DELETE FROM movies WHERE playlistId = :playlistId")
    abstract suspend fun deleteMovies(playlistId: Long)

    @Query("DELETE FROM episodes WHERE seriesId IN (SELECT id FROM series WHERE playlistId = :playlistId)")
    abstract suspend fun deleteEpisodesOfPlaylist(playlistId: Long)

    @Query("DELETE FROM seasons WHERE seriesId IN (SELECT id FROM series WHERE playlistId = :playlistId)")
    abstract suspend fun deleteSeasonsOfPlaylist(playlistId: Long)

    @Query("DELETE FROM series WHERE playlistId = :playlistId")
    abstract suspend fun deleteSeries(playlistId: Long)

    @Query("DELETE FROM seasons WHERE seriesId = :seriesId")
    abstract suspend fun deleteSeasons(seriesId: String)

    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    abstract suspend fun deleteEpisodes(seriesId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertCategories(items: List<VodCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMovies(items: List<MovieEntity>)

    @Upsert
    abstract suspend fun upsertSeries(items: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSeasons(items: List<SeasonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEpisodes(items: List<EpisodeEntity>)

    @Transaction
    open suspend fun replaceMovies(playlistId: Long, categories: List<VodCategoryEntity>, movies: List<MovieEntity>) {
        deleteCategories(playlistId, VodKinds.MOVIE)
        deleteMovies(playlistId)
        for (chunk in categories.chunked(INSERT_CHUNK)) insertCategories(chunk)
        for (chunk in movies.chunked(INSERT_CHUNK)) insertMovies(chunk)
    }

    /** Replaces the series catalogue; cached seasons/episodes are dropped (they may be stale). */
    @Transaction
    open suspend fun replaceSeries(playlistId: Long, categories: List<VodCategoryEntity>, series: List<SeriesEntity>) {
        deleteCategories(playlistId, VodKinds.SERIES)
        deleteEpisodesOfPlaylist(playlistId)
        deleteSeasonsOfPlaylist(playlistId)
        deleteSeries(playlistId)
        for (chunk in categories.chunked(INSERT_CHUNK)) insertCategories(chunk)
        for (chunk in series.chunked(INSERT_CHUNK)) upsertSeries(chunk)
    }

    @Transaction
    open suspend fun replaceSeasons(seriesId: String, seasons: List<SeasonEntity>, episodes: List<EpisodeEntity>) {
        deleteEpisodes(seriesId)
        deleteSeasons(seriesId)
        for (chunk in seasons.chunked(INSERT_CHUNK)) insertSeasons(chunk)
        for (chunk in episodes.chunked(INSERT_CHUNK)) insertEpisodes(chunk)
    }
}

@Dao
abstract class FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY sortOrder, addedAt")
    abstract fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY sortOrder, addedAt")
    abstract fun observeByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND contentId = :contentId)")
    abstract fun observeExists(type: String, contentId: String): Flow<Boolean>

    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY sortOrder, addedAt")
    abstract suspend fun listByType(type: String): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE type = :type AND contentId = :contentId")
    abstract suspend fun get(type: String, contentId: String): FavoriteEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM favorites WHERE type = :type")
    abstract suspend fun maxSortOrder(type: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE type = :type AND contentId = :contentId")
    abstract suspend fun delete(type: String, contentId: String)

    @Query("UPDATE favorites SET sortOrder = :sortOrder WHERE type = :type AND contentId = :contentId")
    abstract suspend fun setSortOrder(type: String, contentId: String, sortOrder: Int)

    /** Returns true when the item is a favourite after the call. */
    @Transaction
    open suspend fun toggle(type: String, contentId: String, playlistId: Long, nowMillis: Long): Boolean {
        return if (get(type, contentId) != null) {
            delete(type, contentId)
            false
        } else {
            insert(FavoriteEntity(type, contentId, playlistId, nowMillis, maxSortOrder(type) + 1))
            true
        }
    }

    @Transaction
    open suspend fun move(type: String, contentId: String, newIndex: Int) {
        val ids = listByType(type).map { it.contentId }
        val reordered = Reorder.move(ids, contentId, newIndex)
        reordered.forEachIndexed { index, id -> setSortOrder(type, id, index) }
    }
}

@Dao
abstract class HistoryDao {
    @Query("SELECT * FROM watch_history WHERE type != :channelType ORDER BY lastWatchedAt DESC LIMIT :limit")
    abstract fun observeNonChannel(channelType: String, limit: Int): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE type = :channelType ORDER BY lastWatchedAt DESC LIMIT :limit")
    abstract fun observeByType(channelType: String, limit: Int): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE type = :type AND contentId = :contentId")
    abstract suspend fun get(type: String, contentId: String): WatchHistoryEntity?

    @Upsert
    abstract suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE type = :type AND contentId = :contentId")
    abstract suspend fun delete(type: String, contentId: String)

    @Query("DELETE FROM watch_history")
    abstract suspend fun clear()
}
