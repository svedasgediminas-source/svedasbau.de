package app.novaiptv.feature.guide

import app.novaiptv.core.domain.Reminder
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.feature.livetv.isAdultGroupName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Pure, Android-free logic for the TV guide: day boundaries (DST-safe), the row "cell" model,
 * horizontal/vertical navigation and catch-up decisions. Everything here is unit tested.
 * All times are UTC epoch millis; only presentation converts to the device zone.
 */

const val MINUTE_MILLIS: Long = 60_000L
const val SLOT_MILLIS: Long = 30 * MINUTE_MILLIS
const val DAY_MILLIS: Long = 24 * 60 * MINUTE_MILLIS

/**
 * Upper bound for past days in the selector, whatever a provider declares. Archive depth itself
 * always comes from the provider (14-day archives are fully browsable; unknown = 14 days).
 */
const val MAX_PAST_DAYS: Int = 30
const val FUTURE_DAYS: Int = 7

/** One selectable guide day. [endMillis] - [startMillis] is 23 h / 25 h on DST transition days. */
data class GuideDay(val date: LocalDate, val startMillis: Long, val endMillis: Long) {
    val lengthMillis: Long get() = endMillis - startMillis
    operator fun contains(millis: Long): Boolean = millis in startMillis until endMillis
}

fun guideDay(date: LocalDate, zone: ZoneId): GuideDay {
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return GuideDay(date, start, end)
}

fun localDateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** Days from today - [pastDays] to today + [futureDays] inclusive. */
fun buildGuideDays(nowMillis: Long, zone: ZoneId, pastDays: Int, futureDays: Int = FUTURE_DAYS): List<GuideDay> {
    val today = localDateOf(nowMillis, zone)
    val past = pastDays.coerceIn(0, MAX_PAST_DAYS)
    return (-past..futureDays.coerceAtLeast(0)).map { guideDay(today.plusDays(it.toLong()), zone) }
}

/** Largest declared archive depth among [channels], 0 when none supports catch-up. */
fun maxCatchupDays(channels: List<Channel>, cap: Int = MAX_PAST_DAYS): Int {
    var max = 0
    for (c in channels) {
        val cap0 = c.catchup
        if (cap0 is CatchupCapability.Supported && cap0.windowDays > max) max = cap0.windowDays
    }
    return max.coerceIn(0, cap)
}

/** Same wall-clock time as [millis] but on [date]; used when switching days. Clamped into that day. */
fun sameLocalTimeOn(millis: Long, date: LocalDate, zone: ZoneId): Long {
    val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
    val day = guideDay(date, zone)
    val target = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    return target.coerceIn(day.startMillis, day.endMillis - 1)
}

/** Floors [millis] to a 30-minute slot boundary measured from [dayStart]. */
fun floorToSlot(millis: Long, dayStart: Long): Long {
    val delta = millis - dayStart
    return dayStart + Math.floorDiv(delta, SLOT_MILLIS) * SLOT_MILLIS
}

/** Slot boundaries (ruler ticks) inside [from, to). */
fun slotTicks(day: GuideDay, from: Long, to: Long): List<Long> {
    val out = ArrayList<Long>()
    var t = floorToSlot(maxOf(from, day.startMillis), day.startMillis)
    if (t < from) t += SLOT_MILLIS
    val end = minOf(to, day.endMillis)
    while (t < end) {
        out += t
        t += SLOT_MILLIS
    }
    return out
}

/** A horizontal piece of a guide row. Cells of a row are contiguous and cover the whole day. */
sealed interface GuideCell {
    /** Clipped to the day. */
    val start: Long
    val end: Long

    data class Program(val program: EpgProgram, override val start: Long, override val end: Long) : GuideCell

    /** No programme data for this span (split on 30-minute slots so D-pad can step through it). */
    data class Gap(override val start: Long, override val end: Long) : GuideCell
}

/**
 * Builds contiguous cells for one channel over [day]. Programmes are sorted, clipped to the day,
 * overlaps are trimmed (later programme starts where the earlier one ends) and holes become [GuideCell.Gap]s.
 */
fun buildRowCells(programs: List<EpgProgram>, day: GuideDay): List<GuideCell> {
    val dayStart = day.startMillis
    val dayEnd = day.endMillis
    val out = ArrayList<GuideCell>()
    var cursor = dayStart
    val sorted = programs.filter { it.endMillis > dayStart && it.startMillis < dayEnd && it.endMillis > it.startMillis }
        .sortedWith(compareBy({ it.startMillis }, { it.endMillis }))
    for (p in sorted) {
        val s = maxOf(p.startMillis, cursor)
        val e = minOf(p.endMillis, dayEnd)
        if (e <= s) continue
        if (s > cursor) addGaps(out, cursor, s, dayStart)
        out += GuideCell.Program(p, s, e)
        cursor = e
    }
    if (cursor < dayEnd) addGaps(out, cursor, dayEnd, dayStart)
    return out
}

private fun addGaps(out: MutableList<GuideCell>, from: Long, to: Long, dayStart: Long) {
    var s = from
    while (s < to) {
        val nextBoundary = floorToSlot(s, dayStart) + SLOT_MILLIS
        val e = minOf(nextBoundary, to)
        out += GuideCell.Gap(s, e)
        s = e
    }
}

/** Index of the cell containing [millis] (clamped to the first/last cell), -1 for an empty list. */
fun cellIndexAt(cells: List<GuideCell>, millis: Long): Int {
    if (cells.isEmpty()) return -1
    if (millis < cells.first().start) return 0
    if (millis >= cells.last().end) return cells.lastIndex
    var lo = 0
    var hi = cells.lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val c = cells[mid]
        when {
            millis < c.start -> hi = mid - 1
            millis >= c.end -> lo = mid + 1
            else -> return mid
        }
    }
    return lo.coerceIn(0, cells.lastIndex)
}

/** Cells overlapping [viewStart, viewEnd). */
fun visibleCells(cells: List<GuideCell>, viewStart: Long, viewEnd: Long): List<GuideCell> {
    if (cells.isEmpty()) return emptyList()
    val from = cellIndexAt(cells, viewStart)
    val out = ArrayList<GuideCell>()
    var i = from
    while (i < cells.size && cells[i].start < viewEnd) {
        if (cells[i].end > viewStart) out += cells[i]
        i++
    }
    return out
}

/** Horizontal placement in dp of a cell clipped to the visible window. */
data class CellPlacement(val offsetDp: Float, val widthDp: Float)

fun placeCell(start: Long, end: Long, viewStart: Long, viewEnd: Long, dpPerMinute: Float): CellPlacement? {
    val s = maxOf(start, viewStart)
    val e = minOf(end, viewEnd)
    if (e <= s) return null
    val offset = (s - viewStart).toFloat() / MINUTE_MILLIS * dpPerMinute
    val width = (e - s).toFloat() / MINUTE_MILLIS * dpPerMinute
    return CellPlacement(offset, width)
}

/** Clamps the left edge of the visible window into the day. */
fun clampViewStart(viewStart: Long, spanMillis: Long, day: GuideDay): Long {
    val max = maxOf(day.startMillis, day.endMillis - spanMillis)
    return viewStart.coerceIn(day.startMillis, max)
}

/** Default window: half an hour before [focusMillis], slot aligned. */
fun initialViewStart(focusMillis: Long, spanMillis: Long, day: GuideDay): Long =
    clampViewStart(floorToSlot(focusMillis, day.startMillis) - SLOT_MILLIS, spanMillis, day)

/**
 * Returns a window start that keeps the start of [cellStart, cellEnd) visible, scrolling as little as
 * possible and in whole slots. A cell starting in the last slot of the window and extending past it
 * pulls the window forward so the user sees what is focused.
 */
fun ensureVisible(cellStart: Long, cellEnd: Long, viewStart: Long, spanMillis: Long, day: GuideDay): Long {
    val s = maxOf(cellStart, day.startMillis)
    val e = minOf(cellEnd, day.endMillis)
    val viewEnd = viewStart + spanMillis
    val target = when {
        s < viewStart -> floorToSlot(s, day.startMillis)
        s >= viewEnd -> floorToSlot(s, day.startMillis) - (if (spanMillis > 2 * SLOT_MILLIS) SLOT_MILLIS else 0L)
        e > viewEnd && s >= viewEnd - SLOT_MILLIS && spanMillis > 2 * SLOT_MILLIS ->
            floorToSlot(s, day.startMillis) - SLOT_MILLIS
        else -> viewStart
    }
    return clampViewStart(target, spanMillis, day)
}

/** Guide cursor: [focusMillis] is the time anchor kept when moving up/down between channels. */
data class GuideCursor(val row: Int, val focusMillis: Long, val viewStart: Long)

/** LEFT (-1) / RIGHT (+1) inside a row. Returns null when there is nothing further in that direction. */
fun moveHorizontal(cells: List<GuideCell>, cursor: GuideCursor, direction: Int, spanMillis: Long, day: GuideDay): GuideCursor? {
    val idx = cellIndexAt(cells, cursor.focusMillis)
    if (idx < 0) return null
    val target = idx + direction
    if (target !in cells.indices) return null
    val cell = cells[target]
    val view = ensureVisible(cell.start, cell.end, cursor.viewStart, spanMillis, day)
    val anchor = maxOf(cell.start, view).coerceAtMost(cell.end - 1)
    return cursor.copy(focusMillis = anchor, viewStart = view)
}

/** UP (-1) / DOWN (+1): same time anchor, adjacent channel. Null at the list edges. */
fun moveVertical(cursor: GuideCursor, direction: Int, rowCount: Int): GuideCursor? {
    val target = cursor.row + direction
    if (target !in 0 until rowCount) return null
    return cursor.copy(row = target)
}

/** What OK on a guide cell does. */
enum class GuideAction { PlayLive, PlayCatchup, ArchiveUnavailable, ArchiveExpired, Upcoming }

/** True when the programme is past and inside the channel's declared archive window. */
fun isInArchive(capability: CatchupCapability, startMillis: Long, endMillis: Long, nowMillis: Long): Boolean {
    if (capability !is CatchupCapability.Supported) return false
    if (endMillis > nowMillis) return false
    return startMillis >= nowMillis - capability.windowDays * DAY_MILLIS
}

fun decideGuideAction(capability: CatchupCapability, startMillis: Long, endMillis: Long, nowMillis: Long): GuideAction = when {
    nowMillis in startMillis until endMillis -> GuideAction.PlayLive
    startMillis > nowMillis -> GuideAction.Upcoming
    capability !is CatchupCapability.Supported -> GuideAction.ArchiveUnavailable
    isInArchive(capability, startMillis, endMillis, nowMillis) -> GuideAction.PlayCatchup
    else -> GuideAction.ArchiveExpired
}

/** Clock time formatting following the device 12/24 h setting. */
class ClockFormat(locale: Locale, is24Hour: Boolean, private val zone: ZoneId) {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)

    fun time(millis: Long): String = formatter.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun range(startMillis: Long, endMillis: Long): String = "${time(startMillis)} – ${time(endMillis)}"
}

/** Short day label like "Sat 10" (localized). Relative names (Today, ...) are resolved in the UI. */
fun dayLabel(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date)

/** -1 yesterday, 0 today, 1 tomorrow, else null. */
fun relativeDayOffset(date: LocalDate, nowMillis: Long, zone: ZoneId): Int? {
    val today = localDateOf(nowMillis, zone)
    return when (date) {
        today -> 0
        today.minusDays(1) -> -1
        today.plusDays(1) -> 1
        else -> null
    }
}

// ---------------------------------------------------------------------------------------------
// Group filter (chip row above the grid)
// ---------------------------------------------------------------------------------------------

/** Identity of a guide group chip. [Group.name] "" is the bucket of channels without a group. */
sealed interface GuideGroupKey {
    data object All : GuideGroupKey
    data object Favorites : GuideGroupKey
    data class Group(val name: String) : GuideGroupKey
}

data class GuideGroup(val key: GuideGroupKey, val count: Int)

/** Group name as shown/filtered in the guide: blank or missing group = "". */
fun guideGroupName(channel: Channel): String = channel.groupName?.takeIf { it.isNotBlank() } ?: ""

/**
 * All, Favourites, then the channel groups of [channels] in playlist order (first appearance) with
 * their counts. The "" group (no group) is always last. Adult-named groups are dropped when [hideAdult].
 * [channels] are expected to be already adult-filtered per channel.
 */
fun buildGuideGroups(channels: List<Channel>, favoritesCount: Int, hideAdult: Boolean): List<GuideGroup> {
    val counts = LinkedHashMap<String, Int>()
    for (c in channels) {
        val name = guideGroupName(c)
        counts[name] = (counts[name] ?: 0) + 1
    }
    val groups = counts.entries
        .filter { (name, _) -> !(hideAdult && isAdultGroupName(name)) }
        .sortedBy { (name, _) -> name.isEmpty() } // stable: keeps playlist order, "" last
        .map { (name, count) -> GuideGroup(GuideGroupKey.Group(name), count) }
    return buildList {
        add(GuideGroup(GuideGroupKey.All, groups.sumOf { it.count }))
        add(GuideGroup(GuideGroupKey.Favorites, favoritesCount))
        addAll(groups)
    }
}

/** The selected key if it still exists in [groups], else [GuideGroupKey.All]. */
fun resolveGuideGroup(selected: GuideGroupKey, groups: List<GuideGroup>): GuideGroupKey =
    if (selected is GuideGroupKey.Group && groups.none { it.key == selected }) GuideGroupKey.All else selected

/**
 * Rows of the guide for [key]. Favourites follow the user's order ([favoriteIds]); groups keep
 * playlist order. All = [channels] unchanged.
 */
fun filterGuideChannels(channels: List<Channel>, key: GuideGroupKey, favoriteIds: List<String>): List<Channel> = when (key) {
    GuideGroupKey.All -> channels
    GuideGroupKey.Favorites -> {
        val byId = channels.associateBy { it.id }
        favoriteIds.distinct().mapNotNull { byId[it] }
    }
    is GuideGroupKey.Group -> channels.filter { guideGroupName(it) == key.name }
}

/** Saveable token for a [GuideGroupKey] (rememberSaveable stores plain strings). */
fun encodeGuideGroup(key: GuideGroupKey): String = when (key) {
    GuideGroupKey.All -> "all"
    GuideGroupKey.Favorites -> "fav"
    is GuideGroupKey.Group -> "g:" + key.name
}

fun decodeGuideGroup(token: String?): GuideGroupKey = when {
    token == null || token == "all" -> GuideGroupKey.All
    token == "fav" -> GuideGroupKey.Favorites
    token.startsWith("g:") -> GuideGroupKey.Group(token.substring(2))
    else -> GuideGroupKey.All
}

// ---------------------------------------------------------------------------------------------
// Reminders
// ---------------------------------------------------------------------------------------------

/** Reminder for [program] on [channel]. */
fun reminderFor(channel: Channel, program: EpgProgram): Reminder =
    Reminder(
        channelId = channel.id,
        epgId = channel.epgId ?: program.channelEpgId.takeIf { it.isNotEmpty() },
        title = program.title,
        startMillis = program.startMillis,
        endMillis = program.endMillis,
        channelName = channel.name,
    )
