package app.novaiptv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class XtreamUrlsTest {
    private val server = "http://portal.example:8080/"

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun placeholdersMatchStreamResolverContract() {
        assertEquals("{xtream_user}", XtreamUrls.USER_PLACEHOLDER)
        assertEquals("{xtream_pass}", XtreamUrls.PASS_PLACEHOLDER)
    }

    @Test
    fun liveMovieEpisode() {
        assertEquals("http://portal.example:8080/live/{xtream_user}/{xtream_pass}/42.m3u8", XtreamUrls.live(server, "42"))
        assertEquals("http://portal.example:8080/live/{xtream_user}/{xtream_pass}/42.ts", XtreamUrls.live(server, "42", "ts"))
        assertEquals("http://portal.example:8080/movie/{xtream_user}/{xtream_pass}/7.mkv", XtreamUrls.movie(server, "7", "mkv"))
        assertEquals("http://portal.example:8080/series/{xtream_user}/{xtream_pass}/9.mp4", XtreamUrls.episode(server, "9", "mp4"))
    }

    @Test
    fun timeshiftVilniusWinter() {
        // EET = UTC+2
        assertEquals(
            "http://portal.example:8080/timeshift/{xtream_user}/{xtream_pass}/60/2026-01-15:14-00/42.ts",
            XtreamUrls.timeshift(server, "42", ms("2026-01-15T12:00:00Z"), 60, "Europe/Vilnius"),
        )
    }

    @Test
    fun timeshiftVilniusSummer() {
        // EEST = UTC+3
        assertEquals(
            "http://portal.example:8080/timeshift/{xtream_user}/{xtream_pass}/45/2026-07-01:15-05/42.ts",
            XtreamUrls.timeshift(server, "42", ms("2026-07-01T12:05:00Z"), 45, "Europe/Vilnius"),
        )
    }

    @Test
    fun timeshiftAcrossDstSwitch() {
        // 2026-03-29 01:00 UTC: Vilnius jumps from 03:00 EET to 04:00 EEST.
        assertEquals(
            "http://portal.example:8080/timeshift/{xtream_user}/{xtream_pass}/30/2026-03-29:02-30/1.ts",
            XtreamUrls.timeshift(server, "1", ms("2026-03-29T00:30:00Z"), 30, "Europe/Vilnius"),
        )
        assertEquals(
            "http://portal.example:8080/timeshift/{xtream_user}/{xtream_pass}/30/2026-03-29:04-30/1.ts",
            XtreamUrls.timeshift(server, "1", ms("2026-03-29T01:30:00Z"), 30, "Europe/Vilnius"),
        )
        // Autumn: 2026-10-25 01:00 UTC back from 04:00 EEST to 03:00 EET.
        assertEquals(
            "http://portal.example:8080/timeshift/{xtream_user}/{xtream_pass}/30/2026-10-25:03-30/1.ts",
            XtreamUrls.timeshift(server, "1", ms("2026-10-25T00:30:00Z"), 30, "Europe/Vilnius"),
        )
        assertEquals(
            "http://portal.example:8080/timeshift/{xtream_user}/{xtream_pass}/30/2026-10-25:03-30/1.ts",
            XtreamUrls.timeshift(server, "1", ms("2026-10-25T01:30:00Z"), 30, "Europe/Vilnius"),
        )
    }

    @Test
    fun timeshiftFallsBackToUtc() {
        val t = ms("2026-01-15T12:00:00Z")
        val expected = "http://portal.example:8080/timeshift/{xtream_user}/{xtream_pass}/60/2026-01-15:12-00/42.ts"
        assertEquals(expected, XtreamUrls.timeshift(server, "42", t, 60, null))
        assertEquals(expected, XtreamUrls.timeshift(server, "42", t, 60, ""))
        assertEquals(expected, XtreamUrls.timeshift(server, "42", t, 60, "Not/AZone"))
    }
}
