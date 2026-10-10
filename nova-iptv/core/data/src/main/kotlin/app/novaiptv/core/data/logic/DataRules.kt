package app.novaiptv.core.data.logic

import app.novaiptv.core.domain.StreamResolver
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.NowNext
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.WatchHistory
import java.net.URLEncoder

const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

/** Archive window rules shared by the catch-up resolver and the EPG import. */
object CatchupWindow {
    /**
     * A programme can be played from the archive when it has already started (live programmes
     * can be restarted) and started no earlier than [days] days ago. [days] <= 0 means no archive.
     */
    fun isAvailable(startMillis: Long, days: Int, nowMillis: Long): Boolean =
        days > 0 && startMillis <= nowMillis && startMillis >= nowMillis - days * DAY_MILLIS

    /** Programme duration in whole minutes, rounded up, at least 1. */
    fun durationMinutes(startMillis: Long, endMillis: Long): Int {
        val millis = (endMillis - startMillis).coerceAtLeast(0)
        val minutes = (millis + 59_999) / 60_000
        return minutes.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Which programmes the EPG import keeps. */
object EpgWindow {
    const val FORWARD_MILLIS: Long = 7 * DAY_MILLIS
    const val DEFAULT_BACK_MILLIS: Long = DAY_MILLIS

    /** Look-back for a channel: one day, or the declared catch-up window if longer. */
    fun backMillis(catchupDays: Int): Long = maxOf(DEFAULT_BACK_MILLIS, catchupDays.coerceAtLeast(0) * DAY_MILLIS)

    fun keep(startMillis: Long, endMillis: Long, nowMillis: Long, backMillis: Long): Boolean =
        endMillis > nowMillis - backMillis && startMillis < nowMillis + FORWARD_MILLIS

    /** Programmes ending before this are deleted after a refresh. */
    fun purgeBefore(nowMillis: Long, maxCatchupDays: Int): Long =
        nowMillis - backMillis(maxCatchupDays) - DAY_MILLIS
}

object NowNextSelector {
    /**
     * Picks the current and next programme per epg id. [programs] may be in any order and may
     * contain several channels.
     */
    fun select(programs: List<EpgProgram>, nowMillis: Long): Map<String, NowNext> {
        val out = LinkedHashMap<String, NowNext>()
        for ((id, list) in programs.groupBy { it.channelEpgId }) {
            val sorted = list.sortedBy { it.startMillis }
            val current = sorted.firstOrNull { nowMillis >= it.startMillis && nowMillis < it.endMillis }
            val after = current?.endMillis ?: nowMillis
            val next = sorted.firstOrNull { it !== current && it.startMillis >= after }
            if (current != null || next != null) out[id] = NowNext(current, next)
        }
        return out
    }
}

object ContinueWatching {
    /**
     * [entriesNewestFirst] is the history ordered by lastWatchedAt desc. Channels and finished
     * items are dropped; episodes of the same series collapse into the most recent one.
     */
    fun select(entriesNewestFirst: List<WatchHistory>, limit: Int): List<WatchHistory> =
        entriesNewestFirst.asSequence()
            .filter { it.contentType != ContentType.Channel && !it.isFinished }
            .distinctBy { if (it.contentType == ContentType.Episode) "s:" + (it.parentId ?: it.contentId) else "${it.contentType}:${it.contentId}" }
            .take(limit.coerceAtLeast(0))
            .toList()
}

object Reorder {
    /** Moves [id] to [newIndex] (clamped). Unknown ids leave the list unchanged. */
    fun <T> move(items: List<T>, item: T, newIndex: Int): List<T> {
        val from = items.indexOf(item)
        if (from < 0) return items
        val list = items.toMutableList()
        list.removeAt(from)
        list.add(newIndex.coerceIn(0, list.size), item)
        return list
    }
}

/** Substitution of the Xtream credential placeholders stored in stream URLs. */
object XtreamPlaceholders {
    fun contains(url: String): Boolean =
        url.contains(StreamResolver.USER_PLACEHOLDER) || url.contains(StreamResolver.PASS_PLACEHOLDER)

    /** Percent-encodes characters that are not allowed in a URL path segment. */
    fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun substitute(url: String, username: String, password: String): String =
        url.replace(StreamResolver.USER_PLACEHOLDER, encodePathSegment(username))
            .replace(StreamResolver.PASS_PLACEHOLDER, encodePathSegment(password))
}

/** Stream format guess for URLs built at runtime (catch-up). */
object StreamFormats {
    fun fromUrl(url: String, fallback: StreamFormat): StreamFormat {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> StreamFormat.Hls
            path.endsWith(".mpd") -> StreamFormat.Dash
            path.endsWith(".ts") -> StreamFormat.MpegTs
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") -> StreamFormat.Progressive
            else -> fallback
        }
    }
}

/** XMLTV id used for a channel: user override, then name-matched id, then the playlist's tvg-id. */
fun effectiveEpgId(manualEpgId: String?, resolvedEpgId: String?, epgId: String?): String? =
    manualEpgId?.takeIf { it.isNotBlank() } ?: resolvedEpgId?.takeIf { it.isNotBlank() } ?: epgId?.takeIf { it.isNotBlank() }

/** Whether a playlist should be refreshed now. */
fun isStale(lastRefreshedAtMillis: Long?, autoRefreshHours: Int, nowMillis: Long): Boolean {
    if (autoRefreshHours <= 0) return false
    if (lastRefreshedAtMillis == null) return true
    return nowMillis - lastRefreshedAtMillis >= autoRefreshHours * 3_600_000L
}
