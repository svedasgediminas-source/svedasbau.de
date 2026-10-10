package app.novaiptv.core.player

/**
 * Accelerating, deferred seeking for the remote's LEFT/RIGHT and FF/REW keys. Pure Kotlin,
 * unit-tested; it never touches the player.
 *
 * A "session" starts with the first key press: the current position is the anchor and every
 * press/repeat adds a step to a pending delta. The player is only asked to seek when the session
 * is committed (the caller does that after the key is released or after a short pause, see
 * [COMMIT_AFTER_RELEASE_MS] / [COMMIT_WHILE_HELD_MS]), so holding a key does not re-buffer the
 * stream on every repeat event.
 *
 * Holding a key accelerates: the step grows 10 s -> 30 s -> 60 s -> 5 min with the number of
 * repeat steps applied while the key is held ([Kind.Media] starts at 30 s). Repeat events that
 * arrive faster than [minRepeatIntervalMillis] are ignored, so the speed does not depend on the
 * remote's key-repeat rate.
 *
 * Positions are opaque millis: a media position for VOD/catch-up, or wall-clock time for live
 * timeshift. The target is always clamped into the bounds given with each press.
 */
class SeekAccelerator(
    private val tierSteps: LongArray = DEFAULT_TIER_STEPS,
    /** Number of applied repeat steps (while held) at which tier i begins; must start with 0. */
    private val tierThresholds: IntArray = DEFAULT_TIER_THRESHOLDS,
    private val minRepeatIntervalMillis: Long = DEFAULT_MIN_REPEAT_INTERVAL_MS,
) {
    init {
        require(tierSteps.isNotEmpty() && tierSteps.size == tierThresholds.size) { "one threshold per tier" }
        require(tierThresholds[0] == 0) { "first tier must start at 0" }
        require(tierSteps.all { it > 0 }) { "steps must be > 0" }
    }

    /** Initial step size: D-pad starts at 10 s, media FF/REW keys at 30 s. */
    enum class Kind(val firstTier: Int) { Dpad(0), Media(1) }

    var isActive: Boolean = false
        private set

    /** Position when the session started. */
    var anchorMillis: Long = 0L
        private set

    /** Pending (clamped) offset from [anchorMillis]. */
    var deltaMillis: Long = 0L
        private set

    val targetMillis: Long get() = anchorMillis + deltaMillis

    /** Repeat steps applied since the key went down (resets on every new press). */
    private var heldSteps = 0
    private var lastStepAtMillis = Long.MIN_VALUE

    /** Step for the given number of repeat steps already applied while holding the key. */
    fun stepMillis(kind: Kind, heldSteps: Int): Long {
        var tier = 0
        for (i in tierThresholds.indices) if (heldSteps >= tierThresholds[i]) tier = i
        return tierSteps[(tier + kind.firstTier).coerceAtMost(tierSteps.lastIndex)]
    }

    /**
     * Handles one key-down event ([repeatCount] 0 = new press, > 0 = auto-repeat) at
     * [eventTimeMillis] (any monotonic clock, e.g. KeyEvent.eventTime). [positionMillis] is used as
     * the anchor when no session is active. Returns the (clamped) target.
     */
    fun press(
        direction: Int,
        kind: Kind,
        repeatCount: Int,
        eventTimeMillis: Long,
        positionMillis: Long,
        minMillis: Long = 0L,
        maxMillis: Long = Long.MAX_VALUE,
    ): Long {
        require(direction == 1 || direction == -1) { "direction must be +1 or -1" }
        if (!isActive) {
            isActive = true
            anchorMillis = positionMillis
            deltaMillis = 0L
        }
        if (repeatCount <= 0) {
            heldSteps = 0
        } else {
            if (lastStepAtMillis != Long.MIN_VALUE && eventTimeMillis - lastStepAtMillis < minRepeatIntervalMillis) {
                return targetMillis
            }
            heldSteps++
        }
        lastStepAtMillis = eventTimeMillis
        val upper = maxOf(minMillis, maxMillis)
        val step = stepMillis(kind, if (repeatCount <= 0) 0 else heldSteps)
        val raw = saturatingAdd(targetMillis, direction * step)
        val clamped = raw.coerceIn(minMillis, upper)
        deltaMillis = clamped - anchorMillis
        return clamped
    }

    /** Ends the session. Returns the target to seek to, or null when nothing would change. */
    fun commit(): Long? {
        if (!isActive) return null
        val target = targetMillis
        val moved = deltaMillis != 0L
        reset()
        return if (moved) target else null
    }

    fun cancel() = reset()

    private fun reset() {
        isActive = false
        anchorMillis = 0L
        deltaMillis = 0L
        heldSteps = 0
        lastStepAtMillis = Long.MIN_VALUE
    }

    private fun saturatingAdd(a: Long, b: Long): Long {
        val r = a + b
        // Overflow only if both have the same sign and the result's sign differs.
        return if (((a xor r) and (b xor r)) < 0) (if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE) else r
    }

    companion object {
        val DEFAULT_TIER_STEPS = longArrayOf(10_000L, 30_000L, 60_000L, 300_000L)
        val DEFAULT_TIER_THRESHOLDS = intArrayOf(0, 4, 8, 14)
        const val DEFAULT_MIN_REPEAT_INTERVAL_MS = 120L

        /** Commit this long after the seek key is released (rapid taps keep accumulating). */
        const val COMMIT_AFTER_RELEASE_MS = 400L

        /**
         * Safety commit while a key is still down (a lost key-up). Longer than Android's key-repeat
         * timeout (500 ms) so holding a key never commits before the first repeat arrives.
         */
        const val COMMIT_WHILE_HELD_MS = 700L

        /** "+1:30", "-0:10", "+1:05:00". */
        fun formatDelta(deltaMillis: Long): String {
            val sign = if (deltaMillis < 0) "-" else "+"
            val totalSeconds = kotlin.math.abs(deltaMillis) / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                sign + hours + ":" + two(minutes) + ":" + two(seconds)
            } else {
                sign + minutes + ":" + two(seconds)
            }
        }

        private fun two(v: Long): String = if (v < 10) "0$v" else v.toString()
    }
}
