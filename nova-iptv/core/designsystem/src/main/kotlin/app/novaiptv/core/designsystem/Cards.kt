package app.novaiptv.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

private val CardShape = RoundedCornerShape(NovaDimens.CardCorner)

/** Thin progress bar (0..1) used on cards for resume position / live programme progress. */
@Composable
fun NovaProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = NovaColors.Accent) {
    val p = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.22f)),
    ) {
        if (p > 0f) {
            Box(Modifier.fillMaxWidth(p).height(4.dp).background(color))
        }
    }
}

/** Shared Card wrapper with Nova focus treatment (scale + bright accent border + soft glow). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NovaCardFrame(
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { onFocusChange(it.isFocused) },
        shape = CardDefaults.shape(shape = CardShape),
        colors = CardDefaults.colors(
            containerColor = NovaColors.SurfaceRaised,
            contentColor = NovaColors.TextPrimary,
            focusedContainerColor = NovaColors.SurfaceBright,
            focusedContentColor = NovaColors.TextPrimary,
        ),
        scale = CardDefaults.scale(focusedScale = NovaDimens.FocusedScale),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NovaDimens.FocusBorder, NovaColors.Accent),
                shape = CardShape,
            ),
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = NovaColors.Accent.copy(alpha = 0.45f), elevation = 18.dp),
        ),
    ) {
        content()
    }
}

/** Artwork with a gradient + title placeholder that stays visible while loading or on error. */
@Composable
private fun Artwork(imageUrl: String?, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NovaColors.SurfaceBright, NovaColors.Surface))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = NovaColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardCaption(title: String, subtitle: String?, focused: Boolean) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = if (focused) NovaColors.TextPrimary else NovaColors.TextSecondary,
        maxLines = 1,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (focused) Modifier.basicMarquee() else Modifier,
    )
    if (!subtitle.isNullOrBlank()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 2:3 poster (movies, series). */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    onFocused: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(NovaDimens.PosterWidth)) {
        NovaCardFrame(
            onClick = onClick,
            onFocusChange = { f -> focused = f; if (f) onFocused?.invoke() },
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(imageUrl, title)
                if (progress != null) {
                    NovaProgressBar(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp))
                }
            }
        }
        CardCaption(title, subtitle, focused)
    }
}

/** 16:9 landscape card (continue watching, episodes, backdrops). */
@Composable
fun LandscapeCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    onFocused: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(NovaDimens.LandscapeWidth)) {
        NovaCardFrame(
            onClick = onClick,
            onFocusChange = { f -> focused = f; if (f) onFocused?.invoke() },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Artwork(imageUrl, title)
                if (progress != null) {
                    NovaProgressBar(progress, Modifier.align(Alignment.BottomCenter).padding(10.dp))
                }
            }
        }
        CardCaption(title, subtitle, focused)
    }
}

/** Live channel tile: big logo, number, name, current programme and its progress. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelCard(
    name: String,
    logoUrl: String?,
    number: Int?,
    nowTitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    progress: Float? = null,
    onFocused: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    NovaCardFrame(
        onClick = onClick,
        onFocusChange = { f -> focused = f; if (f) onFocused?.invoke() },
        modifier = modifier.width(NovaDimens.ChannelCardWidth).height(NovaDimens.ChannelCardHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ChannelLogo(url = logoUrl, name = name, modifier = Modifier.size(64.dp).align(Alignment.Center))
                if (number != null) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = NovaColors.TextSecondary,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = NovaColors.Favorite,
                        modifier = Modifier.size(18.dp).align(Alignment.TopEnd),
                    )
                }
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = NovaColors.TextPrimary,
                maxLines = 1,
                overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (focused) Modifier.basicMarquee() else Modifier,
            )
            Row(horizontalArrangement = Arrangement.Start) {
                Text(
                    text = nowTitle ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                NovaProgressBar(progress)
            }
        }
    }
}
