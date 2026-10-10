package app.novaiptv.core.player

import app.novaiptv.core.player.SeekAccelerator.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekAcceleratorTest {

    @Test
    fun singlePressStepsTenSecondsAndCommitsTarget() {
        val acc = SeekAccelerator()
        assertEquals(70_000L, acc.press(1, Kind.Dpad, 0, 0, positionMillis = 60_000))
        assertTrue(acc.isActive)
        assertEquals(10_000L, acc.deltaMillis)
        assertEquals(70_000L, acc.commit())
        assertFalse(acc.isActive)
        assertNull("nothing pending after commit", acc.commit())
    }

    @Test
    fun mediaKeysStartAtThirtySeconds() {
        val acc = SeekAccelerator()
        assertEquals(30_000L, acc.press(-1, Kind.Media, 0, 0, positionMillis = 60_000))
        assertEquals(-30_000L, acc.deltaMillis)
    }

    @Test
    fun rapidTapsAccumulateIntoOneSeek() {
        val acc = SeekAccelerator()
        acc.press(1, Kind.Dpad, 0, 0, positionMillis = 0)
        acc.press(1, Kind.Dpad, 0, 200, positionMillis = 999_999) // anchor stays at the first position
        acc.press(1, Kind.Dpad, 0, 400, positionMillis = 999_999)
        assertEquals(30_000L, acc.commit())
    }

    @Test
    fun holdingAcceleratesTenThirtySixtyFiveMinutes() {
        val acc = SeekAccelerator()
        assertEquals(10_000L, acc.stepMillis(Kind.Dpad, 0))
        assertEquals(10_000L, acc.stepMillis(Kind.Dpad, 3))
        assertEquals(30_000L, acc.stepMillis(Kind.Dpad, 4))
        assertEquals(60_000L, acc.stepMillis(Kind.Dpad, 8))
        assertEquals(300_000L, acc.stepMillis(Kind.Dpad, 14))
        assertEquals(300_000L, acc.stepMillis(Kind.Dpad, 1_000))
        // Media keys start one tier higher and accelerate the same way.
        assertEquals(30_000L, acc.stepMillis(Kind.Media, 0))
        assertEquals(60_000L, acc.stepMillis(Kind.Media, 4))
        assertEquals(300_000L, acc.stepMillis(Kind.Media, 8))
    }

    @Test
    fun heldKeySequenceUsesGrowingSteps() {
        val acc = SeekAccelerator()
        var t = 0L
        acc.press(1, Kind.Dpad, 0, t, positionMillis = 0) // 10 s
        for (repeat in 1..16) {
            t += 150
            acc.press(1, Kind.Dpad, repeat, t, positionMillis = 0)
        }
        // initial 10 + repeats 1..3 (10 s) + 4..7 (30 s) + 8..13 (60 s) + 14..16 (5 min)
        val expected = 10_000L + 3 * 10_000L + 4 * 30_000L + 6 * 60_000L + 3 * 300_000L
        assertEquals(expected, acc.deltaMillis)
    }

    @Test
    fun repeatsFasterThanTheIntervalAreIgnored() {
        val acc = SeekAccelerator(minRepeatIntervalMillis = 120)
        acc.press(1, Kind.Dpad, 0, 1_000, positionMillis = 0)
        acc.press(1, Kind.Dpad, 1, 1_050, positionMillis = 0) // ignored (50 ms)
        acc.press(1, Kind.Dpad, 2, 1_100, positionMillis = 0) // ignored (100 ms)
        acc.press(1, Kind.Dpad, 3, 1_130, positionMillis = 0) // applied (130 ms)
        assertEquals(20_000L, acc.deltaMillis)
    }

    @Test
    fun targetIsClampedToBoundsAndReversalWorks() {
        val acc = SeekAccelerator()
        assertEquals(0L, acc.press(-1, Kind.Media, 0, 0, positionMillis = 5_000, minMillis = 0, maxMillis = 100_000))
        assertEquals(-5_000L, acc.deltaMillis)
        assertEquals(30_000L, acc.press(1, Kind.Media, 0, 500, positionMillis = 0, minMillis = 0, maxMillis = 100_000))
        assertEquals(100_000L, acc.press(1, Kind.Media, 0, 1_000, positionMillis = 0, minMillis = 0, maxMillis = 100_000).let {
            acc.press(1, Kind.Media, 0, 1_500, positionMillis = 0, minMillis = 0, maxMillis = 100_000)
            acc.press(1, Kind.Media, 0, 2_000, positionMillis = 0, minMillis = 0, maxMillis = 100_000)
        })
        assertEquals(100_000L, acc.commit())
    }

    @Test
    fun clampedToNoMovementCommitsNothing() {
        val acc = SeekAccelerator()
        // Live edge: anchor = now = max, RIGHT cannot move.
        assertEquals(1_000_000L, acc.press(1, Kind.Dpad, 0, 0, positionMillis = 1_000_000, minMillis = 0, maxMillis = 1_000_000))
        assertNull(acc.commit())
    }

    @Test
    fun cancelDropsPendingSeek() {
        val acc = SeekAccelerator()
        acc.press(1, Kind.Dpad, 0, 0, positionMillis = 0)
        acc.cancel()
        assertFalse(acc.isActive)
        assertNull(acc.commit())
    }

    @Test
    fun wallClockPositionsWork() {
        val now = 1_700_000_000_000L
        val acc = SeekAccelerator()
        assertEquals(now - 10_000, acc.press(-1, Kind.Dpad, 0, 0, positionMillis = now, minMillis = now - 14L * 86_400_000, maxMillis = now))
        assertEquals(now - 10_000, acc.commit())
    }

    @Test
    fun commitDelaysOutlastKeyRepeatTimeout() {
        assertTrue(SeekAccelerator.COMMIT_WHILE_HELD_MS > 500)
        assertEquals(400L, SeekAccelerator.COMMIT_AFTER_RELEASE_MS)
    }

    @Test
    fun formatsDelta() {
        assertEquals("+0:10", SeekAccelerator.formatDelta(10_000))
        assertEquals("-0:10", SeekAccelerator.formatDelta(-10_000))
        assertEquals("+1:30", SeekAccelerator.formatDelta(90_000))
        assertEquals("-10:00", SeekAccelerator.formatDelta(-600_000))
        assertEquals("+1:05:00", SeekAccelerator.formatDelta(3_900_000))
        assertEquals("+0:00", SeekAccelerator.formatDelta(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDirection() {
        SeekAccelerator().press(0, Kind.Dpad, 0, 0, positionMillis = 0)
    }
}
