package app.novaiptv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedsTest {

    @Test
    fun offersTheUsualSpeeds() {
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), PlaybackSpeeds.OPTIONS)
        assertTrue(PlaybackSpeeds.NORMAL in PlaybackSpeeds.OPTIONS)
    }

    @Test
    fun snapsToNearestOption() {
        assertEquals(1.25f, PlaybackSpeeds.nearest(1.3f), 0f)
        assertEquals(2f, PlaybackSpeeds.nearest(8f), 0f)
        assertEquals(0.5f, PlaybackSpeeds.nearest(0.1f), 0f)
        assertEquals(1f, PlaybackSpeeds.nearest(0f), 0f)
        assertEquals(1f, PlaybackSpeeds.nearest(-2f), 0f)
        assertEquals(1f, PlaybackSpeeds.nearest(Float.NaN), 0f)
        assertEquals(1f, PlaybackSpeeds.nearest(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun liveAlwaysPlaysAtNormalSpeed() {
        assertEquals(1f, PlaybackSpeeds.effective(2f, isLive = true), 0f)
        assertEquals(2f, PlaybackSpeeds.effective(2f, isLive = false), 0f)
        assertEquals(0.75f, PlaybackSpeeds.effective(0.75f, isLive = false), 0f)
    }

    @Test
    fun labels() {
        assertEquals("0.5×", PlaybackSpeeds.label(0.5f))
        assertEquals("0.75×", PlaybackSpeeds.label(0.75f))
        assertEquals("1×", PlaybackSpeeds.label(1f))
        assertEquals("1.25×", PlaybackSpeeds.label(1.25f))
        assertEquals("2×", PlaybackSpeeds.label(2f))
        assertEquals("1,5×", PlaybackSpeeds.label(1.5f, ','))
    }

    @Test
    fun normalCheck() {
        assertTrue(PlaybackSpeeds.isNormal(1f))
        assertFalse(PlaybackSpeeds.isNormal(1.25f))
    }
}
