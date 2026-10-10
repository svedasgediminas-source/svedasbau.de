package app.novaiptv.core.designsystem

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

/**
 * Pill button: neutral when idle, accent fill when focused (very visible from the sofa).
 * [primary] buttons are accent-tinted even when not focused.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NovaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) NovaColors.AccentDim else NovaColors.SurfaceBright,
            contentColor = NovaColors.TextPrimary,
            focusedContainerColor = NovaColors.Accent,
            focusedContentColor = NovaColors.OnAccent,
            pressedContainerColor = NovaColors.Accent,
            pressedContentColor = NovaColors.OnAccent,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text = text)
    }
}
