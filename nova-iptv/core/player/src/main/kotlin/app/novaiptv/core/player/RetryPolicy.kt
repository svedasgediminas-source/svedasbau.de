package app.novaiptv.core.player

import app.novaiptv.core.model.PlaybackErrorKind

/**
 * Bounded automatic reconnect for live streams. Pure Kotlin (no Android/Media3), unit-tested.
 *
 * - Only [PlaybackErrorKind.Network] and [PlaybackErrorKind.SourceUnavailable] on live streams
 *   are retried; everything else fails immediately.
 * - Exponential backoff: baseDelay, 2x, 4x ... ([maxAttempts] attempts, default 1s/2s/4s).
 * - The attempt counter resets after [stableResetMs] of uninterrupted playback, so a stream that
 *   drops once an hour keeps reconnecting, while a dead one gives up after [maxAttempts].
 * - Never loops forever: once the budget is spent [onFailure] returns null until [reset] or a
 *   stable-playback reset.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 1_000,
    val stableResetMs: Long = 30_000,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts must be >= 0" }
        require(baseDelayMs > 0) { "baseDelayMs must be > 0" }
    }

    /** Number of retries scheduled since the last reset. */
    var attempts: Int = 0
        private set

    private var playingSinceMs: Long? = null

    fun isRetryable(kind: PlaybackErrorKind, isLive: Boolean): Boolean =
        isLive && (kind == PlaybackErrorKind.Network || kind == PlaybackErrorKind.SourceUnavailable)

    /**
     * Registers a failure. Returns the delay before the next attempt, or null when playback
     * should stop with an error (not retryable or budget exhausted).
     */
    fun onFailure(kind: PlaybackErrorKind, isLive: Boolean): Long? {
        playingSinceMs = null
        if (!isRetryable(kind, isLive) || attempts >= maxAttempts) return null
        val delay = baseDelayMs shl attempts.coerceAtMost(MAX_SHIFT)
        attempts++
        return delay
    }

    /** Call periodically while the player is actually playing (monotonic clock). */
    fun onPlaying(nowMs: Long) {
        val since = playingSinceMs
        if (since == null) {
            playingSinceMs = nowMs
        } else if (nowMs - since >= stableResetMs && attempts != 0) {
            attempts = 0
        }
    }

    /** Playback stopped (buffering, paused, error): the stable-playback window starts over. */
    fun onInterrupted() {
        playingSinceMs = null
    }

    fun reset() {
        attempts = 0
        playingSinceMs = null
    }

    private companion object {
        const val MAX_SHIFT = 20
    }
}
