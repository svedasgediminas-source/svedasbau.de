package app.novaiptv.core.player

import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTimeshiftTest {
    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val day = 24 * 60 * min

    private fun prog(startMin: Long, endMin: Long, title: String = "P$startMin") =
        EpgProgram("ch", now + startMin * min, now + endMin * min, title)

    @Test
    fun onlyDeclaredCatchupCanTimeshift() {
        assertFalse(LiveTimeshift.canTimeshift(CatchupCapability.None))
        assertNull(LiveTimeshift.earliestMillis(CatchupCapability.None, now))
        assertTrue(LiveTimeshift.canTimeshift(CatchupCapability.Supported(CatchupType.Default, 3)))
    }

    @Test
    fun archiveWindowFollowsTheProvider() {
        assertEquals(now - 14 * day, LiveTimeshift.earliestMillis(CatchupCapability.Supported(CatchupType.Xtream, 14), now))
        assertEquals(now - 3 * day, LiveTimeshift.earliestMillis(CatchupCapability.Supported(CatchupType.Default, 3), now))
        // Declared without a day count: the 14-day default window.
        assertEquals(now - 14 * day, LiveTimeshift.earliestMillis(CatchupCapability.Supported(CatchupType.Default, 0), now))
    }

    @Test
    fun behindLiveAndStartPosition() {
        val start = now - 30 * min
        // A 10 s step from live is raised to the 30 s minimum (archive lag).
        val target = LiveTimeshift.stepBackTarget(now, 10_000)
        assertEquals(30 * min - 30_000, LiveTimeshift.startPositionMillis(target, start))
        assertEquals(now - 60_000, LiveTimeshift.stepBackTarget(now, 60_000))
        assertEquals(10_000L, LiveTimeshift.behindLiveMillis(now, start, 30 * min - 10_000))
        assertEquals("never negative", 0L, LiveTimeshift.behindLiveMillis(now, start, 31 * min))
        assertEquals("target before programme start clamps", 0L, LiveTimeshift.startPositionMillis(start - 1_000, start))
        assertEquals(start + 5_000, LiveTimeshift.wallClockAt(start, 5_000))
    }

    @Test
    fun liveEdge() {
        assertTrue(LiveTimeshift.isAtLiveEdge(now, now))
        assertTrue(LiveTimeshift.isAtLiveEdge(now - LiveTimeshift.LIVE_EDGE_MARGIN_MS, now))
        assertFalse("one 10 s step back is behind live", LiveTimeshift.isAtLiveEdge(now - 10_000, now))
    }

    @Test
    fun pauseResumesFromArchiveOnlyAfterAWhile() {
        assertFalse(LiveTimeshift.shouldResumeFromArchive(now - 2_000, now))
        assertTrue(LiveTimeshift.shouldResumeFromArchive(now - 60_000, now))
    }

    @Test
    fun findsProgrammeAtTime() {
        val programs = listOf(prog(-120, -60), prog(-60, 0), prog(0, 60))
        assertEquals(prog(-60, 0), LiveTimeshift.programAt(programs, now - 1))
        assertEquals(prog(0, 60), LiveTimeshift.programAt(programs, now))
        assertNull(LiveTimeshift.programAt(programs, now - 200 * min))
    }

    @Test
    fun archiveEndContinuesWithNextProgrammeOrReturnsToLive() {
        val programs = listOf(prog(-120, -60), prog(-60, 30), prog(30, 90))
        assertEquals(
            LiveTimeshift.OnEnded.ContinueWith(prog(-60, 30)),
            LiveTimeshift.onArchiveEnded(prog(-120, -60), programs, now),
        )
        // Next programme has not started (playback caught up with live): live.
        assertEquals(LiveTimeshift.OnEnded.BackToLive, LiveTimeshift.onArchiveEnded(prog(-60, 30), programs, now))
        // No next programme in the guide: live.
        assertEquals(LiveTimeshift.OnEnded.BackToLive, LiveTimeshift.onArchiveEnded(prog(-300, -240), programs, now))
    }

    @Test
    fun nextProgrammeToleratesSmallGaps() {
        val programs = listOf(prog(-120, -62), prog(-60, 0))
        assertEquals(prog(-60, 0), LiveTimeshift.nextProgram(programs, prog(-120, -62)))
        assertNull(LiveTimeshift.nextProgram(listOf(prog(-120, -80), prog(-60, 0)), prog(-120, -80)))
    }
}
