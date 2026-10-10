@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.novaiptv.feature.livetv

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.ChannelLogo
import app.novaiptv.core.designsystem.EmptyState
import app.novaiptv.core.designsystem.ErrorState
import app.novaiptv.core.designsystem.LoadingState
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.NowNext
import app.novaiptv.di.LocalAppContainer
import app.novaiptv.feature.guide.ClockFormat
import app.novaiptv.ui.errorMessage
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val CategoryPaneWidth = 220.dp
private val DetailsPaneWidth = 330.dp
private val PaneGap = 16.dp
private val ChannelRowHeight = 76.dp
private val CategoryRowHeight = 52.dp
private val RowShape = RoundedCornerShape(10.dp)

/**
 * Live TV: categories | channel list | details of the focused channel.
 *
 * Remote: OK = play, long-press OK or MENU = toggle favourite, digits = jump to channel number,
 * LEFT from the list = categories, RIGHT from categories = back to the list (same channel).
 *
 * Future work: a muted live mini-preview in the details pane (needs a shared player instance from
 * :core:player; deliberately not creating a second ExoPlayer here).
 */
@Composable
fun LiveTvScreen(onPlayChannel: (channelId: String, group: String?) -> Unit) {
    val c = LocalAppContainer.current
    val vm: LiveTvViewModel = viewModel {
        LiveTvViewModel(c.playlists, c.channels, c.epg, c.favorites, c.history, c.preferences, c.clock)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val nowMillis by vm.nowMillis.collectAsStateWithLifecycle()
    val numberEntry by vm.numberEntry.collectAsStateWithLifecycle()
    val focusedId by vm.focusedChannelId.collectAsStateWithLifecycle()
    val initialId by vm.initialChannelId.collectAsStateWithLifecycle()
    val initialResolved by vm.initialResolved.collectAsStateWithLifecycle()

    val list = state.list
    when {
        list is ChannelListState.NoPlaylist -> {
            Box(Modifier.fillMaxSize().background(NovaColors.Background), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.livetv_empty_title),
                    message = stringResource(R.string.livetv_empty_message),
                )
            }
            return
        }
        list is ChannelListState.Failed -> {
            Box(Modifier.fillMaxSize().background(NovaColors.Background), contentAlignment = Alignment.Center) {
                ErrorState(message = errorMessage(list.error), onRetry = vm::retry)
            }
            return
        }
        list is ChannelListState.Loading && state.categories.isEmpty() -> {
            Box(Modifier.fillMaxSize().background(NovaColors.Background), contentAlignment = Alignment.Center) {
                LoadingState()
            }
            return
        }
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0] // minSdk 26: observable via LocalConfiguration
    val is24h = DateFormat.is24HourFormat(context)
    val clock = remember(locale, is24h) { ClockFormat(locale, is24h, ZoneId.systemDefault()) }

    val scope = rememberCoroutineScope()
    val channels = state.channels
    val listCategory = (list as? ChannelListState.Loaded)?.category
    // A fresh scroll state per category so the list starts at the top (keys would otherwise
    // keep the scroll position on a channel that also exists in the new category).
    val listState = remember(listCategory) { LazyListState() }
    val categoryListState = rememberLazyListState()

    var channelFocusTarget by remember { mutableStateOf<String?>(null) }
    var categoryFocusTarget by remember { mutableStateOf<CategoryKey?>(null) }
    var pendingJumpId by remember { mutableStateOf<String?>(null) }
    var didInitialFocus by remember { mutableStateOf(false) }
    var searchEditing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<LiveTvEvent?>(null) }

    fun focusChannel(id: String?) {
        if (channels.isEmpty()) return
        val targetId = id?.takeIf { wanted -> channels.any { it.id == wanted } } ?: channels.first().id
        val index = channels.indexOfFirst { it.id == targetId }
        scope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == index }) listState.scrollToItem((index - 2).coerceAtLeast(0))
            channelFocusTarget = targetId
        }
    }

    var pendingListFocus by remember { mutableStateOf(false) }

    /** Focus the list; if it still shows the previous category, wait for the new data first. */
    fun requestListFocus(preferredId: String?) {
        if (listCategory == state.selectedCategory && list is ChannelListState.Loaded) {
            focusChannel(preferredId)
        } else {
            pendingListFocus = true
        }
    }

    fun focusSelectedCategory() {
        val index = state.categories.indexOfFirst { it.key == state.selectedCategory }.coerceAtLeast(0)
        scope.launch {
            if (categoryListState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                categoryListState.scrollToItem(index)
            }
            categoryFocusTarget = state.categories.getOrNull(index)?.key
        }
    }

    // Initial focus: last focused (when coming back from the player) or last played channel.
    LaunchedEffect(initialResolved, channels, didInitialFocus) {
        if (!didInitialFocus && initialResolved && list is ChannelListState.Loaded && channels.isNotEmpty()) {
            didInitialFocus = true
            focusChannel(focusedId ?: initialId)
        }
    }

    LaunchedEffect(pendingListFocus, listCategory, channels) {
        if (pendingListFocus && listCategory == state.selectedCategory && channels.isNotEmpty()) {
            pendingListFocus = false
            focusChannel(null)
        }
    }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { event ->
            when (event) {
                is LiveTvEvent.JumpTo -> pendingJumpId = event.channelId
                else -> notice = event
            }
        }
    }

    LaunchedEffect(pendingJumpId, channels) {
        val id = pendingJumpId ?: return@LaunchedEffect
        if (channels.any { it.id == id }) {
            pendingJumpId = null
            focusChannel(id)
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .distinctUntilChanged()
            .collect { vm.onVisibleChannels(it) }
    }

    val focusedChannel = channels.firstOrNull { it.id == focusedId } ?: channels.firstOrNull()

    Box(
        Modifier
            .fillMaxSize()
            .background(NovaColors.Background)
            .onPreviewKeyEvent { event ->
                if (searchEditing) return@onPreviewKeyEvent false
                val digit = event.digitOrNull() ?: return@onPreviewKeyEvent false
                if (event.isKeyDown()) vm.appendNumberDigit(digit)
                true
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(PaneGap),
        ) {
            // ---- Categories ----
            Column(Modifier.width(CategoryPaneWidth).fillMaxHeight()) {
                PaneTitle(stringResource(R.string.livetv_categories_title))
                LazyColumn(
                    state = categoryListState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    items(state.categories, key = { categoryKeyString(it.key) }) { category ->
                        CategoryRow(
                            label = categoryLabel(category.key),
                            count = category.count,
                            selected = category.key == state.selectedCategory,
                            requestFocus = categoryFocusTarget == category.key,
                            onFocusRequested = { categoryFocusTarget = null },
                            onFocused = { vm.selectCategory(category.key) },
                            onClick = {
                                vm.selectCategory(category.key)
                                requestListFocus(null)
                            },
                            onRight = { requestListFocus(focusedId) },
                        )
                    }
                }
            }

            // ---- Channel list ----
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SearchField(
                    query = state.query,
                    editing = searchEditing,
                    onStartEditing = { searchEditing = true },
                    onQueryChange = vm::setQuery,
                    onFinish = {
                        searchEditing = false
                        focusChannel(null)
                    },
                    onFocusLost = { searchEditing = false },
                    onLeft = { focusSelectedCategory() },
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        list is ChannelListState.Loading -> LoadingState(Modifier.fillMaxSize())
                        channels.isEmpty() -> EmptyState(
                            title = when {
                                state.query.isNotBlank() -> stringResource(R.string.livetv_no_results, state.query)
                                state.selectedCategory == CategoryKey.Favorites -> stringResource(R.string.livetv_empty_favorites_title)
                                else -> stringResource(R.string.livetv_empty_category)
                            },
                            message = if (state.selectedCategory == CategoryKey.Favorites && state.query.isBlank()) {
                                stringResource(R.string.livetv_empty_favorites_message)
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                        ) {
                            itemsIndexed(channels, key = { _, ch -> ch.id }) { index, channel ->
                                val nn = channel.epgId?.let { nowNext[it] }
                                ChannelRow(
                                    channel = channel,
                                    nowNext = nn,
                                    nowMillis = nowMillis,
                                    showNumber = state.showNumbers,
                                    isFavorite = channel.id in state.favoriteIds,
                                    requestFocus = channelFocusTarget == channel.id,
                                    onFocusRequested = { channelFocusTarget = null },
                                    onFocused = { vm.onChannelFocused(channel.id) },
                                    onClick = {
                                        if (!vm.commitNumberNow()) {
                                            onPlayChannel(channel.id, (state.selectedCategory as? CategoryKey.Group)?.name)
                                        }
                                    },
                                    onToggleFavorite = {
                                        if (state.selectedCategory == CategoryKey.Favorites && channel.id in state.favoriteIds) {
                                            // The row will disappear: move focus to a neighbour first.
                                            neighbourAfterRemoval(channels, index)?.let { channelFocusTarget = it }
                                        }
                                        vm.toggleFavorite(channel)
                                    },
                                    onLeft = { focusSelectedCategory() },
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.livetv_hint_controls),
                    style = MaterialTheme.typography.labelMedium,
                    color = NovaColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }

            // ---- Details ----
            DetailsPane(
                channel = focusedChannel,
                nowNext = focusedChannel?.epgId?.let { nowNext[it] },
                nowMillis = nowMillis,
                showNumber = state.showNumbers,
                isFavorite = focusedChannel?.id?.let { it in state.favoriteIds } == true,
                clock = clock,
                modifier = Modifier.width(DetailsPaneWidth).fillMaxHeight(),
            )
        }

        if (numberEntry.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NovaColors.Scrim)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.livetv_number_entry, numberEntry),
                    style = MaterialTheme.typography.displaySmall,
                    color = NovaColors.Accent,
                )
            }
        }

        notice?.let { event ->
            val text = when (event) {
                is LiveTvEvent.NumberNotFound -> stringResource(R.string.livetv_number_not_found, event.number)
                is LiveTvEvent.FavoriteChanged -> if (event.added) {
                    stringResource(R.string.livetv_favorite_added, event.channelName)
                } else {
                    stringResource(R.string.livetv_favorite_removed, event.channelName)
                }
                is LiveTvEvent.JumpTo -> null
            }
            if (text != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NovaColors.SurfaceBright)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(text = text, style = MaterialTheme.typography.titleMedium, color = NovaColors.TextPrimary)
                }
            }
        }
    }
}

private fun categoryKeyString(key: CategoryKey): String = when (key) {
    CategoryKey.All -> "\u0000all"
    CategoryKey.Favorites -> "\u0000fav"
    is CategoryKey.Group -> "g:" + key.name
}

@Composable
private fun categoryLabel(key: CategoryKey): String = when (key) {
    CategoryKey.All -> stringResource(R.string.livetv_category_all)
    CategoryKey.Favorites -> stringResource(R.string.livetv_category_favorites)
    is CategoryKey.Group -> key.name.ifBlank { stringResource(R.string.livetv_category_ungrouped) }
}

@Composable
private fun PaneTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = NovaColors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).height(32.dp),
    )
}

/** Requests focus on the composed row once [requestFocus] becomes true. */
@Composable
private fun FocusOnRequest(requestFocus: Boolean, requester: FocusRequester, onDone: () -> Unit) {
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            onDone()
        }
    }
}

@Composable
private fun rowColors(selected: Boolean = false) = ClickableSurfaceDefaults.colors(
    containerColor = if (selected) NovaColors.SurfaceRaised else Color.Transparent,
    contentColor = NovaColors.TextPrimary,
    focusedContainerColor = NovaColors.SurfaceBright,
    focusedContentColor = NovaColors.TextPrimary,
    pressedContainerColor = NovaColors.SurfaceBright,
    pressedContentColor = NovaColors.TextPrimary,
)

@Composable
private fun rowBorder() = ClickableSurfaceDefaults.border(
    focusedBorder = Border(border = BorderStroke(2.dp, NovaColors.Accent), shape = RowShape),
)

@Composable
private fun CategoryRow(
    label: String,
    count: Int,
    selected: Boolean,
    requestFocus: Boolean,
    onFocusRequested: () -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onRight: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    FocusOnRequest(requestFocus, requester, onFocusRequested)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(CategoryRowHeight)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused || it.hasFocus) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionRight) {
                    if (event.isKeyDown()) onRight()
                    true
                } else {
                    false
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = rowColors(selected),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = rowBorder(),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(NovaColors.Accent),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = NovaColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    nowNext: NowNext?,
    nowMillis: Long,
    showNumber: Boolean,
    isFavorite: Boolean,
    requestFocus: Boolean,
    onFocusRequested: () -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLeft: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    FocusOnRequest(requestFocus, requester, onFocusRequested)
    val now = nowNext?.now
    val progress = programProgress(now, nowMillis)
    Surface(
        onClick = onClick,
        onLongClick = onToggleFavorite,
        modifier = Modifier
            .fillMaxWidth()
            .height(ChannelRowHeight)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused || it.hasFocus) onFocused() }
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.DirectionLeft -> {
                        if (event.isKeyDown()) onLeft()
                        true
                    }
                    // Nothing focusable on the right: keep focus in the list.
                    event.key == Key.DirectionRight -> true
                    event.isMenuKey() -> {
                        if (event.isKeyDown()) onToggleFavorite()
                        true
                    }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = rowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = rowBorder(),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNumber) {
                Text(
                    text = formatChannelNumber(channel.number),
                    style = MaterialTheme.typography.titleSmall,
                    color = NovaColors.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.width(48.dp),
                )
            }
            ChannelLogo(
                url = channel.logoUrl,
                name = channel.name,
                modifier = Modifier.size(width = 64.dp, height = 44.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.catchup is CatchupCapability.Supported) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = stringResource(R.string.livetv_catchup_badge),
                            tint = NovaColors.Secondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = stringResource(R.string.common_favorite_remove),
                            tint = NovaColors.Favorite,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = now?.title ?: stringResource(R.string.livetv_no_epg),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (now != null) NovaColors.TextSecondary else NovaColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress != null) {
                    Spacer(Modifier.height(5.dp))
                    ProgressLine(progress)
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(NovaColors.SurfaceBright),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(NovaColors.Accent),
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    editing: Boolean,
    onStartEditing: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFinish: () -> Unit,
    onFocusLost: () -> Unit,
    onLeft: () -> Unit,
) {
    if (!editing) {
        // A plain button while browsing, so D-pad navigation never pops up the keyboard.
        Surface(
            onClick = onStartEditing,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionLeft) {
                        if (event.isKeyDown()) onLeft()
                        true
                    } else {
                        false
                    }
                },
            shape = ClickableSurfaceDefaults.shape(shape = RowShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NovaColors.Surface,
                contentColor = NovaColors.TextSecondary,
                focusedContainerColor = NovaColors.SurfaceBright,
                focusedContentColor = NovaColors.TextPrimary,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = rowBorder(),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = query.ifBlank { stringResource(R.string.livetv_search_hint) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }

    val requester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RowShape)
            .background(NovaColors.SurfaceBright)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = NovaColors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NovaColors.TextPrimary),
            cursorBrush = SolidColor(NovaColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onFinish() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        onFocusLost()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionDown && event.isKeyDown()) {
                        onFinish()
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

@Composable
private fun DetailsPane(
    channel: Channel?,
    nowNext: NowNext?,
    nowMillis: Long,
    showNumber: Boolean,
    isFavorite: Boolean,
    clock: ClockFormat,
    modifier: Modifier = Modifier,
) {
    if (channel == null) {
        Spacer(modifier)
        return
    }
    Column(modifier.padding(top = 4.dp)) {
        // Preview placeholder (live mini-preview is future work).
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(listOf(NovaColors.SurfaceRaised, NovaColors.Background)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(
                url = channel.logoUrl,
                name = channel.name,
                modifier = Modifier.size(width = 160.dp, height = 100.dp),
            )
            Text(
                text = stringResource(R.string.livetv_preview_hint),
                style = MaterialTheme.typography.labelMedium,
                color = NovaColors.TextSecondary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showNumber && channel.number > 0) {
                Text(
                    text = formatChannelNumber(channel.number),
                    style = MaterialTheme.typography.titleLarge,
                    color = NovaColors.Accent,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = channel.name,
                style = MaterialTheme.typography.headlineSmall,
                color = NovaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isFavorite) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Star, contentDescription = null, tint = NovaColors.Favorite, modifier = Modifier.size(22.dp))
            }
        }
        val catchup = channel.catchup
        if (catchup is CatchupCapability.Supported && catchup.days > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = pluralStringResource(R.plurals.livetv_catchup_days, catchup.days, catchup.days),
                style = MaterialTheme.typography.labelMedium,
                color = NovaColors.Secondary,
            )
        }
        Spacer(Modifier.height(14.dp))
        val now = nowNext?.now
        val next = nowNext?.next
        if (now == null && next == null) {
            Text(
                text = stringResource(R.string.livetv_no_epg),
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextMuted,
            )
        }
        if (now != null) {
            ProgrammeLabel(stringResource(R.string.livetv_now), clock.range(now.startMillis, now.endMillis), NovaColors.Live)
            Text(
                text = now.title,
                style = MaterialTheme.typography.titleLarge,
                color = NovaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            programProgress(now, nowMillis)?.let {
                Spacer(Modifier.height(6.dp))
                ProgressLine(it)
            }
            now.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        if (next != null) {
            ProgrammeLabel(stringResource(R.string.livetv_next), clock.range(next.startMillis, next.endMillis), NovaColors.TextMuted)
            Text(
                text = next.title,
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgrammeLabel(label: String, time: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(10.dp))
        Text(text = time, style = MaterialTheme.typography.labelLarge, color = NovaColors.TextSecondary)
    }
}
