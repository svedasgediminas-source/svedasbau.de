package app.novaiptv.feature.home

import app.novaiptv.core.domain.ChannelListItem
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ChannelGroup
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.NowNext
import app.novaiptv.core.model.WatchHistory
import app.novaiptv.feature.livetv.isAdultChannel
import app.novaiptv.feature.livetv.isAdultGroupName

/*
 * Pure, Android-free selection logic for Home (live hero choice, group rails, EPG id batching).
 * Unit tested in HomeLogicTest.
 */

/** Channel as shown on Home; built from full channels (favourites, history) or slim list rows (group rails). */
data class HomeChannel(
    val id: String,
    val playlistId: Long,
    val number: Int,
    val name: String,
    val groupName: String?,
    val logoUrl: String?,
    val epgId: String?,
    val isAdult: Boolean,
)

fun Channel.toHomeChannel(): HomeChannel =
    HomeChannel(id, playlistId, number, name, groupName, logoUrl, epgId, isAdult || isAdultGroupName(groupName))

fun ChannelListItem.toHomeChannel(): HomeChannel =
    HomeChannel(id, playlistId, number, name, groupName, logoUrl, epgId, isAdult || isAdultGroupName(groupName))

/** A channel with its current/next programme for Home rails and the live hero. */
data class ChannelNow(val channel: HomeChannel, val now: EpgProgram?, val next: EpgProgram?) {
    fun progress(nowMillis: Long): Float? = HomeLogic.progress(now, nowMillis)
}

/** One "channels of group X" rail. [name] is "" for ungrouped channels (the UI shows a localized label). */
data class GroupRail(val name: String, val channels: List<HomeChannel>)

object HomeLogic {
    /** Size of the "Now on TV" rail. */
    const val NOW_ON_TV = 20
    /** How many leading channels to read for "Now on TV" when adult ones are hidden. */
    const val NOW_ON_TV_FETCH = 60
    const val MAX_GROUP_RAILS = 6
    const val GROUP_RAIL_SIZE = 20
    /** Upper bound for one batched now/next query (all rails together). */
    const val MAX_EPG_IDS = 300

    /**
     * Picks up to [max] groups with the most channels (ties: playlist order), dropping empty groups
     * and – when [hideAdult] – adult groups. The result keeps the playlist order so the rails follow
     * the provider's arrangement. With fewer than [minGroups] usable groups no group rails are shown:
     * a single group would only repeat the "Now on TV" rail.
     */
    fun selectGroups(
        groups: List<ChannelGroup>,
        hideAdult: Boolean,
        max: Int = MAX_GROUP_RAILS,
        minGroups: Int = 2,
    ): List<ChannelGroup> {
        if (max <= 0) return emptyList()
        val seen = HashSet<String>()
        val usable = groups.withIndex().filter { (_, g) ->
            g.channelCount > 0 && !(hideAdult && isAdultGroupName(g.name)) && seen.add(g.name)
        }
        if (usable.size < minGroups) return emptyList()
        return usable
            .sortedWith(compareByDescending<IndexedValue<ChannelGroup>> { it.value.channelCount }.thenBy { it.index })
            .take(max)
            .sortedBy { it.index }
            .map { it.value }
    }

    /** First [limit] channels of a group rail, adult ones removed when [hideAdult]. */
    fun capGroupRail(items: List<ChannelListItem>, hideAdult: Boolean, limit: Int = GROUP_RAIL_SIZE): List<HomeChannel> =
        items.asSequence()
            .filter { !(hideAdult && isAdultChannel(it)) }
            .take(limit.coerceAtLeast(0))
            .map { it.toHomeChannel() }
            .toList()

    /** Full channels → Home channels, adult ones removed when [hideAdult]. */
    fun visibleChannels(channels: List<Channel>, hideAdult: Boolean, limit: Int = Int.MAX_VALUE): List<HomeChannel> =
        channels.asSequence()
            .filter { !(hideAdult && isAdultChannel(it)) }
            .take(limit.coerceAtLeast(0))
            .map { it.toHomeChannel() }
            .toList()

    /**
     * Distinct, non-blank EPG ids of all channels shown on Home, in the given priority order
     * (hero candidates first), capped at [max] so one batched now/next query covers every rail.
     */
    fun epgIds(lists: List<List<HomeChannel>>, max: Int = MAX_EPG_IDS): List<String> {
        val out = LinkedHashSet<String>()
        for (list in lists) {
            for (ch in list) {
                if (out.size >= max) return out.toList()
                val id = ch.epgId
                if (!id.isNullOrBlank()) out.add(id)
            }
        }
        return out.toList()
    }

    /** The programme airing at [nowMillis], if the now/next entry really covers that moment. */
    fun currentProgram(nowNext: NowNext?, nowMillis: Long): EpgProgram? = nowNext?.now?.takeIf { it.isLive(nowMillis) }

    fun progress(program: EpgProgram?, nowMillis: Long): Float? {
        val p = program ?: return null
        if (p.durationMillis <= 0) return null
        return ((nowMillis - p.startMillis).toFloat() / p.durationMillis).coerceIn(0f, 1f)
    }

    /** Channel ids from [history], most watched first (ties: most recently watched first). */
    fun mostWatchedChannelIds(history: List<WatchHistory>): List<String> {
        val channelEntries = history.filter { it.contentType == ContentType.Channel }
        if (channelEntries.isEmpty()) return emptyList()
        val counts = HashMap<String, Int>()
        val last = HashMap<String, Long>()
        for (h in channelEntries) {
            counts[h.contentId] = (counts[h.contentId] ?: 0) + 1
            last[h.contentId] = maxOf(last[h.contentId] ?: Long.MIN_VALUE, h.lastWatchedAtMillis)
        }
        return counts.keys.sortedWith(
            compareByDescending<String> { counts.getValue(it) }.thenByDescending { last.getValue(it) },
        )
    }

    /**
     * The channel for the LIVE hero: the first candidate that is airing something right now, looking
     * at favourites (in their order), then the most watched channels from [history], then [others]
     * (e.g. Now on TV and group rails, in display order). When no candidate has a current programme
     * (no EPG), the first candidate in that same order is used so Home still gets a hero.
     * Adult channels are skipped when [hideAdult].
     */
    fun pickLiveHero(
        favorites: List<HomeChannel>,
        recent: List<HomeChannel>,
        history: List<WatchHistory>,
        others: List<HomeChannel>,
        nowNext: Map<String, NowNext>,
        nowMillis: Long,
        hideAdult: Boolean,
    ): HomeChannel? {
        val known = LinkedHashMap<String, HomeChannel>()
        (favorites + recent + others).forEach { known.putIfAbsent(it.id, it) }
        val ordered = LinkedHashMap<String, HomeChannel>()
        favorites.forEach { ordered.putIfAbsent(it.id, it) }
        mostWatchedChannelIds(history).forEach { id -> known[id]?.let { ordered.putIfAbsent(id, it) } }
        others.forEach { ordered.putIfAbsent(it.id, it) }
        val candidates = ordered.values.filter { !(hideAdult && it.isAdult) }
        return candidates.firstOrNull { ch -> currentProgram(ch.epgId?.let { nowNext[it] }, nowMillis) != null }
            ?: candidates.firstOrNull()
    }
}
