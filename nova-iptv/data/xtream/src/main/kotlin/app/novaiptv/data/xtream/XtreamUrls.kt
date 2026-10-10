package app.novaiptv.data.xtream

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds Xtream stream URLs with credential placeholders. The literals equal
 * `StreamResolver.USER_PLACEHOLDER` / `PASS_PLACEHOLDER` (core:domain), which the resolver
 * substitutes with the real credentials just before playback, so the database never stores them.
 *
 * [serverUrl] is the normalised portal URL; stream ids are the raw Xtream ids (not app ids).
 */
object XtreamUrls {
    /** Must match app.novaiptv.core.domain.StreamResolver.USER_PLACEHOLDER (data:xtream does not depend on core:domain). */
    internal const val USER_PLACEHOLDER = app.novaiptv.core.common.StreamPlaceholders.USER

    /** Must match app.novaiptv.core.domain.StreamResolver.PASS_PLACEHOLDER. */
    internal const val PASS_PLACEHOLDER = app.novaiptv.core.common.StreamPlaceholders.PASS

    private val TIMESHIFT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

    fun live(serverUrl: String, streamId: String, ext: String = "m3u8"): String =
        "${base(serverUrl)}/live/$USER_PLACEHOLDER/$PASS_PLACEHOLDER/$streamId.$ext"

    fun movie(serverUrl: String, streamId: String, ext: String): String =
        "${base(serverUrl)}/movie/$USER_PLACEHOLDER/$PASS_PLACEHOLDER/$streamId.$ext"

    fun episode(serverUrl: String, episodeId: String, ext: String): String =
        "${base(serverUrl)}/series/$USER_PLACEHOLDER/$PASS_PLACEHOLDER/$episodeId.$ext"

    /** Xtream timeshift: /timeshift/{user}/{pass}/{durationMin}/{yyyy-MM-dd:HH-mm}/{streamId}.ts with start formatted in the server timezone (fallback UTC). Placeholders, not credentials. */
    fun timeshift(serverUrl: String, streamId: String, startMillis: Long, durationMinutes: Int, serverTimezone: String?): String {
        val zone = zoneOrUtc(serverTimezone)
        val start = TIMESHIFT_FORMAT.format(Instant.ofEpochMilli(startMillis).atZone(zone))
        return "${base(serverUrl)}/timeshift/$USER_PLACEHOLDER/$PASS_PLACEHOLDER/${durationMinutes.coerceAtLeast(1)}/$start/$streamId.ts"
    }

    internal fun zoneOrUtc(tz: String?): ZoneId {
        val t = tz?.trim().orEmpty()
        if (t.isEmpty()) return ZoneOffset.UTC
        return try {
            ZoneId.of(t)
        } catch (e: Exception) {
            ZoneOffset.UTC
        }
    }

    private fun base(serverUrl: String): String = serverUrl.trim().trimEnd('/')
}
