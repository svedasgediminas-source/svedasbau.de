package app.novaiptv.data.xtream

import app.novaiptv.core.common.AppResult
import app.novaiptv.core.common.Limits
import app.novaiptv.core.common.UrlValidator
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.Episode
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.Season
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.model.VodCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Pure mapping from Xtream JSON to domain models. Every item is mapped independently; an item
 * lacking a usable id or name is skipped instead of failing the whole catalogue.
 */
internal object XtreamMapper {
    private val SAFE_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val SAFE_EXT = Regex("^[A-Za-z0-9]{1,8}$")
    private val YEAR = Regex("""(?<!\d)(19\d{2}|20\d{2})(?!\d)""")
    private val ADULT_MARKERS = listOf("xxx", "adult", "18+")

    /** Ids end up in URL paths and app ids, so only conservative characters are accepted. */
    fun safeId(e: JsonElement?): String? = e.id()?.takeIf { SAFE_ID.matches(it) && it != "." && it != ".." }

    fun safeExt(e: JsonElement?, default: String): String =
        e.str()?.trimStart('.')?.lowercase()?.takeIf { SAFE_EXT.matches(it) } ?: default

    fun name(e: JsonElement?): String? = e.str()?.take(Limits.MAX_NAME_LENGTH)

    fun isAdultName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return ADULT_MARKERS.any { n.contains(it) }
    }

    fun formatFor(ext: String): StreamFormat = when (ext.lowercase()) {
        "m3u8" -> StreamFormat.Hls
        "mpd" -> StreamFormat.Dash
        "ts" -> StreamFormat.MpegTs
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "flv" -> StreamFormat.Progressive
        else -> StreamFormat.Unknown
    }

    fun year(e: JsonElement?): Int? = e.str()?.let { YEAR.find(it)?.value?.toIntOrNull() }

    fun secondsToMillis(e: JsonElement?): Long? = e.long()?.takeIf { it > 0 }?.let { it * 1000 }

    // Same rule as M3U logos: only http(s) images reach the image loader.
    fun image(e: JsonElement?): String? =
        e.str()?.takeIf { it.length <= Limits.MAX_URL_LENGTH && UrlValidator.validateSourceUrl(it) is AppResult.Ok }

    fun categories(root: JsonElement?, playlistId: Long): List<VodCategory> =
        root.items().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["category_id"].id() ?: return@mapNotNull null
            val name = name(o["category_name"]) ?: return@mapNotNull null
            VodCategory(playlistId, id, name)
        }

    fun liveStreams(
        root: JsonElement?,
        playlistId: Long,
        serverUrl: String,
        categoryNames: Map<String, String>,
        ext: String,
    ): List<Channel> = root.items().withIndex()
        .mapNotNull { (index, el) -> liveStream(index, el, playlistId, serverUrl, categoryNames, ext) }
        .take(Limits.MAX_CHANNELS_PER_PLAYLIST)

    /** Maps one get_live_streams element; [index] is its position (fallback channel number). */
    fun liveStream(
        index: Int,
        el: JsonElement,
        playlistId: Long,
        serverUrl: String,
        categoryNames: Map<String, String>,
        ext: String,
    ): Channel? {
        val o = el as? JsonObject ?: return null
        val streamId = safeId(o["stream_id"]) ?: return null
        val name = name(o["name"]) ?: return null
        val group = o["category_id"].id()?.let { categoryNames[it] }
        val archive = o["tv_archive"].bool()
        val days = o["tv_archive_duration"].int() ?: 0
        val catchup = if (archive && days > 0) CatchupCapability.Supported(CatchupType.Xtream, days) else CatchupCapability.None
        return Channel(
            id = "$playlistId:$streamId",
            playlistId = playlistId,
            number = o["num"].int()?.takeIf { it > 0 } ?: (index + 1),
            name = name,
            groupName = group,
            logoUrl = image(o["stream_icon"]),
            epgId = o["epg_channel_id"].str(),
            stream = StreamSource(XtreamUrls.live(serverUrl, streamId, ext), mimeTypeHint = formatFor(ext)),
            catchup = catchup,
            isAdult = o["is_adult"].bool() || isAdultName(group),
        )
    }

    fun movies(root: JsonElement?, playlistId: Long, serverUrl: String): List<Movie> =
        root.items().mapNotNull { movie(it, playlistId, serverUrl) }.take(Limits.MAX_CHANNELS_PER_PLAYLIST)

    fun movie(el: JsonElement, playlistId: Long, serverUrl: String): Movie? {
            val o = el as? JsonObject ?: return null
            val streamId = safeId(o["stream_id"]) ?: return null
            val name = name(o["name"]) ?: name(o["title"]) ?: return null
            val ext = safeExt(o["container_extension"], "mp4")
            return Movie(
                id = "$playlistId:m:$streamId",
                playlistId = playlistId,
                name = name,
                categoryId = o["category_id"].id(),
                posterUrl = image(o["stream_icon"]) ?: image(o["cover"]),
                stream = StreamSource(XtreamUrls.movie(serverUrl, streamId, ext), mimeTypeHint = formatFor(ext)),
                year = year(o["year"]) ?: year(o["releaseDate"]) ?: year(o["release_date"]),
                plot = o["plot"].str(),
                genre = o["genre"].str(),
                rating = o["rating"].double(),
                durationSeconds = o["duration_secs"].int()?.takeIf { it > 0 },
                addedAtMillis = secondsToMillis(o["added"]),
            )
    }

    fun series(root: JsonElement?, playlistId: Long): List<Series> =
        root.items().mapNotNull { seriesItem(it, playlistId) }.take(Limits.MAX_CHANNELS_PER_PLAYLIST)

    fun seriesItem(el: JsonElement, playlistId: Long): Series? {
            val o = el as? JsonObject ?: return null
            val seriesId = safeId(o["series_id"]) ?: return null
            val name = name(o["name"]) ?: name(o["title"]) ?: return null
            return Series(
                id = "$playlistId:s:$seriesId",
                playlistId = playlistId,
                name = name,
                categoryId = o["category_id"].id(),
                posterUrl = image(o["cover"]) ?: image(o["stream_icon"]),
                plot = o["plot"].str(),
                genre = o["genre"].str(),
                year = year(o["releaseDate"]) ?: year(o["release_date"]) ?: year(o["year"]),
                rating = o["rating"].double(),
                addedAtMillis = secondsToMillis(o["last_modified"]),
            )
    }

    /**
     * Parses get_series_info. "episodes" may be an object keyed by season number or a list of
     * lists; "seasons" may be an array or an object. Seasons and episodes are sorted numerically.
     */
    fun seasons(root: JsonElement?, playlistId: Long, appSeriesId: String, serverUrl: String): List<Season> {
        val o = root as? JsonObject ?: return emptyList()
        val seasonNames = HashMap<Int, String>()
        for (s in o["seasons"].items()) {
            val so = s as? JsonObject ?: continue
            val n = so["season_number"].int() ?: continue
            so["name"].str()?.take(Limits.MAX_NAME_LENGTH)?.let { seasonNames[n] = it }
        }

        // Collect (seasonNumberHint, episodeElements) groups.
        val groups = ArrayList<Pair<Int?, List<JsonElement>>>()
        when (val eps = o["episodes"]) {
            is JsonObject -> for ((key, value) in eps) groups += key.trim().toIntOrNull() to value.items()
            is JsonArray -> {
                // List of lists (one per season) or, rarely, a flat list of episode objects.
                eps.filterIsInstance<JsonArray>().forEach { groups += null to it }
                val flat = eps.filterIsInstance<JsonObject>()
                if (flat.isNotEmpty()) groups += null to flat
            }
            else -> Unit
        }

        val bySeason = LinkedHashMap<Int, MutableList<Episode>>()
        groups.forEachIndexed { groupIndex, (hint, elements) ->
            elements.forEachIndexed { epIndex, el ->
                val e = el as? JsonObject ?: return@forEachIndexed
                val epId = safeId(e["id"]) ?: return@forEachIndexed
                val season = e["season"].int() ?: hint ?: (groupIndex + 1)
                val number = e["episode_num"].int() ?: (epIndex + 1)
                val info = e.obj("info")
                val ext = safeExt(e["container_extension"], "mp4")
                val title = name(e["title"]) ?: name(info?.get("name")) ?: "Episode $number"
                val episode = Episode(
                    id = "$playlistId:e:$epId",
                    seriesId = appSeriesId,
                    seasonNumber = season,
                    episodeNumber = number,
                    title = title,
                    stream = StreamSource(XtreamUrls.episode(serverUrl, epId, ext), mimeTypeHint = formatFor(ext)),
                    plot = info?.get("plot").str(),
                    durationSeconds = info?.get("duration_secs").int()?.takeIf { it > 0 } ?: parseDuration(info?.get("duration").str()),
                    thumbnailUrl = image(info?.get("movie_image")) ?: image(info?.get("cover_big")),
                )
                bySeason.getOrPut(season) { ArrayList() }.add(episode)
            }
        }

        return bySeason.entries
            .sortedBy { it.key }
            .map { (n, list) ->
                Season(
                    seriesId = appSeriesId,
                    number = n,
                    name = seasonNames[n] ?: "Season $n",
                    episodes = list.distinctBy { it.id }.sortedBy { it.episodeNumber },
                )
            }
    }

    /** "HH:MM:SS" or "MM:SS" to seconds. */
    fun parseDuration(s: String?): Int? {
        val parts = s?.split(':')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        val secs = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> return null
        }
        return secs.takeIf { it > 0 }
    }
}
