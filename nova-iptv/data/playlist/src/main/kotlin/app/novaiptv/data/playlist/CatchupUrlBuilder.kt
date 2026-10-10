package app.novaiptv.data.playlist

import app.novaiptv.core.common.UrlValidator
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Builds archive (catch-up) URLs for M3U channels from what the playlist declared.
 *
 * Supported template placeholders (all times UTC, epoch values in seconds):
 * - programme start: `{utc}`, `{start}`, `${start}`
 * - programme end: `{utcend}`, `{end}`, `${end}`
 * - now: `{lutc}`, `{now}`, `${now}`, `${timestamp}`, `{timestamp}`
 * - `{duration}`, `${duration}` (seconds); `{duration:60}` divides (=> minutes)
 * - `{offset}`, `${offset}` (seconds between start and now); `{offset:60}` divides
 * - `{Y} {m} {d} {H} {M} {S}` start date/time parts
 * - formatted times: `{utc:Y-m-d H:M:S}`, `${start:YmdHMS}`, `{end:...}`, `{now:...}` …
 * Unknown placeholders are left untouched.
 *
 * `days == 0` means "window unknown": no window check is applied and the server decides.
 */
object CatchupUrlBuilder {
    private const val DAY_MILLIS = 86_400_000L
    private val PLACEHOLDER = Regex("""\$?\{([A-Za-z]+)(?::([^{}]*))?\}""")
    private val XTREAM_URL = Regex("""^(https?://[^/?#]+)/(?:live/)?([^/?#]+)/([^/?#]+)/(\d+)(?:\.[A-Za-z0-9]+)?(?:[?#].*)?$""")

    /** Returns the archive URL for an M3U channel, or null if not supported / outside the archive window / programme not finished starting. Never invents archive support. */
    fun build(channel: Channel, program: EpgProgram, nowMillis: Long): String? {
        val cap = channel.catchup as? CatchupCapability.Supported ?: return null
        if (program.startMillis >= nowMillis) return null
        if (program.endMillis <= program.startMillis) return null
        if (cap.days > 0 && program.startMillis < nowMillis - cap.days.toLong() * DAY_MILLIS) return null

        val base = channel.stream.url
        val t = Times(program.startMillis / 1000, program.endMillis / 1000, nowMillis / 1000)
        val template = cap.sourceTemplate?.trim()?.takeIf { it.isNotEmpty() }

        val url = when (cap.type) {
            CatchupType.Default -> template?.let { if (isAbsolute(it)) fill(it, t) else base + fill(it, t) }
            CatchupType.Append -> template?.let { if (isAbsolute(it)) fill(it, t) else base + fill(it, t) }
            CatchupType.Shift -> appendQuery(base, "utc=${t.start}&lutc=${t.now}")
            CatchupType.Flussonic -> template?.takeIf(::isAbsolute)?.let { fill(it, t) } ?: flussonic(base, t)
            CatchupType.Xtream -> template?.let { if (isAbsolute(it)) fill(it, t) else base + fill(it, t) } ?: xtream(base, t)
        } ?: return null
        return url.takeIf { UrlValidator.isValidStreamUrl(it) }
    }

    private class Times(val start: Long, val end: Long, val now: Long) {
        val duration get() = end - start
        val offset get() = now - start
    }

    private fun isAbsolute(s: String) = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(s)

    private fun appendQuery(url: String, query: String): String {
        val hash = url.indexOf('#')
        val (u, frag) = if (hash >= 0) url.substring(0, hash) to url.substring(hash) else url to ""
        val sep = when {
            !u.contains('?') -> "?"
            u.endsWith("?") || u.endsWith("&") -> ""
            else -> "&"
        }
        return u + sep + query + frag
    }

    private fun fill(template: String, t: Times): String = PLACEHOLDER.replace(template) { m ->
        val name = m.groupValues[1]
        val arg = m.groups[2]?.value
        when (name) {
            "utc", "start" -> timeValue(t.start, arg)
            "utcend", "end" -> timeValue(t.end, arg)
            "lutc", "now", "timestamp" -> timeValue(t.now, arg)
            "duration" -> divided(t.duration, arg)
            "offset" -> divided(t.offset, arg)
            "Y", "m", "d", "H", "M", "S" -> if (arg == null) format(t.start, name) else m.value
            else -> m.value
        }
    }

    private fun timeValue(epochSeconds: Long, arg: String?): String =
        if (arg.isNullOrEmpty()) epochSeconds.toString() else format(epochSeconds, arg)

    private fun divided(seconds: Long, arg: String?): String {
        val d = arg?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        return (seconds / d).toString()
    }

    /** Formats with Kodi-style letters: Y year, m month, d day, H hour, M minute, S second (UTC). */
    private fun format(epochSeconds: Long, pattern: String): String {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                'Y' -> sb.append(z.year.toString().padStart(4, '0'))
                'm' -> sb.append(two(z.monthValue))
                'd' -> sb.append(two(z.dayOfMonth))
                'H' -> sb.append(two(z.hour))
                'M' -> sb.append(two(z.minute))
                'S' -> sb.append(two(z.second))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun two(v: Int) = v.toString().padStart(2, '0')

    /**
     * Flussonic conventions:
     * - `.../name.m3u8` (index, mono, video, …) -> `.../name-<start>-<duration>.m3u8`
     * - `.../mpegts` or `.../name.ts` -> `.../timeshift_abs-<start>.ts`
     */
    private fun flussonic(base: String, t: Times): String? {
        val cut = base.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) base.length else it }
        val path = base.substring(0, cut)
        val query = base.substring(cut)
        val schemeEnd = path.indexOf("://")
        val slash = path.lastIndexOf('/')
        if (schemeEnd < 0 || slash <= schemeEnd + 2) return null
        val dir = path.substring(0, slash + 1)
        val last = path.substring(slash + 1)
        return when {
            last.endsWith(".m3u8", ignoreCase = true) && last.length > 5 ->
                dir + last.dropLast(5) + "-${t.start}-${t.duration}.m3u8" + query
            last.equals("mpegts", ignoreCase = true) || last.endsWith(".ts", ignoreCase = true) ->
                dir + "timeshift_abs-${t.start}.ts" + query
            else -> null
        }
    }

    /**
     * Xtream Codes archive URL derived from an M3U line pointing at an Xtream portal
     * (`http://host/[live/]user/pass/<id>[.ext]`):
     * `http://host/timeshift/user/pass/<minutes>/<Y-m-d:H-M>/<id>.ts` (UTC).
     */
    private fun xtream(base: String, t: Times): String? {
        val m = XTREAM_URL.matchEntire(base) ?: return null
        val (host, user, pass, id) = m.destructured
        val minutes = (t.duration + 59) / 60
        return "$host/timeshift/$user/$pass/$minutes/${format(t.start, "Y-m-d:H-M")}/$id.ts"
    }
}
