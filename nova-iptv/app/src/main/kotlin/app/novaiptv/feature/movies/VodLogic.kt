package app.novaiptv.feature.movies

import app.novaiptv.core.model.VodCategory
import app.novaiptv.core.model.WatchHistory
import java.util.Locale
import kotlin.math.roundToInt

/** Hides adult VOD categories (and their items) when the user asked for it. Pure, no Android. */
object AdultContentFilter {
    private val markers = listOf("xxx", "adult", "18+", "+18", "porn", "erotic", "для взрослых", "эрот")

    fun isAdultCategory(name: String): Boolean {
        val n = name.lowercase()
        return markers.any { n.contains(it) }
    }

    data class Split(val visible: List<VodCategory>, val hiddenIds: Set<String>)

    /** Splits categories into visible ones (de-duplicated by id) and ids of hidden adult categories. */
    fun split(categories: List<VodCategory>, hideAdult: Boolean): Split {
        val unique = categories.distinctBy { it.id }
        if (!hideAdult) return Split(unique, emptySet())
        val (adult, visible) = unique.partition { isAdultCategory(it.name) }
        return Split(visible, adult.mapTo(HashSet()) { it.id })
    }

    fun <T> filterItems(items: List<T>, hiddenCategoryIds: Set<String>, categoryOf: (T) -> String?): List<T> =
        if (hiddenCategoryIds.isEmpty()) items else items.filter { categoryOf(it) !in hiddenCategoryIds }
}

object ActivePlaylist {
    /** The preferred playlist if it still exists, otherwise the first one; null when there is none. */
    fun resolve(preferred: Long?, available: List<Long>): Long? =
        preferred?.takeIf { it in available } ?: available.firstOrNull()
}

object VodFormat {
    data class DurationParts(val hours: Int, val minutes: Int)

    /** Rounded to whole minutes (at least one); null for missing/zero durations. */
    fun durationParts(totalSeconds: Int?): DurationParts? {
        if (totalSeconds == null || totalSeconds <= 0) return null
        val minutes = ((totalSeconds + 30) / 60).coerceAtLeast(1)
        return DurationParts(minutes / 60, minutes % 60)
    }

    /** Playback position as "mm:ss" or "h:mm:ss". */
    fun timestamp(millis: Long): String {
        val total = millis.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /** "★ 7.4" (one decimal, ".0" dropped); null when unrated (missing or <= 0). */
    fun rating(value: Double?): String? {
        if (value == null || value.isNaN() || value <= 0.0) return null
        val tenths = (value * 10).roundToInt()
        val text = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
        return "★ $text"
    }

    /** Joins the non-blank parts with a middle dot. */
    fun metaLine(vararg parts: String?): String =
        parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

    /** Position to offer "Resume" from, or null when there is nothing meaningful to resume. */
    fun resumePositionMillis(entry: WatchHistory?, minMillis: Long = MIN_RESUME_MILLIS): Long? {
        if (entry == null || entry.isFinished || entry.positionMillis < minMillis) return null
        return entry.positionMillis
    }

    const val MIN_RESUME_MILLIS = 10_000L
}
