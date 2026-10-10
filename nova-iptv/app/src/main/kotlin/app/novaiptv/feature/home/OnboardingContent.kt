package app.novaiptv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import app.novaiptv.R
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.feature.playlists.PlaylistType

/** First-run screen shown on Home while no playlist exists. */
@Composable
fun OnboardingContent(onAddPlaylist: (type: String) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(NovaColors.AccentDim.copy(alpha = 0.25f), Color.Transparent),
                    radius = 1400f,
                ),
            )
            .padding(NovaDimens.ScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.home_onboarding_title),
                style = MaterialTheme.typography.displayMedium,
                color = NovaColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.home_onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = NovaColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 720.dp),
            )
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                OnboardingOption(
                    icon = Icons.Filled.Link,
                    title = stringResource(R.string.playlist_type_m3u_url),
                    description = stringResource(R.string.playlist_type_m3u_url_desc),
                    onClick = { onAddPlaylist(PlaylistType.M3U_URL) },
                )
                OnboardingOption(
                    icon = Icons.Filled.FolderOpen,
                    title = stringResource(R.string.playlist_type_m3u_file),
                    description = stringResource(R.string.playlist_type_m3u_file_desc),
                    onClick = { onAddPlaylist(PlaylistType.M3U_FILE) },
                )
                OnboardingOption(
                    icon = Icons.Filled.Person,
                    title = stringResource(R.string.playlist_type_xtream),
                    description = stringResource(R.string.playlist_type_xtream_desc),
                    onClick = { onAddPlaylist(PlaylistType.XTREAM) },
                )
            }
            Spacer(Modifier.height(40.dp))
            Text(
                text = stringResource(R.string.common_legal_note),
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OnboardingOption(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.width(250.dp).height(190.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NovaColors.SurfaceRaised,
            contentColor = NovaColors.TextPrimary,
            focusedContainerColor = NovaColors.SurfaceBright,
            focusedContentColor = NovaColors.TextPrimary,
            pressedContainerColor = NovaColors.SurfaceBright,
            pressedContentColor = NovaColors.TextPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(NovaDimens.FocusBorder, NovaColors.Accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = NovaDimens.FocusedScale),
    ) {
        Column(Modifier.padding(22.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(NovaColors.Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = NovaColors.Accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = NovaColors.TextSecondary, maxLines = 3)
        }
    }
}
