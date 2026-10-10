package app.novaiptv.core.player

import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.EpgProgram

/**
 * Rules for rewinding live TV through the provider's catch-up archive ("timeshift").
 * Pure Kotlin, unit-tested.
 *
 * Only channels whose source declares catch-up ([CatchupCapability.Supported]) can be rewound;
 * nothing is invented for other channels. While timeshifted, the player plays the archive of the
 * programme containing the target wall-clock time, so "position in programme" and "wall clock"
 * convert with the programme start.
 */
object LiveTimeshift {
    /** A target within this distance of now counts as the live edge (smaller than the 10 s step back). */
    const val LIVE_EDGE_MARGIN_MS: Long = 5_000L

    /** Pausing live longer than this resumes from the archive at the paused moment. */
    const val RESUME_FROM_ARCHIVE_AFTER_MS: Long = 5_000L

    /** Programmes whose start is at most this far after the previous end count as consecutive. */
    const val NEXT_PROGRAM_GAP_MS: Long = 5 * 60_000L

    private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    fun canTimeshift(capability: CatchupCapability): Boolean = capability is CatchupCapability.Supported

    /** Earliest wall-clock time the archive declares (null = no archive). */
    fun earliestMillis(capability: CatchupCapability, nowMillis: Long): Long? {
        val supported = capability as? CatchupCapability.Supported ?: return null
        return nowMillis - supported.windowDays * DAY_MILLIS
    }

    fun isAtLiveEdge(targetWallMillis: Long, nowMillis: Long): Boolean =
        targetWallMillis >= nowMillis - LIVE_EDGE_MARGIN_MS

    fun wallClockAt(programStartMillis: Long, positionMillis: Long): Long = programStartMillis + positionMillis

    /** How far playback is behind the live edge (never negative). */
    fun behindLiveMillis(nowMillis: Long, programStartMillis: Long, positionMillis: Long): Long =
        (nowMillis - wallClockAt(programStartMillis, positionMillis)).coerceAtLeast(0L)

    /** Start position inside the programme's archive for a wall-clock target. */
    fun startPositionMillis(targetWallMillis: Long, programStartMillis: Long): Long =
        (targetWallMillis - programStartMillis).coerceAtLeast(0L)

    /**
     * Target when the user steps back from live: now - stepBack, at least [MIN_STEP_BACK_MS] because
     * a running programme's archive usually lags real time; a smaller step would hit the end of the
     * archive at once and bounce back to live.
     */
    fun stepBackTarget(nowMillis: Long, stepBackMillis: Long): Long =
        nowMillis - stepBackMillis.coerceAtLeast(MIN_STEP_BACK_MS)

    const val MIN_STEP_BACK_MS: Long = 30_000L

    fun shouldResumeFromArchive(pausedAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - pausedAtMillis >= RESUME_FROM_ARCHIVE_AFTER_MS

    /** The programme airing at [millis], or null when the guide has a hole there. */
    fun programAt(programs: List<EpgProgram>, millis: Long): EpgProgram? =
        programs.filter { millis >= it.startMillis && millis < it.endMillis }.minByOrNull { it.startMillis }

    /** The programme that follows [current] (allowing a small gap), or null. */
    fun nextProgram(programs: List<EpgProgram>, current: EpgProgram): EpgProgram? =
        programs.asSequence()
            .filter { it.startMillis >= current.endMillis && it.startMillis - current.endMillis <= NEXT_PROGRAM_GAP_MS }
            .minByOrNull { it.startMillis }

    /** What to do when the archive of [current] ends during timeshift. */
    sealed interface OnEnded {
        data class ContinueWith(val program: EpgProgram) : OnEnded
        data object BackToLive : OnEnded
    }

    /**
     * Continues with the next programme's archive when it started early enough to be behind live,
     * otherwise returns to live (also when the archive ran out because playback caught up).
     */
    fun onArchiveEnded(current: EpgProgram, programs: List<EpgProgram>, nowMillis: Long): OnEnded {
        val next = nextProgram(programs, current) ?: return OnEnded.BackToLive
        return if (isAtLiveEdge(next.startMillis, nowMillis)) OnEnded.BackToLive else OnEnded.ContinueWith(next)
    }
}
