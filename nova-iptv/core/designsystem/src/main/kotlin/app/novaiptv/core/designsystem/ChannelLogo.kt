package app.novaiptv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

private val LogoPalette = listOf(
    Color(0xFF2B5D8C), Color(0xFF7A3E9D), Color(0xFF1F7A6B), Color(0xFFA04A3A),
    Color(0xFF3F4FA8), Color(0xFF8C6A1F), Color(0xFF2E7D32), Color(0xFF9C2F5E),
)

/** Up to two letters from the channel name, e.g. "Nova Sport HD" -> "NS". */
fun channelInitials(name: String): String {
    val words = name.split(' ', '-', '_', '.', '|').filter { w -> w.isNotBlank() && w.first().isLetterOrDigit() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * Channel logo loaded with Coil. Falls back to the channel initials on a colour derived from
 * the name hash when there is no URL or the image fails to load.
 */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val showImage = !url.isNullOrBlank() && !failed
    val bg = if (showImage) NovaColors.Surface.copy(alpha = 0.6f)
    else LogoPalette[Math.floorMod(name.hashCode(), LogoPalette.size)]
    Box(modifier.clip(shape).background(bg), contentAlignment = Alignment.Center) {
        if (showImage) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true },
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        } else {
            Text(
                text = channelInitials(name),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
