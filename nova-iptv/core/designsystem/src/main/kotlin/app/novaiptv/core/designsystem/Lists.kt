package app.novaiptv.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Text

/**
 * tv-material ListItem with Nova colours: accent fill on focus, subtle fill when selected.
 * Texts inherit LocalContentColor so they stay readable in every state.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NovaListItem(
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    trailing: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        headlineContent = { Text(text = headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (supporting.isNullOrBlank()) null else {
            { Text(text = supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = if (icon == null) null else {
            { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
        },
        trailingContent = if (trailing.isNullOrBlank()) null else {
            { Text(text = trailing, maxLines = 1) }
        },
        shape = ListItemDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(
            containerColor = NovaColors.Background.copy(alpha = 0f),
            contentColor = NovaColors.TextPrimary,
            selectedContainerColor = NovaColors.SurfaceRaised,
            selectedContentColor = NovaColors.Accent,
            focusedContainerColor = NovaColors.Accent,
            focusedContentColor = NovaColors.OnAccent,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
    )
}
