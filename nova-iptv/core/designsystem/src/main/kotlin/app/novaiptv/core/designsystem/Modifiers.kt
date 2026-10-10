package app.novaiptv.core.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color

/**
 * Generic focus decoration for custom focusable elements that are not tv-material
 * Card/Surface: accent border + slight scale while focused. Place it BEFORE `focusable()` /
 * `clickable()` in the chain so it observes the element's focus state.
 */
fun Modifier.focusGlow(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .scale(if (focused) NovaDimens.FocusedScale else 1f)
        .border(
            width = NovaDimens.FocusBorder,
            color = if (focused) NovaColors.Accent else Color.Transparent,
            shape = RoundedCornerShape(NovaDimens.CardCorner),
        )
}
