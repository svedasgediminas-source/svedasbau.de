package app.novaiptv.core.player

import java.math.BigDecimal
import kotlin.math.abs

/**
 * Playback speed choices. Pure Kotlin, unit-tested.
 *
 * Speed changes are offered for VOD, catch-up and timeshift (archive) playback only: the live
 * edge always plays at [NORMAL] (there is nothing ahead to fast-forward into, and slowing down
 * would drift behind live without the user noticing).
 */
object PlaybackSpeeds {
    const val NORMAL: Float = 1f

    /** Choices shown in the player settings panel, slowest first. */
    val OPTIONS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Snaps any value to the nearest offered option; invalid values become [NORMAL]. */
    fun nearest(speed: Float): Float {
        if (speed.isNaN() || speed.isInfinite() || speed <= 0f) return NORMAL
        return OPTIONS.minByOrNull { abs(it - speed) } ?: NORMAL
    }

    fun isNormal(speed: Float): Boolean = abs(speed - NORMAL) < 0.001f

    /** The speed that may actually be applied: live playback is always [NORMAL]. */
    fun effective(requested: Float, isLive: Boolean): Float = if (isLive) NORMAL else nearest(requested)

    /** "0.5×", "1×", "1.25×" (decimal separator configurable for the UI locale). */
    fun label(speed: Float, decimalSeparator: Char = '.'): String {
        val plain = BigDecimal(nearest(speed).toString()).stripTrailingZeros().toPlainString()
        return plain.replace('.', decimalSeparator) + "×"
    }
}
