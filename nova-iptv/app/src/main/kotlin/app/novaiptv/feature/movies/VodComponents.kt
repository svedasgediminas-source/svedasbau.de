@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package app.novaiptv.feature.movies

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.EmptyState
import app.novaiptv.core.designsystem.LoadingState
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.designsystem.PosterCard
import app.novaiptv.core.designsystem.SectionHeader
import app.novaiptv.core.model.WatchHistory
import coil3.compose.AsyncImage
import androidx.compose.foundation.lazy.grid.items as gridItems

/** Text shown in the catalogue header for the focused card. */
internal data class FocusedInfo(val title: String, val meta: String?, val plot: String?)

/** Shared Movies/Series catalogue: header, category column, Continue Watching rail, poster grid. */
@Composable
internal fun <T : Any> VodCatalogContent(
    state: CatalogState<T>,
    screenTitle: String,
    emptyTitle: String,
    emptyMessage: String,
    itemKey: (T) -> String,
    itemTitle: (T) -> String,
    itemImage: (T) -> String?,
    itemSubtitle: (T) -> String?,
    itemInfo: (T) -> FocusedInfo,
    onSelectCategory: (String?) -> Unit,
    onOpenItem: (T) -> Unit,
    onOpenContinue: (WatchHistory) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState(modifier.fillMaxSize())
        !state.hasPlaylist -> EmptyState(
            title = stringResource(R.string.vod_no_playlist_title),
            message = stringResource(R.string.vod_no_playlist_message),
            modifier = modifier.fillMaxSize(),
        )
        state.categories.isEmpty() && state.items.isEmpty() && state.continueWatching.isEmpty() -> EmptyState(
            title = emptyTitle,
            message = emptyMessage,
            modifier = modifier.fillMaxSize(),
        )
        else -> {
            val focused = remember { mutableStateOf<FocusedInfo?>(null) }
            Column(modifier.fillMaxSize().padding(top = 12.dp)) {
                FocusedInfoHeader(
                    info = focused,
                    placeholder = screenTitle,
                    modifier = Modifier
                        .padding(horizontal = NovaDimens.ScreenPadding)
                        .fillMaxWidth()
                        .height(104.dp),
                )
                Row(Modifier.fillMaxSize()) {
                    if (state.categories.isNotEmpty()) {
                        CategoryColumn(
                            state = state,
                            onSelect = onSelectCategory,
                            modifier = Modifier.width(250.dp).fillMaxHeight(),
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val showContinue = state.selectedCategoryId == null && state.continueWatching.isNotEmpty()
                        if (state.items.isEmpty() && !showContinue) {
                            EmptyState(title = stringResource(R.string.vod_empty_category), modifier = Modifier.fillMaxSize())
                        } else {
                            PosterGrid(
                                state = state,
                                showContinue = showContinue,
                                focused = focused,
                                itemKey = itemKey,
                                itemTitle = itemTitle,
                                itemImage = itemImage,
                                itemSubtitle = itemSubtitle,
                                itemInfo = itemInfo,
                                onOpenItem = onOpenItem,
                                onOpenContinue = onOpenContinue,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T : Any> PosterGrid(
    state: CatalogState<T>,
    showContinue: Boolean,
    focused: MutableState<FocusedInfo?>,
    itemKey: (T) -> String,
    itemTitle: (T) -> String,
    itemImage: (T) -> String?,
    itemSubtitle: (T) -> String?,
    itemInfo: (T) -> FocusedInfo,
    onOpenItem: (T) -> Unit,
    onOpenContinue: (WatchHistory) -> Unit,
) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(NovaDimens.PosterWidth + 8.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(start = 16.dp, end = NovaDimens.ScreenPadding, top = 16.dp, bottom = 64.dp),
        horizontalArrangement = Arrangement.spacedBy(NovaDimens.CardSpacing),
        verticalArrangement = Arrangement.spacedBy(NovaDimens.CardSpacing),
    ) {
        if (showContinue) {
            item(key = "__continue", span = { GridItemSpan(maxLineSpan) }, contentType = "rail") {
                // Own rail (not ContentRail) so the cards line up with the grid columns below.
                Column(Modifier.padding(bottom = 8.dp)) {
                    SectionHeader(text = stringResource(R.string.vod_continue_watching))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().focusRestorer(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(NovaDimens.CardSpacing),
                    ) {
                        items(state.continueWatching, key = { "cw:${it.contentType}:${it.contentId}" }) { entry ->
                            PosterCard(
                                title = entry.title,
                                imageUrl = entry.imageUrl,
                                onClick = { onOpenContinue(entry) },
                                progress = entry.progress,
                                onFocused = { focused.value = FocusedInfo(entry.title, null, null) },
                            )
                        }
                    }
                }
            }
        }
        gridItems(state.items, key = { itemKey(it) }, contentType = { "poster" }) { item ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                PosterCard(
                    title = itemTitle(item),
                    imageUrl = itemImage(item),
                    onClick = { onOpenItem(item) },
                    subtitle = itemSubtitle(item),
                    onFocused = { focused.value = itemInfo(item) },
                )
            }
        }
    }
}

@Composable
private fun <T> CategoryColumn(
    state: CatalogState<T>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.focusRestorer(),
        contentPadding = PaddingValues(start = NovaDimens.ScreenPadding - 16.dp, end = 8.dp, top = 16.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "__all") {
            CategoryItem(
                name = stringResource(R.string.vod_category_all),
                selected = state.selectedCategoryId == null,
                onClick = { onSelect(null) },
            )
        }
        items(state.categories, key = { "c:${it.id}" }) { category ->
            CategoryItem(
                name = category.name,
                selected = category.id == state.selectedCategoryId,
                onClick = { onSelect(category.id) },
            )
        }
    }
}

@Composable
private fun CategoryItem(name: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = {
            Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FocusedInfoHeader(info: State<FocusedInfo?>, placeholder: String, modifier: Modifier = Modifier) {
    val current = info.value
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        Text(
            text = current?.title ?: placeholder,
            style = MaterialTheme.typography.headlineMedium,
            color = NovaColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!current?.meta.isNullOrBlank()) {
            Text(
                text = current?.meta.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!current?.plot.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = current?.plot.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
        }
    }
}

/** Full-screen detail backdrop: blurred poster on API 31+, dimmed poster + gradients everywhere. */
@Composable
internal fun DetailBackdrop(imageUrl: String?, modifier: Modifier = Modifier) {
    val bg = NovaColors.Background
    Box(modifier.fillMaxSize().background(bg)) {
        if (!imageUrl.isNullOrBlank()) {
            val blur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(36.dp) else Modifier
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(blur).alpha(0.45f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0.88f), bg.copy(alpha = 0.35f))),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, bg)),
            ),
        )
    }
}

/** Rounded poster with a neutral placeholder colour while loading or when there is no image. */
@Composable
internal fun PosterImage(imageUrl: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(NovaDimens.CardCorner)).background(NovaColors.SurfaceRaised)) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Localised duration ("1 h 52 min" / "48 min"), null when unknown. */
@Composable
internal fun durationText(seconds: Int?): String? {
    val parts = VodFormat.durationParts(seconds) ?: return null
    return if (parts.hours > 0) stringResource(R.string.vod_duration_hm, parts.hours, parts.minutes)
    else stringResource(R.string.vod_duration_m, parts.minutes)
}
