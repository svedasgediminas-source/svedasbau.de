package app.novaiptv.core.player

import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.player.PlaybackRecovery.Attempt
import app.novaiptv.core.player.PlaybackRecovery.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryTest {

    private val url = "http://host:8080/user/pass/101.ts"

    @Test
    fun xtreamHlsVariants() {
        assertEquals(
            listOf("http://host:8080/live/user/pass/101.m3u8", "http://host:8080/user/pass/101.m3u8"),
            PlaybackRecovery.hlsVariants(url),
        )
        assertEquals(
            listOf("https://h/live/u/p/7.m3u8", "https://h/u/p/7.m3u8"),
            PlaybackRecovery.hlsVariants("https://h/live/u/p/7"),
        )
        assertTrue(PlaybackRecovery.hlsVariants("http://h/movie/u/p/7.mp4").isEmpty())
        assertTrue(PlaybackRecovery.hlsVariants("http://h/series/u/p/7").isEmpty())
        assertTrue(PlaybackRecovery.hlsVariants("http://h/live/u/p/7.m3u8").isEmpty())
        assertTrue(PlaybackRecovery.hlsVariants("http://h/channel/abc").isEmpty())
    }

    @Test
    fun attemptListIsBoundedAndStartsWithTheOriginal() {
        val list = PlaybackRecovery.attempts(url, StreamFormat.MpegTs, uaCount = 3, xtreamVariants = true)
        assertEquals(Attempt(url, StreamFormat.MpegTs, 0), list[0])
        assertEquals(Attempt(url, StreamFormat.Hls, 0), list[1])
        assertEquals(StreamFormat.Hls, list[2].format)
        assertEquals(Attempt(url, StreamFormat.MpegTs, 1), list[4])
        assertEquals(Attempt(url, StreamFormat.Hls, 1), list[5])
        assertEquals(Attempt(url, StreamFormat.MpegTs, 2), list[6])
        assertEquals(8, list.size)
        assertTrue(list.size <= PlaybackRecovery.MAX_ATTEMPTS)
        val vod = PlaybackRecovery.attempts(url, StreamFormat.MpegTs, uaCount = 1, xtreamVariants = false)
        assertEquals(2, vod.size)
    }

    @Test
    fun forbiddenAndNoDataSwitchUserAgent() {
        val list = PlaybackRecovery.attempts(url, StreamFormat.MpegTs, uaCount = 2, xtreamVariants = true)
        assertEquals(4, PlaybackRecovery.next(list, 0, Failure.Forbidden))
        assertEquals(4, PlaybackRecovery.next(list, 0, Failure.NoData))
        assertNull(PlaybackRecovery.next(list, 4, Failure.Forbidden))
        assertEquals(5, PlaybackRecovery.next(list, 4, Failure.NoData))
        assertEquals(5, PlaybackRecovery.next(list, 4, Failure.Format))
        assertNull(PlaybackRecovery.next(list, 5, Failure.NoData))
        // Single agent: forbidden gives up, no data keeps walking.
        val one = PlaybackRecovery.attempts(url, StreamFormat.MpegTs, uaCount = 1, xtreamVariants = false)
        assertNull(PlaybackRecovery.next(one, 0, Failure.Forbidden))
        assertEquals(1, PlaybackRecovery.next(one, 0, Failure.NoData))
    }

    @Test
    fun formatAndStallWalkForwardOtherOnlyInsideTheLadder() {
        val list = PlaybackRecovery.attempts(url, StreamFormat.MpegTs, uaCount = 1, xtreamVariants = true)
        assertEquals(1, PlaybackRecovery.next(list, 0, Failure.Format))
        assertEquals(1, PlaybackRecovery.next(list, 0, Failure.Stall))
        assertNull(PlaybackRecovery.next(list, 0, Failure.Other))
        assertEquals(3, PlaybackRecovery.next(list, 2, Failure.Other))
        assertNull(PlaybackRecovery.next(list, list.lastIndex, Failure.Stall))
        assertNull(PlaybackRecovery.next(list, 99, Failure.Stall))
    }

    @Test
    fun neverLoops() {
        val list = PlaybackRecovery.attempts(url, StreamFormat.Unknown, uaCount = 3, xtreamVariants = true)
        for (failure in Failure.entries) {
            var index: Int? = 0
            var steps = 0
            while (index != null) {
                val next = PlaybackRecovery.next(list, index, failure)
                if (next != null) assertTrue(next > index)
                index = next
                steps++
            }
            assertTrue(steps <= list.size)
        }
    }
}
