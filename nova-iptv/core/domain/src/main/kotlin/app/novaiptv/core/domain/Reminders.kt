package app.novaiptv.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * A "remind me" mark on a future programme. Identity is ([channelId], [startMillis]): one channel
 * cannot have two programmes starting at the same instant. [channelName] is stored so the banner
 * can name the channel without a database lookup.
 *
 * Reminders only fire while the app is in the foreground (see the app's ReminderBanner);
 * background alarms / system notifications are intentionally out of scope.
 */
data class Reminder(
    val channelId: String,
    val epgId: String?,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val channelName: String = "",
) {
    val key: String get() = reminderKey(channelId, startMillis)
}

fun reminderKey(channelId: String, startMillis: Long): String = "$channelId|$startMillis"

interface ReminderRepository {
    /** Pending reminders (expired ones are dropped), ordered by start time. */
    fun observeReminders(): Flow<List<Reminder>>

    /** Adds or replaces the reminder with the same key. Programmes that already ended are ignored. */
    suspend fun add(reminder: Reminder)

    suspend fun remove(channelId: String, startMillis: Long)

    suspend fun isReminded(channelId: String, startMillis: Long): Boolean =
        observeReminders().first().any { it.channelId == channelId && it.startMillis == startMillis }
}

/** Pure reminder rules shared by the data layer and the UI. Unit tested in ReminderRulesTest. */
object ReminderRules {
    /** A reminder fires this long before the programme starts. */
    const val LEAD_MILLIS: Long = 60_000L

    /** How often the foreground checker looks for due reminders. */
    const val CHECK_INTERVAL_MILLIS: Long = 15_000L

    /** Upper bound of stored reminders (oldest-starting ones are kept, far-future extras dropped). */
    const val MAX_REMINDERS: Int = 200

    /** Due from [LEAD_MILLIS] before the start until the programme ends. */
    fun isDue(reminder: Reminder, nowMillis: Long): Boolean =
        nowMillis >= reminder.startMillis - LEAD_MILLIS && nowMillis < reminder.endMillis

    fun isExpired(reminder: Reminder, nowMillis: Long): Boolean = reminder.endMillis <= nowMillis

    /** The earliest-starting due reminder, or null. */
    fun nextDue(reminders: List<Reminder>, nowMillis: Long): Reminder? =
        reminders.filter { isDue(it, nowMillis) }.minWithOrNull(compareBy({ it.startMillis }, { it.channelId }))

    /** Drops expired and invalid entries and duplicates (last one wins), sorts by start, caps the size. */
    fun prune(reminders: List<Reminder>, nowMillis: Long): List<Reminder> {
        val byKey = LinkedHashMap<String, Reminder>()
        for (r in reminders) {
            if (r.channelId.isEmpty() || r.endMillis <= r.startMillis || isExpired(r, nowMillis)) continue
            byKey[r.key] = r
        }
        return byKey.values
            .sortedWith(compareBy({ it.startMillis }, { it.channelId }))
            .take(MAX_REMINDERS)
    }

    fun add(reminders: List<Reminder>, reminder: Reminder, nowMillis: Long): List<Reminder> =
        prune(reminders + reminder, nowMillis)

    fun remove(reminders: List<Reminder>, channelId: String, startMillis: Long, nowMillis: Long): List<Reminder> =
        prune(reminders.filterNot { it.channelId == channelId && it.startMillis == startMillis }, nowMillis)

    // --- Storage format: one reminder per line, tab-separated, "\\", "\t", "\n", "\r" escaped. ---

    private const val VERSION = "v1"

    fun encode(reminders: List<Reminder>): String = buildString {
        append(VERSION)
        for (r in reminders) {
            append('\n')
            append(escape(r.channelId)).append('\t')
            append(escape(r.epgId ?: "")).append('\t')
            append(if (r.epgId == null) '0' else '1').append('\t')
            append(escape(r.title)).append('\t')
            append(r.startMillis).append('\t')
            append(r.endMillis).append('\t')
            append(escape(r.channelName))
        }
    }

    /** Malformed lines are skipped; unknown versions decode to an empty list. */
    fun decode(text: String?): List<Reminder> {
        if (text.isNullOrEmpty()) return emptyList()
        val lines = text.split('\n')
        if (lines.first() != VERSION) return emptyList()
        val out = ArrayList<Reminder>(lines.size - 1)
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            if (f.size < 7) continue
            val start = f[4].toLongOrNull() ?: continue
            val end = f[5].toLongOrNull() ?: continue
            val channelId = unescape(f[0])
            if (channelId.isEmpty()) continue
            out += Reminder(
                channelId = channelId,
                epgId = if (f[2] == "1") unescape(f[1]) else null,
                title = unescape(f[3]),
                startMillis = start,
                endMillis = end,
                channelName = unescape(f[6]),
            )
        }
        return out
    }

    private fun escape(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
