package app.novaiptv.feature.livetv

import app.novaiptv.core.domain.ChannelListItem
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ChannelGroup
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.Favorite

/*
 * Pure, Android-free logic for the Live TV screen. Unit tested in LiveTvLogicTest.
 */

/** Identity of a category in the left pane (count-independent, so selection survives count changes). */
sealed interface CategoryKey {
    data object All : CategoryKey
    data object Favorites : CategoryKey
    data class Group(val name: String) : CategoryKey
}

data class LiveCategory(val key: CategoryKey, val count: Int)

private val ADULT_MARKERS = listOf(
    "adult", "xxx", "18+", "+18", "porn", "erotic", "erotik", "for adults",
    "для взрослых", "эротика", "suaugusiems", "erwachsene",
)

/** Heuristic for adult groups; providers mark them only by name. */
fun isAdultGroupName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val n = name.lowercase()
    return ADULT_MARKERS.any { n.contains(it) }
}

fun isAdultChannel(channel: Channel): Boolean = channel.isAdult || isAdultGroupName(channel.groupName)

fun isAdultChannel(channel: ChannelListItem): Boolean = channel.isAdult || isAdultGroupName(channel.groupName)

/** All, Favorites, then provider groups (adult ones removed when [hideAdult]). */
fun buildCategories(groups: List<ChannelGroup>, hideAdult: Boolean, favoritesCount: Int): List<LiveCategory> {
    val visible = if (hideAdult) groups.filterNot { isAdultGroupName(it.name) } else groups
    val total = visible.sumOf { it.channelCount }
    return buildList {
        add(LiveCategory(CategoryKey.All, total))
        add(LiveCategory(CategoryKey.Favorites, favoritesCount))
        visible.forEach { add(LiveCategory(CategoryKey.Group(it.name), it.channelCount)) }
    }
}

/**
 * Applies the adult filter and the quick-search [query] to the slim Live TV list. A purely numeric
 * query also matches the channel number exactly. Linear; call it off the main thread for big lists.
 */
@JvmName("filterChannelItems")
fun filterChannels(channels: List<ChannelListItem>, query: String, hideAdult: Boolean): List<ChannelListItem> =
    filterBy(channels, query, hideAdult, { isAdultChannel(it) }, { it.name }, { it.number })

/** Same rules for full channels (used by the Guide). */
fun filterChannels(channels: List<Channel>, query: String, hideAdult: Boolean): List<Channel> =
    filterBy(channels, query, hideAdult, { isAdultChannel(it) }, { it.name }, { it.number })

private inline fun <T> filterBy(
    channels: List<T>,
    query: String,
    hideAdult: Boolean,
    isAdult: (T) -> Boolean,
    name: (T) -> String,
    number: (T) -> Int,
): List<T> {
    val q = query.trim()
    if (q.isEmpty() && !hideAdult) return channels
    val n = q.toIntOrNull()
    return channels.filter { c ->
        (!hideAdult || !isAdult(c)) &&
            (q.isEmpty() || name(c).contains(q, ignoreCase = true) || (n != null && number(c) == n))
    }
}

/** Favourite channel ids of [playlistId] in the user's order. */
fun favoriteChannelIds(favorites: List<Favorite>, playlistId: Long): List<String> =
    favorites.filter { it.playlistId == playlistId }
        .sortedWith(compareBy({ it.sortOrder }, { it.addedAtMillis }))
        .map { it.contentId }

/** Re-orders [channels] to follow [ids] (repositories may return them in any order). */
fun orderByIds(channels: List<ChannelListItem>, ids: List<String>): List<ChannelListItem> {
    val byId = channels.associateBy { it.id }
    return ids.mapNotNull { byId[it] }
}

/** Active playlist: the preferred one if it still exists, else the first playlist. */
fun resolveActivePlaylistId(preferred: Long?, available: List<Long>): Long? =
    if (preferred != null && preferred in available) preferred else available.firstOrNull()

/** 0..1 progress of a running programme, null if it is not running. */
fun programProgress(program: EpgProgram?, nowMillis: Long): Float? {
    if (program == null || program.endMillis <= program.startMillis) return null
    if (nowMillis < program.startMillis || nowMillis >= program.endMillis) return null
    return ((nowMillis - program.startMillis).toFloat() / (program.endMillis - program.startMillis)).coerceIn(0f, 1f)
}

/** Channel numbers <= 0 mean "not numbered" and are not shown. */
fun formatChannelNumber(number: Int): String = if (number > 0) number.toString() else ""

const val MAX_NUMBER_DIGITS = 5

/**
 * Appends a remote digit to the channel-number entry. Leading zeros are dropped; when the entry is
 * full, the new digit starts a fresh entry.
 */
fun appendDigit(current: String, digit: Int, maxDigits: Int = MAX_NUMBER_DIGITS): String {
    require(digit in 0..9) { "digit out of range: $digit" }
    val base = if (current.length >= maxDigits) "" else current
    val next = (base + digit).trimStart('0')
    return next
}

/** Index to focus when entering the list: the preferred channel if present, else 0. */
fun initialFocusIndex(channels: List<ChannelListItem>, preferredId: String?): Int {
    if (preferredId == null) return 0
    val i = channels.indexOfFirst { it.id == preferredId }
    return if (i >= 0) i else 0
}

/** Neighbour to focus after [index] disappears from the list (e.g. un-favourited). */
fun neighbourAfterRemoval(channels: List<ChannelListItem>, index: Int): String? = when {
    index + 1 < channels.size -> channels[index + 1].id
    index - 1 >= 0 -> channels[index - 1].id
    else -> null
}
