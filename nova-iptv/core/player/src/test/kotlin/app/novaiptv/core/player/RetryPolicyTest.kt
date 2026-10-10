package app.novaiptv.core.player

import app.novaiptv.core.model.PlaybackErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun liveNetworkErrorsBackOffExponentiallyThenGiveUp() {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 1_000)
        assertEquals(1_000L, policy.onFailure(PlaybackErrorKind.Network, isLive = true))
        assertEquals(2_000L, policy.onFailure(PlaybackErrorKind.Network, isLive = true))
        assertEquals(4_000L, policy.onFailure(PlaybackErrorKind.SourceUnavailable, isLive = true))
        assertNull(policy.onFailure(PlaybackErrorKind.Network, isLive = true))
        assertEquals(3, policy.attempts)
    }

    @Test
    fun neverRetriesForever() {
        val policy = RetryPolicy()
        val scheduled = (1..1_000).count { policy.onFailure(PlaybackErrorKind.Network, isLive = true) != null }
        assertEquals(3, scheduled)
    }

    @Test
    fun vodErrorsAreNotRetried() {
        val policy = RetryPolicy()
        assertNull(policy.onFailure(PlaybackErrorKind.Network, isLive = false))
        assertNull(policy.onFailure(PlaybackErrorKind.SourceUnavailable, isLive = false))
        assertEquals(0, policy.attempts)
    }

    @Test
    fun nonTransientErrorsAreNotRetried() {
        val policy = RetryPolicy()
        for (kind in listOf(
            PlaybackErrorKind.UnsupportedFormat,
            PlaybackErrorKind.DrmProtected,
            PlaybackErrorKind.Decoder,
            PlaybackErrorKind.Unknown,
        )) {
            assertFalse(policy.isRetryable(kind, isLive = true))
            assertNull(policy.onFailure(kind, isLive = true))
        }
        assertTrue(policy.isRetryable(PlaybackErrorKind.Network, isLive = true))
    }

    @Test
    fun resetsAfterThirtySecondsOfStablePlayback() {
        val policy = RetryPolicy()
        policy.onFailure(PlaybackErrorKind.Network, isLive = true)
        policy.onFailure(PlaybackErrorKind.Network, isLive = true)
        policy.onPlaying(nowMs = 100_000)
        policy.onPlaying(nowMs = 129_999)
        assertEquals(2, policy.attempts)
        policy.onPlaying(nowMs = 130_000)
        assertEquals(0, policy.attempts)
        assertEquals(1_000L, policy.onFailure(PlaybackErrorKind.Network, isLive = true))
    }

    @Test
    fun interruptionRestartsTheStableWindow() {
        val policy = RetryPolicy()
        policy.onFailure(PlaybackErrorKind.Network, isLive = true)
        policy.onPlaying(nowMs = 0)
        policy.onPlaying(nowMs = 20_000)
        policy.onInterrupted()
        policy.onPlaying(nowMs = 25_000)
        policy.onPlaying(nowMs = 40_000)
        assertEquals(1, policy.attempts)
        policy.onPlaying(nowMs = 55_000)
        assertEquals(0, policy.attempts)
    }

    @Test
    fun failureRestartsTheStableWindow() {
        val policy = RetryPolicy()
        policy.onPlaying(nowMs = 0)
        policy.onFailure(PlaybackErrorKind.Network, isLive = true)
        policy.onPlaying(nowMs = 31_000) // first tick after recovery starts a new window
        assertEquals(1, policy.attempts)
    }

    @Test
    fun manualResetRestoresBudget() {
        val policy = RetryPolicy(maxAttempts = 1)
        assertEquals(1_000L, policy.onFailure(PlaybackErrorKind.Network, isLive = true))
        assertNull(policy.onFailure(PlaybackErrorKind.Network, isLive = true))
        policy.reset()
        assertEquals(1_000L, policy.onFailure(PlaybackErrorKind.Network, isLive = true))
    }

    @Test
    fun zeroAttemptsNeverRetries() {
        assertNull(RetryPolicy(maxAttempts = 0).onFailure(PlaybackErrorKind.Network, isLive = true))
    }
}
