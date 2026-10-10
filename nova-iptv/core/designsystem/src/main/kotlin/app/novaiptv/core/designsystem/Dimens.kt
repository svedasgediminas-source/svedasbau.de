package app.novaiptv.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Layout constants in dp; TV density scaling makes them identical on 1080p and 4K. */
object NovaDimens {
    /** Overscan-safe outer padding. */
    val ScreenPadding: Dp = 48.dp
    /** Vertical gap between rails. */
    val RailSpacing: Dp = 28.dp
    /** Horizontal gap between cards in a rail. */
    val CardSpacing: Dp = 20.dp
    val PosterWidth: Dp = 150.dp
    val LandscapeWidth: Dp = 260.dp
    val ChannelCardWidth: Dp = 220.dp
    val ChannelCardHeight: Dp = 150.dp
    val CardCorner: Dp = 12.dp
    val FocusBorder: Dp = 3.dp
    const val FocusedScale: Float = 1.08f
}
