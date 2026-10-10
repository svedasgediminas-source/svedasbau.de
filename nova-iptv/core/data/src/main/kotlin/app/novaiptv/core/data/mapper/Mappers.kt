package app.novaiptv.core.data.mapper

import app.novaiptv.core.data.db.ChannelEntity
import app.novaiptv.core.data.db.ChannelEpgRow
import app.novaiptv.core.data.db.ChannelGroupRow
import app.novaiptv.core.data.db.ChannelListRow
import app.novaiptv.core.data.db.EpisodeEntity
import app.novaiptv.core.data.db.FavoriteEntity
import app.novaiptv.core.data.db.MovieEntity
import app.novaiptv.core.data.db.PlaylistEntity
import app.novaiptv.core.data.db.PlaylistTypes
import app.novaiptv.core.data.db.ProgramEntity
import app.novaiptv.core.data.db.SeasonEntity
import app.novaiptv.core.data.db.SeriesEntity
import app.novaiptv.core.data.db.StreamEmbedded
import app.novaiptv.core.data.db.VodCategoryEntity
import app.novaiptv.core.data.db.WatchHistoryEntity
import app.novaiptv.core.data.logic.MiniJson
import app.novaiptv.core.data.logic.SearchText
import app.novaiptv.core.data.logic.effectiveEpgId
import app.novaiptv.core.domain.ChannelListItem
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ChannelGroup
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.Episode
import app.novaiptv.core.model.Favorite
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.Playlist
import app.novaiptv.core.model.PlaylistSource
import app.novaiptv.core.model.Season
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.model.VodCategory
import app.novaiptv.core.model.WatchHistory

// ---- helpers -----------------------------------------------------------------------------------

internal inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    if (name == null) null else enumValues<E>().firstOrNull { it.name == name }

fun joinUrls(urls: List<String>): String = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")

fun splitUrls(joined: String?): List<String> =
    joined?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

fun joinList(values: List<String>): String? = if (values.isEmpty()) null else values.joinToString(",")

// ---- stream ------------------------------------------------------------------------------------

fun StreamSource.toEmbedded(): StreamEmbedded = StreamEmbedded(
    url = url,
    userAgent = userAgent,
    referrer = referrer,
    headersJson = MiniJson.encodeOrNull(headers),
    format = mimeTypeHint.name,
)

fun StreamEmbedded.toModel(): StreamSource = StreamSource(
    url = url,
    userAgent = userAgent,
    referrer = referrer,
    headers = MiniJson.decode(headersJson),
    mimeTypeHint = enumOrNull<StreamFormat>(format) ?: StreamFormat.Unknown,
)

// ---- playlist ----------------------------------------------------------------------------------

fun PlaylistEntity.toModel(): Playlist = Playlist(
    id = id,
    name = name,
    source = when (type) {
        PlaylistTypes.M3U_FILE -> PlaylistSource.M3uFile(uri = url, displayName = displayName ?: name)
        PlaylistTypes.XTREAM -> PlaylistSource.Xtream(serverUrl = url, credentialKey = credentialKey.orEmpty())
        else -> PlaylistSource.M3uUrl(url = url)
    },
    epgUrls = splitUrls(epgUrls),
    autoRefreshHours = autoRefreshHours,
    lastRefreshedAtMillis = lastRefreshedAt,
    lastError = lastError,
)

// ---- channels ----------------------------------------------------------------------------------

/** [sortIndex] = position in the source playlist. The domain epgId is stored as the raw tvg-id. */
fun Channel.toEntity(sortIndex: Int): ChannelEntity {
    val supported = catchup as? CatchupCapability.Supported
    return ChannelEntity(
        id = id,
        playlistId = playlistId,
        number = number,
        name = name,
        nameNormalized = SearchText.normalize(name),
        groupName = groupName?.trim().orEmpty(),
        logoUrl = logoUrl,
        epgId = epgId?.takeIf { it.isNotBlank() },
        resolvedEpgId = null,
        manualEpgId = null,
        stream = stream.toEmbedded(),
        catchupType = supported?.type?.name,
        catchupDays = supported?.days ?: 0,
        catchupTemplate = supported?.sourceTemplate,
        isAdult = isAdult,
        sortIndex = sortIndex,
        extrasJson = MiniJson.encodeOrNull(extras),
    )
}

fun ChannelEntity.toModel(): Channel {
    val type = enumOrNull<CatchupType>(catchupType)
    return Channel(
        id = id,
        playlistId = playlistId,
        number = number,
        name = name,
        groupName = groupName.ifEmpty { null },
        logoUrl = logoUrl,
        epgId = effectiveEpgId(manualEpgId, resolvedEpgId, epgId),
        stream = stream.toModel(),
        catchup = if (type == null) CatchupCapability.None else CatchupCapability.Supported(type, catchupDays, catchupTemplate),
        isAdult = isAdult,
        extras = MiniJson.decode(extrasJson),
    )
}

private val CATCHUP_TYPE_NAMES: Set<String> = CatchupType.entries.mapTo(HashSet()) { it.name }

/** Slim list row: no stream/extras, so no JSON decoding (cheap for 200k rows). */
fun ChannelListRow.toListItem(): ChannelListItem = ChannelListItem(
    id = id,
    playlistId = playlistId,
    number = number,
    name = name,
    groupName = groupName.ifEmpty { null },
    logoUrl = logoUrl,
    epgId = effectiveEpgId(manualEpgId, resolvedEpgId, epgId),
    hasCatchup = catchupType != null && catchupType in CATCHUP_TYPE_NAMES,
    catchupDays = catchupDays,
    isAdult = isAdult,
)

/** Lightweight channel used only for EPG name matching (stream is irrelevant there). */
fun ChannelEpgRow.toMatchChannel(): Channel = Channel(
    id = id,
    playlistId = playlistId,
    number = 0,
    name = name,
    groupName = null,
    logoUrl = null,
    epgId = epgId,
    stream = StreamSource(url = ""),
    extras = MiniJson.decode(extrasJson),
)

fun ChannelGroupRow.toModel(playlistId: Long): ChannelGroup = ChannelGroup(playlistId, name, channelCount)

// ---- EPG ---------------------------------------------------------------------------------------

fun EpgProgram.toEntity(): ProgramEntity = ProgramEntity(
    epgId = channelEpgId,
    startMillis = startMillis,
    endMillis = endMillis,
    title = title,
    description = description,
    category = category,
    iconUrl = iconUrl,
    episodeNum = episodeNumber,
)

fun ProgramEntity.toModel(): EpgProgram = EpgProgram(
    channelEpgId = epgId,
    startMillis = startMillis,
    endMillis = endMillis,
    title = title,
    description = description,
    category = category,
    iconUrl = iconUrl,
    episodeNumber = episodeNum,
)

// ---- VOD ---------------------------------------------------------------------------------------

fun VodCategory.toEntity(kind: String, sortIndex: Int): VodCategoryEntity =
    VodCategoryEntity(playlistId = playlistId, kind = kind, id = id, name = name, sortIndex = sortIndex)

fun VodCategoryEntity.toModel(): VodCategory = VodCategory(playlistId = playlistId, id = id, name = name)

fun Movie.toEntity(sortIndex: Int): MovieEntity = MovieEntity(
    id = id,
    playlistId = playlistId,
    name = name,
    nameNormalized = SearchText.normalize(name),
    categoryId = categoryId,
    posterUrl = posterUrl,
    stream = stream.toEmbedded(),
    year = year,
    plot = plot,
    genre = genre,
    rating = rating,
    durationSeconds = durationSeconds,
    addedAt = addedAtMillis,
    sortIndex = sortIndex,
)

fun MovieEntity.toModel(): Movie = Movie(
    id = id,
    playlistId = playlistId,
    name = name,
    categoryId = categoryId,
    posterUrl = posterUrl,
    stream = stream.toModel(),
    year = year,
    plot = plot,
    genre = genre,
    rating = rating,
    durationSeconds = durationSeconds,
    addedAtMillis = addedAt,
)

fun Series.toEntity(sortIndex: Int): SeriesEntity = SeriesEntity(
    id = id,
    playlistId = playlistId,
    name = name,
    nameNormalized = SearchText.normalize(name),
    categoryId = categoryId,
    posterUrl = posterUrl,
    plot = plot,
    genre = genre,
    year = year,
    rating = rating,
    addedAt = addedAtMillis,
    sortIndex = sortIndex,
)

fun SeriesEntity.toModel(): Series = Series(
    id = id,
    playlistId = playlistId,
    name = name,
    categoryId = categoryId,
    posterUrl = posterUrl,
    plot = plot,
    genre = genre,
    year = year,
    rating = rating,
    addedAtMillis = addedAt,
)

fun Episode.toEntity(): EpisodeEntity = EpisodeEntity(
    id = id,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    stream = stream.toEmbedded(),
    plot = plot,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
)

fun EpisodeEntity.toModel(): Episode = Episode(
    id = id,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    stream = stream.toModel(),
    plot = plot,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
)

/**
 * Rebuilds seasons from the cache. Episodes whose season row is missing get a synthetic season
 * so nothing cached is hidden.
 */
fun assembleSeasons(seasons: List<SeasonEntity>, episodes: List<EpisodeEntity>): List<Season> {
    val bySeason = episodes.groupBy { it.seasonNumber }
    val names = seasons.associate { it.number to it.name }
    val numbers = (names.keys + bySeason.keys).toSortedSet()
    return numbers.map { n ->
        val seriesId = seasons.firstOrNull()?.seriesId ?: episodes.first().seriesId
        Season(
            seriesId = seriesId,
            number = n,
            name = names[n] ?: "Season $n",
            episodes = bySeason[n].orEmpty().sortedBy { it.episodeNumber }.map { it.toModel() },
        )
    }
}

// ---- user data ---------------------------------------------------------------------------------

fun FavoriteEntity.toModel(): Favorite? {
    val t = enumOrNull<ContentType>(type) ?: return null
    return Favorite(contentType = t, contentId = contentId, playlistId = playlistId, addedAtMillis = addedAt, sortOrder = sortOrder)
}

fun WatchHistory.toEntity(): WatchHistoryEntity = WatchHistoryEntity(
    type = contentType.name,
    contentId = contentId,
    playlistId = playlistId,
    title = title,
    imageUrl = imageUrl,
    positionMillis = positionMillis.coerceAtLeast(0),
    durationMillis = durationMillis.coerceAtLeast(0),
    lastWatchedAt = lastWatchedAtMillis,
    parentId = parentId,
)

fun WatchHistoryEntity.toModel(): WatchHistory? {
    val t = enumOrNull<ContentType>(type) ?: return null
    return WatchHistory(
        contentType = t,
        contentId = contentId,
        playlistId = playlistId,
        title = title,
        imageUrl = imageUrl,
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        lastWatchedAtMillis = lastWatchedAt,
        parentId = parentId,
    )
}
