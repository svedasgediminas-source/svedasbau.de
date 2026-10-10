package app.novaiptv.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import kotlinx.coroutines.delay
import java.util.Date

/** One entry of the top navigation bar. [icon] == null means a text pill. */
data class TopNavItem(val route: Route, val labelRes: Int, val icon: ImageVector? = null)

val TopNavItems: List<TopNavItem> = listOf(
    TopNavItem(Route.Home, R.string.nav_home),
    TopNavItem(Route.LiveTv, R.string.nav_live_tv),
    TopNavItem(Route.Guide, R.string.nav_guide),
    TopNavItem(Route.Movies, R.string.nav_movies),
    TopNavItem(Route.Series, R.string.nav_series),
    TopNavItem(Route.Favorites, R.string.nav_favorites),
    TopNavItem(Route.Search, R.string.nav_search, Icons.Filled.Search),
    TopNavItem(Route.Settings, R.string.nav_settings, Icons.Filled.Settings),
)

/**
 * Top bar: brand mark, six text tabs, then icon-only Search and Settings and a clock.
 * Focus moves along the bar with LEFT/RIGHT; OK opens the tab; DOWN enters the content.
 * The row restores the last focused tab when focus comes back up from the content.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopNavBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = NovaDimens.ScreenPadding, end = NovaDimens.ScreenPadding, top = 24.dp, bottom = 8.dp)
            .focusRestorer(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark()
        Spacer(Modifier.width(20.dp))
        Row(
            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopNavItems.forEachIndexed { index, item ->
                if (item.icon == null) {
                    NavPill(
                        label = stringResource(item.labelRes),
                        icon = null,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TopNavItems.forEachIndexed { index, item ->
            if (item.icon != null) {
                NavPill(
                    label = stringResource(item.labelRes),
                    icon = item.icon,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Clock()
    }
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(NovaColors.Accent, NovaColors.Secondary))),
            contentAlignment = Alignment.Center,
        ) {
            Text("N", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = NovaColors.OnAccent)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavPill(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.height(44.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NovaColors.SurfaceRaised else NovaColors.Background.copy(alpha = 0f),
            contentColor = if (selected) NovaColors.Accent else NovaColors.TextSecondary,
            focusedContainerColor = NovaColors.Accent,
            focusedContentColor = NovaColors.OnAccent,
            pressedContainerColor = NovaColors.Accent,
            pressedContentColor = NovaColors.OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (icon != null && !focused) 12.dp else 14.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(22.dp))
                // Icon-only pills reveal their label while focused.
                if (focused) {
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.titleSmall)
                }
            } else {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    Text(text = format.format(Date(now)), style = MaterialTheme.typography.titleMedium, color = NovaColors.TextSecondary)
}
