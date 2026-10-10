package app.novaiptv.core.data

import app.novaiptv.core.data.logic.CatchupWindow
import app.novaiptv.core.data.logic.ContinueWatching
import app.novaiptv.core.data.logic.DAY_MILLIS
import app.novaiptv.core.data.logic.EpgWindow
import app.novaiptv.core.data.logic.NowNextSelector
import app.novaiptv.core.data.logic.Reorder
import app.novaiptv.core.data.logic.StreamFormats
import app.novaiptv.core.data.logic.XtreamPlaceholders
import app.novaiptv.core.data.logic.effectiveEpgId
import app.novaiptv.core.data.logic.isStale
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.WatchHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRulesTest {
    private val now = 1_700_000_000_000L
    private val min = 60_000L

    // ---- catch-up window -------------------------------------------------------------------

    @Test
    fun catchupWindow() {
        assertTrue(CatchupWindow.isAvailable(now - 60 * min, 1, now))
        assertTrue("live programme can restart", CatchupWindow.isAvailable(now - 1, 1, now))
        assertTrue("exact edge", CatchupWindow.isAvailable(now - 3 * DAY_MILLIS, 3, now))
        assertFalse(CatchupWindow.isAvailable(now - 3 * DAY_MILLIS - 1, 3, now))
        assertFalse("future", CatchupWindow.isAvailable(now + 1, 7, now))
        assertFalse("no archive", CatchupWindow.isAvailable(now - min, 0, now))
    }

    @Test
    fun durationRoundsUp() {
        assertEquals(60, CatchupWindow.durationMinutes(0, 60 * min))
        assertEquals(61, CatchupWindow.durationMinutes(0, 60 * min + 1))
        assertEquals(1, CatchupWindow.durationMinutes(0, 0))
        assertEquals(1, CatchupWindow.durationMinutes(100, 50))
    }

    // ---- EPG window ------------------------------------------------------------------------

    @Test
    fun epgWindowKeepsOneDayBackOrCatchupDays() {
        assertEquals(DAY_MILLIS, EpgWindow.backMillis(0))
        assertEquals(7 * DAY_MILLIS, EpgWindow.backMillis(7))
        val back = EpgWindow.backMillis(0)
        assertTrue(EpgWindow.keep(now - 2 * 60 * min, now - 60 * min, now, back))
        assertFalse(EpgWindow.keep(now - 2 * DAY_MILLIS, now - DAY_MILLIS - 1, now, back))
        assertTrue(EpgWindow.keep(now - 3 * DAY_MILLIS, now - 2 * DAY_MILLIS, now, EpgWindow.backMillis(3)))
        assertTrue(EpgWindow.keep(now + 6 * DAY_MILLIS, now + 6 * DAY_MILLIS + 60 * min, now, back))
        assertFalse(EpgWindow.keep(now + 7 * DAY_MILLIS, now + 7 * DAY_MILLIS + 60 * min, now, back))
        // Purge never cuts into the 14-day default archive window (unknown day counts are stored as 0).
        assertEquals(now - 15 * DAY_MILLIS, EpgWindow.purgeBefore(now, 7))
        assertEquals(now - 15 * DAY_MILLIS, EpgWindow.purgeBefore(now, 0))
        assertEquals(now - 22 * DAY_MILLIS, EpgWindow.purgeBefore(now, 21))
    }

    @Test
    fun epgWindowKeepsFourteenDaysForDeclaredArchives() {
        assertEquals(14 * DAY_MILLIS, EpgWindow.backMillis(14))
        assertEquals("capped", 30 * DAY_MILLIS, EpgWindow.backMillis(365))
        assertEquals("no catch-up declared", DAY_MILLIS, EpgWindow.backMillis(false, 0))
        assertEquals("no catch-up declared ignores days", DAY_MILLIS, EpgWindow.backMillis(false, 14))
        assertEquals("declared, unknown days", 14 * DAY_MILLIS, EpgWindow.backMillis(true, 0))
        assertEquals("declared, 3 days", 3 * DAY_MILLIS, EpgWindow.backMillis(true, 3))
        assertEquals(DAY_MILLIS, EpgWindow.backMillis(CatchupCapability.None))
        assertEquals(14 * DAY_MILLIS, EpgWindow.backMillis(CatchupCapability.Supported(CatchupType.Default, 0)))
        assertEquals(21 * DAY_MILLIS, EpgWindow.backMillis(CatchupCapability.Supported(CatchupType.Xtream, 21)))
        val back = EpgWindow.backMillis(true, 14)
        assertTrue(EpgWindow.keep(now - 14 * DAY_MILLIS + min, now - 14 * DAY_MILLIS + 60 * min, now, back))
        assertFalse(EpgWindow.keep(now - 15 * DAY_MILLIS, now - 14 * DAY_MILLIS - 1, now, back))
    }

    // ---- now / next ------------------------------------------------------------------------

    private fun prog(id: String, startMin: Long, endMin: Long, title: String = "$id@$startMin") =
        EpgProgram(id, now + startMin * min, now + endMin * min, title)

    @Test
    fun nowNextPicksCurrentAndFollowing() {
        val programs = listOf(
            prog("a", 30, 60), prog("a", -30, 30), prog("a", -90, -30), prog("a", 60, 90),
            prog("b", 10, 20), // nothing live yet
            prog("c", -60, -10), // only past
        )
        val result = NowNextSelector.select(programs, now)
        assertEquals(now - 30 * min, result.getValue("a").now!!.startMillis)
        assertEquals(now + 30 * min, result.getValue("a").next!!.startMillis)
        assertNull(result.getValue("b").now)
        assertEquals(now + 10 * min, result.getValue("b").next!!.startMillis)
        assertFalse(result.containsKey("c"))
    }

    @Test
    fun nowNextBoundaryIsHalfOpen() {
        val result = NowNextSelector.select(listOf(prog("a", -30, 0), prog("a", 0, 30)), now)
        assertEquals(now, result.getValue("a").now!!.startMillis)
        assertNull(result.getValue("a").next)
    }

    // ---- continue watching -----------------------------------------------------------------

    private fun h(type: ContentType, id: String, pos: Long, dur: Long, at: Long, parent: String? = null) =
        WatchHistory(type, id, 1, id, null, pos, dur, at, parent)

    @Test
    fun continueWatchingFiltersChannelsFinishedAndDuplicateSeries() {
        val newestFirst = listOf(
            h(ContentType.Channel, "c1", 0, 0, 10),
            h(ContentType.Episode, "e2", 100, 1000, 9, parent = "s1"),
            h(ContentType.Movie, "m1", 960, 1000, 8), // finished (96 %)
            h(ContentType.Episode, "e1", 500, 1000, 7, parent = "s1"), // older episode of same series
            h(ContentType.Movie, "m2", 10, 1000, 6),
            h(ContentType.Series, "s2", 10, 0, 5),
        )
        val result = ContinueWatching.select(newestFirst, 10)
        assertEquals(listOf("e2", "m2", "s2"), result.map { it.contentId })
        assertEquals(listOf("e2"), ContinueWatching.select(newestFirst, 1).map { it.contentId })
        assertEquals(emptyList<WatchHistory>(), ContinueWatching.select(newestFirst, 0))
    }

    // ---- favourites reorder ----------------------------------------------------------------

    @Test
    fun reorder() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), Reorder.move(ids, "c", 0))
        assertEquals(listOf("b", "c", "d", "a"), Reorder.move(ids, "a", 99))
        assertEquals(listOf("a", "b", "c", "d"), Reorder.move(ids, "x", 0))
        assertEquals(listOf("b", "a", "c", "d"), Reorder.move(ids, "a", 1))
        assertEquals(listOf("a", "b", "c", "d"), Reorder.move(ids, "a", -5))
    }

    // ---- Xtream placeholders ---------------------------------------------------------------

    @Test
    fun placeholderSubstitutionEncodesPathSegments() {
        val url = "http://p:8080/live/{xtream_user}/{xtream_pass}/101.ts"
        assertTrue(XtreamPlaceholders.contains(url))
        assertFalse(XtreamPlaceholders.contains("http://p/live/a/b/1.ts"))
        assertEquals("http://p:8080/live/alice/pw/101.ts", XtreamPlaceholders.substitute(url, "alice", "pw"))
        assertEquals(
            "http://p:8080/live/a%20b/p%2Fw%3F%23%26%2B%25/101.ts",
            XtreamPlaceholders.substitute(url, "a b", "p/w?#&+%"),
        )
        assertEquals("%C5%BEmogus", XtreamPlaceholders.encodePathSegment("žmogus"))
    }

    // ---- misc ------------------------------------------------------------------------------

    @Test
    fun streamFormatFromUrl() {
        assertEquals(StreamFormat.MpegTs, StreamFormats.fromUrl("http://x/timeshift/u/p/60/2024-01-01:10-00/5.ts", StreamFormat.Hls))
        assertEquals(StreamFormat.Hls, StreamFormats.fromUrl("http://x/a.m3u8?utc=1&lutc=2", StreamFormat.Unknown))
        assertEquals(StreamFormat.Hls, StreamFormats.fromUrl("http://x/archive-1-60.M3U8", StreamFormat.Unknown))
        assertEquals(StreamFormat.Dash, StreamFormats.fromUrl("http://x/a.mpd", StreamFormat.Unknown))
        assertEquals(StreamFormat.Hls, StreamFormats.fromUrl("http://x/stream", StreamFormat.Hls))
    }

    @Test
    fun effectiveEpgIdOrder() {
        assertEquals("m", effectiveEpgId("m", "r", "e"))
        assertEquals("r", effectiveEpgId(null, "r", "e"))
        assertEquals("e", effectiveEpgId(" ", "", "e"))
        assertNull(effectiveEpgId(null, null, ""))
    }

    @Test
    fun staleness() {
        val hour = 3_600_000L
        assertTrue(isStale(null, 24, now))
        assertFalse(isStale(null, 0, now))
        assertFalse(isStale(now - 23 * hour, 24, now))
        assertTrue(isStale(now - 24 * hour, 24, now))
    }
}
