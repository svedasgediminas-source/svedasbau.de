package app.novaiptv.core.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object PlaylistTypes {
    const val M3U_URL = "m3u_url"
    const val M3U_FILE = "m3u_file"
    const val XTREAM = "xtream"
}

object VodKinds {
    const val MOVIE = "movie"
    const val SERIES = "series"
}

/**
 * [url] holds the M3U URL, the file URI or the Xtream server URL depending on [type]. Credentials
 * are never stored here: Xtream playlists reference [credentialKey] in the encrypted store.
 * [epgUrls] is newline-joined. [epgUrlsManual] = user set the EPG URLs (refresh keeps them).
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val url: String,
    val displayName: String? = null,
    val credentialKey: String? = null,
    val epgUrls: String = "",
    val epgUrlsManual: Boolean = false,
    val autoRefreshHours: Int = 24,
    val lastRefreshedAt: Long? = null,
    val lastError: String? = null,
    val serverTimezone: String? = null,
    val allowedFormats: String? = null,
    val createdAt: Long = 0,
)

/** Stream columns shared by channels, movies and episodes (prefixed "stream_"). */
data class StreamEmbedded(
    val url: String,
    val userAgent: String? = null,
    val referrer: String? = null,
    val headersJson: String? = null,
    val format: String = "Unknown",
)

/**
 * [groupName] is "" for channels without a group (NOT NULL keeps the group index usable).
 * [nameNormalized] = SearchText.normalize(name) for LIKE search.
 * [resolvedEpgId] is set by the EPG import when the XMLTV id was found by name/case-insensitive
 * match; [manualEpgId] is a user override and survives playlist refreshes.
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "sortIndex"]),
        Index(value = ["playlistId", "groupName", "sortIndex"]),
        Index(value = ["playlistId", "number"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val playlistId: Long,
    val number: Int,
    val name: String,
    val nameNormalized: String,
    val groupName: String,
    val logoUrl: String?,
    val epgId: String?,
    val resolvedEpgId: String? = null,
    val manualEpgId: String? = null,
    @Embedded(prefix = "stream_") val stream: StreamEmbedded,
    val catchupType: String? = null,
    val catchupDays: Int = 0,
    val catchupTemplate: String? = null,
    val isAdult: Boolean = false,
    val sortIndex: Int,
    val extrasJson: String? = null,
)

/** Shared across playlists by XMLTV id; not tied to a playlist row. */
@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["epgId", "startMillis"]),
        Index(value = ["endMillis"]),
    ],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgId: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
    val episodeNum: String? = null,
)

@Entity(
    tableName = "vod_categories",
    primaryKeys = ["playlistId", "kind", "id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VodCategoryEntity(
    val playlistId: Long,
    val kind: String,
    val id: String,
    val name: String,
    val sortIndex: Int,
)

@Entity(
    tableName = "movies",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "sortIndex"]),
        Index(value = ["playlistId", "categoryId", "sortIndex"]),
        Index(value = ["playlistId", "addedAt"]),
    ],
)
data class MovieEntity(
    @PrimaryKey val id: String,
    val playlistId: Long,
    val name: String,
    val nameNormalized: String,
    val categoryId: String?,
    val posterUrl: String?,
    @Embedded(prefix = "stream_") val stream: StreamEmbedded,
    val year: Int? = null,
    val plot: String? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val durationSeconds: Int? = null,
    val addedAt: Long? = null,
    val sortIndex: Int,
)

@Entity(
    tableName = "series",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "sortIndex"]),
        Index(value = ["playlistId", "categoryId", "sortIndex"]),
        Index(value = ["playlistId", "addedAt"]),
    ],
)
data class SeriesEntity(
    @PrimaryKey val id: String,
    val playlistId: Long,
    val name: String,
    val nameNormalized: String,
    val categoryId: String?,
    val posterUrl: String?,
    val plot: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val addedAt: Long? = null,
    val sortIndex: Int,
)

@Entity(
    tableName = "seasons",
    primaryKeys = ["seriesId", "number"],
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SeasonEntity(
    val seriesId: String,
    val number: Int,
    val name: String,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["seriesId", "seasonNumber", "episodeNumber"])],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    @Embedded(prefix = "stream_") val stream: StreamEmbedded,
    val plot: String? = null,
    val durationSeconds: Int? = null,
    val thumbnailUrl: String? = null,
)

/** [type] = ContentType.name. */
@Entity(
    tableName = "favorites",
    primaryKeys = ["type", "contentId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlistId"]), Index(value = ["type", "sortOrder"])],
)
data class FavoriteEntity(
    val type: String,
    val contentId: String,
    val playlistId: Long,
    val addedAt: Long,
    val sortOrder: Int,
)

/** [type] = ContentType.name. */
@Entity(
    tableName = "watch_history",
    primaryKeys = ["type", "contentId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlistId"]), Index(value = ["type", "lastWatchedAt"])],
)
data class WatchHistoryEntity(
    val type: String,
    val contentId: String,
    val playlistId: Long,
    val title: String,
    val imageUrl: String?,
    val positionMillis: Long,
    val durationMillis: Long,
    val lastWatchedAt: Long,
    val parentId: String?,
)

/** Query projections. */
data class ChannelGroupRow(val name: String, val channelCount: Int, val firstIndex: Int)

data class ChannelMappingRow(val id: String, val manualEpgId: String?, val resolvedEpgId: String?)

data class ChannelEpgRow(
    val id: String,
    val playlistId: Long,
    val name: String,
    val epgId: String?,
    val manualEpgId: String?,
    val resolvedEpgId: String?,
    val catchupDays: Int,
    val extrasJson: String?,
)
