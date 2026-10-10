@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.novaiptv.feature.guide

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
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
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.di.LocalAppContainer
import app.novaiptv.feature.livetv.formatChannelNumber
import app.novaiptv.feature.livetv.isKeyDown
import app.novaiptv.feature.livetv.isSelectKey
import app.novaiptv.ui.errorMessage
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

/** Horizontal scale of the grid: 1 minute = 5 dp, so 30 minutes = 150 dp. */
private const val DP_PER_MINUTE = 5f
private val ChannelColumnWidth = 220.dp
private val RowHeight = 68.dp
private val RulerHeight = 34.dp
private val InfoPanelHeight = 150.dp
private val CellShape = RoundedCornerShape(6.dp)
private const val PAGE_ROWS = 8

/**
 * TV guide (EPG grid). Channels vertically, time horizontally.
 *
 * Focus design: the whole grid is ONE focusable element with a virtual cursor ([GuideCursor]):
 * a channel row plus a time anchor. LEFT/RIGHT step between programmes (the visible time window
 * scrolls in 30-minute steps), UP/DOWN keep the time anchor. This avoids per-cell focus nodes that
 * would be destroyed whenever the time window moves, and keeps navigation deterministic for
 * thousands of channels. Each row only composes the cells overlapping the visible window.
 */
@Composable
fun GuideScreen(
    onPlayChannel: (String) -> Unit,
    onPlayCatchup: (channelId: String, startMillis: Long, endMillis: Long) -> Unit,
) {
    val c = LocalAppContainer.current
    val vm: GuideViewModel = viewModel {
        GuideViewModel(c.playlists, c.channels, c.epg, c.history, c.preferences, c.clock)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    when (val chs = state.channels) {
        GuideChannelsState.Loading -> {
            Box(Modifier.fillMaxSize().background(NovaColors.Background), contentAlignment = Alignment.Center) {
                LoadingState()
            }
            return
        }
        GuideChannelsState.NoPlaylist -> {
            Box(Modifier.fillMaxSize().background(NovaColors.Background), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.guide_empty_title),
                    message = stringResource(R.string.guide_empty_message),
                )
            }
            return
        }
        is GuideChannelsState.Failed -> {
            Box(Modifier.fillMaxSize().background(NovaColors.Background), contentAlignment = Alignment.Center) {
                ErrorState(message = errorMessage(chs.error), onRetry = vm::retry)
            }
            return
        }
        is GuideChannelsState.Loaded -> if (chs.channels.isEmpty()) {
            Box(Modifier.fillMaxSize().background(NovaColors.Background), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.guide_no_channels_title),
                    message = stringResource(R.string.guide_empty_message),
                )
            }
            return
        }
    }

    val context = LocalContext.current
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    val clock = remember(locale, is24h) { ClockFormat(locale, is24h, ZoneId.systemDefault()) }

    val channels = state.channelList
    val cursor = state.cursor
    val gridFocus = remember { FocusRequester() }
    var gridFocused by remember { mutableStateOf(false) }
    val rowsState = rememberLazyListState()

    // Enter the screen with the grid focused.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { gridFocus.requestFocus() }
    }

    // Keep the cursor row on screen.
    LaunchedEffect(cursor?.row) {
        val row = cursor?.row ?: return@LaunchedEffect
        val info = rowsState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) {
            rowsState.scrollToItem(row)
            return@LaunchedEffect
        }
        val firstFull = visible.firstOrNull { it.offset >= info.viewportStartOffset }?.index ?: visible.first().index
        val lastFull = visible.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: visible.last().index
        when {
            row < firstFull -> rowsState.animateScrollToItem(row)
            row > lastFull -> rowsState.animateScrollToItem((row - (lastFull - firstFull)).coerceAtLeast(0))
        }
    }

    // Report the visible channel range for lazy EPG loading.
    LaunchedEffect(rowsState) {
        snapshotFlow {
            val v = rowsState.layoutInfo.visibleItemsInfo
            if (v.isEmpty()) IntRange.EMPTY else v.first().index..v.last().index
        }
            .distinctUntilChanged()
            .collect { if (!it.isEmpty()) vm.onVisibleRows(it.first, it.last) }
    }

    val focusedChannel = cursor?.let { channels.getOrNull(it.row) }
    val focusedCell: GuideCell? = if (cursor != null && focusedChannel != null) {
        val programs = state.programsFor(focusedChannel)
        val cells = buildRowCells(programs.orEmpty(), state.day)
        cells.getOrNull(cellIndexAt(cells, cursor.focusMillis))
    } else {
        null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(NovaColors.Background)
            .padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 12.dp),
    ) {
        GuideHeader(
            days = state.days,
            selected = state.day,
            nowMillis = state.nowMillis,
            locale = locale,
            onSelectDay = vm::selectDay,
            onNow = {
                vm.jumpToNow()
                runCatching { gridFocus.requestFocus() }
            },
        )
        Spacer(Modifier.height(12.dp))
        InfoPanel(
            channel = focusedChannel,
            cell = focusedCell,
            nowMillis = state.nowMillis,
            clock = clock,
            message = state.message,
        )
        Spacer(Modifier.height(12.dp))

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val gridWidth: Dp = maxWidth - ChannelColumnWidth
            val span = (gridWidth.value / DP_PER_MINUTE).toLong().coerceAtLeast(30) * MINUTE_MILLIS
            LaunchedEffect(span) { vm.setViewSpan(span) }
            val viewStart = cursor?.viewStart ?: state.day.startMillis
            val viewEnd = viewStart + span

            Column(
                Modifier
                    .fillMaxSize()
                    .focusRequester(gridFocus)
                    .onFocusChanged { gridFocused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.isSelectKey()) {
                            if (event.isKeyDown() && event.nativeKeyEvent.repeatCount == 0) {
                                when (val req = vm.select()) {
                                    is GuidePlayRequest.Live -> onPlayChannel(req.channelId)
                                    is GuidePlayRequest.Catchup -> onPlayCatchup(req.channelId, req.startMillis, req.endMillis)
                                    null -> Unit
                                }
                            }
                            return@onKeyEvent true
                        }
                        if (!event.isKeyDown()) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                vm.moveFocusHorizontally(-1)
                                true
                            }
                            Key.DirectionRight -> {
                                vm.moveFocusHorizontally(+1)
                                true
                            }
                            Key.DirectionUp -> vm.moveFocusVertically(-1)
                            Key.DirectionDown -> {
                                vm.moveFocusVertically(+1)
                                true
                            }
                            Key.PageUp, Key.ChannelUp -> {
                                vm.moveFocusVertically(-PAGE_ROWS)
                                true
                            }
                            Key.PageDown, Key.ChannelDown -> {
                                vm.moveFocusVertically(PAGE_ROWS)
                                true
                            }
                            else -> false
                        }
                    }
                    .focusable(),
            ) {
                TimeRuler(day = state.day, viewStart = viewStart, viewEnd = viewEnd, nowMillis = state.nowMillis, clock = clock)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = rowsState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = false,
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        itemsIndexed(channels, key = { _, ch -> ch.id }) { index, channel ->
                            GuideRow(
                                channel = channel,
                                programs = state.programsFor(channel),
                                day = state.day,
                                viewStart = viewStart,
                                viewEnd = viewEnd,
                                focusMillis = if (cursor != null && cursor.row == index) cursor.focusMillis else null,
                                gridFocused = gridFocused,
                                nowMillis = state.nowMillis,
                                showNumber = state.showNumbers,
                                clock = clock,
                            )
                        }
                    }
                    val now = state.nowMillis
                    if (now in viewStart until viewEnd) {
                        val x = (now - viewStart).toFloat() / MINUTE_MILLIS * DP_PER_MINUTE
                        Box(
                            Modifier
                                .offset(x = ChannelColumnWidth + x.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(NovaColors.Live),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideHeader(
    days: List<GuideDay>,
    selected: GuideDay,
    nowMillis: Long,
    locale: Locale,
    onSelectDay: (java.time.LocalDate) -> Unit,
    onNow: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val dayListState = rememberLazyListState()
    LaunchedEffect(days.size) {
        val index = days.indexOfFirst { it.date == selected.date }
        if (index > 0) dayListState.scrollToItem((index - 1).coerceAtLeast(0))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.guide_title),
            style = MaterialTheme.typography.headlineSmall,
            color = NovaColors.TextPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.width(24.dp))
        HeaderChip(text = stringResource(R.string.guide_now_button), selected = false, accent = true, onClick = onNow)
        Spacer(Modifier.width(16.dp))
        LazyRow(
            state = dayListState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            items(days, key = { it.date.toString() }) { day ->
                val label = when (relativeDayOffset(day.date, nowMillis, zone)) {
                    0 -> stringResource(R.string.guide_today)
                    -1 -> stringResource(R.string.guide_yesterday)
                    1 -> stringResource(R.string.guide_tomorrow)
                    else -> dayLabel(day.date, locale)
                }
                HeaderChip(text = label, selected = day.date == selected.date, accent = false, onClick = { onSelectDay(day.date) })
            }
        }
    }
}

@Composable
private fun HeaderChip(text: String, selected: Boolean, accent: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                selected -> NovaColors.AccentDim
                accent -> NovaColors.SurfaceRaised
                else -> NovaColors.Surface
            },
            contentColor = NovaColors.TextPrimary,
            focusedContainerColor = NovaColors.Accent,
            focusedContentColor = NovaColors.OnAccent,
            pressedContainerColor = NovaColors.Accent,
            pressedContentColor = NovaColors.OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, NovaColors.TextPrimary), shape = shape),
        ),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun InfoPanel(
    channel: Channel?,
    cell: GuideCell?,
    nowMillis: Long,
    clock: ClockFormat,
    message: GuideMessage?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(InfoPanelHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(NovaColors.Surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        val program = (cell as? GuideCell.Program)?.program
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = channel?.name.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = NovaColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (program != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = clock.range(program.startMillis, program.endMillis) + "  ·  " +
                        stringResource(R.string.guide_duration_minutes, (program.durationMillis / MINUTE_MILLIS).toInt()),
                    style = MaterialTheme.typography.labelLarge,
                    color = NovaColors.TextSecondary,
                    maxLines = 1,
                )
                StatusBadge(program, channel, nowMillis)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = program?.title ?: stringResource(R.string.guide_no_programme),
            style = MaterialTheme.typography.titleLarge,
            color = if (program != null) NovaColors.TextPrimary else NovaColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (message) {
                    GuideMessage.ArchiveUnavailable -> stringResource(R.string.guide_archive_unavailable)
                    GuideMessage.ArchiveExpired -> stringResource(R.string.guide_archive_expired)
                    is GuideMessage.Upcoming -> stringResource(R.string.guide_upcoming, clock.time(message.startMillis))
                    GuideMessage.NoInformation -> stringResource(R.string.guide_no_programme)
                },
                style = MaterialTheme.typography.titleSmall,
                color = NovaColors.Favorite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (program != null) {
            val meta = listOfNotNull(program.category, program.episodeNumber).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = meta, style = MaterialTheme.typography.labelMedium, color = NovaColors.Secondary, maxLines = 1)
            }
            program.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(program: EpgProgram, channel: Channel?, nowMillis: Long) {
    val (text, color) = when {
        program.isLive(nowMillis) -> stringResource(R.string.guide_live_badge) to NovaColors.Live
        channel != null && isInArchive(channel.catchup, program.startMillis, program.endMillis, nowMillis) ->
            stringResource(R.string.guide_archive_badge) to NovaColors.Secondary
        else -> return
    }
    Spacer(Modifier.width(12.dp))
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = NovaColors.Background, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TimeRuler(day: GuideDay, viewStart: Long, viewEnd: Long, nowMillis: Long, clock: ClockFormat) {
    Row(Modifier.fillMaxWidth().height(RulerHeight)) {
        Spacer(Modifier.width(ChannelColumnWidth))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            for (tick in slotTicks(day, viewStart, viewEnd)) {
                val x = (tick - viewStart).toFloat() / MINUTE_MILLIS * DP_PER_MINUTE
                Box(Modifier.offset(x = x.dp).width(1.dp).fillMaxHeight().background(NovaColors.SurfaceBright))
                Text(
                    text = clock.time(tick),
                    style = MaterialTheme.typography.labelLarge,
                    color = NovaColors.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.offset(x = x.dp + 6.dp).align(Alignment.CenterStart),
                )
            }
            if (nowMillis in viewStart until viewEnd) {
                val x = (nowMillis - viewStart).toFloat() / MINUTE_MILLIS * DP_PER_MINUTE
                Box(
                    Modifier
                        .offset(x = x.dp - 4.dp)
                        .align(Alignment.BottomStart)
                        .size(width = 10.dp, height = 6.dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(NovaColors.Live),
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programs: List<EpgProgram>?,
    day: GuideDay,
    viewStart: Long,
    viewEnd: Long,
    focusMillis: Long?,
    gridFocused: Boolean,
    nowMillis: Long,
    showNumber: Boolean,
    clock: ClockFormat,
) {
    val cells = remember(programs, day) { buildRowCells(programs.orEmpty(), day) }
    val hasPrograms = !programs.isNullOrEmpty()
    val isSelectedRow = focusMillis != null
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 2.dp)) {
        // Sticky channel column.
        Row(
            Modifier
                .width(ChannelColumnWidth)
                .fillMaxHeight()
                .padding(end = 4.dp)
                .clip(CellShape)
                .background(if (isSelectedRow) NovaColors.SurfaceBright else NovaColors.Surface)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNumber && channel.number > 0) {
                Text(
                    text = formatChannelNumber(channel.number),
                    style = MaterialTheme.typography.labelLarge,
                    color = NovaColors.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.width(40.dp),
                )
            }
            ChannelLogo(url = channel.logoUrl, name = channel.name, modifier = Modifier.size(width = 52.dp, height = 36.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleSmall,
                color = NovaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (!hasPrograms) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1.dp)
                        .clip(CellShape)
                        .background(NovaColors.Surface.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(if (programs == null) R.string.guide_loading else R.string.guide_no_programme),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NovaColors.TextMuted,
                        maxLines = 1,
                    )
                }
            } else {
                for (cell in visibleCells(cells, viewStart, viewEnd)) {
                    if (cell !is GuideCell.Program) continue
                    val place = placeCell(cell.start, cell.end, viewStart, viewEnd, DP_PER_MINUTE) ?: continue
                    val selected = focusMillis != null && focusMillis >= cell.start && focusMillis < cell.end
                    ProgramCell(
                        program = cell.program,
                        archive = isInArchive(channel.catchup, cell.program.startMillis, cell.program.endMillis, nowMillis),
                        nowMillis = nowMillis,
                        selected = selected,
                        gridFocused = gridFocused,
                        clock = clock,
                        modifier = Modifier.offset(x = place.offsetDp.dp).width(place.widthDp.dp).fillMaxHeight(),
                    )
                }
            }
            // Cursor on a gap (no programme): outline the 30-minute slot.
            if (focusMillis != null) {
                val idx = cellIndexAt(cells, focusMillis)
                val cell = cells.getOrNull(idx)
                if (cell is GuideCell.Gap) {
                    placeCell(cell.start, cell.end, viewStart, viewEnd, DP_PER_MINUTE)?.let { place ->
                        Box(
                            Modifier
                                .offset(x = place.offsetDp.dp)
                                .width(place.widthDp.dp)
                                .fillMaxHeight()
                                .padding(1.dp)
                                .border(
                                    BorderStroke(2.dp, if (gridFocused) NovaColors.Accent else NovaColors.TextMuted),
                                    CellShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramCell(
    program: EpgProgram,
    archive: Boolean,
    nowMillis: Long,
    selected: Boolean,
    gridFocused: Boolean,
    clock: ClockFormat,
    modifier: Modifier,
) {
    val live = program.isLive(nowMillis)
    val past = program.endMillis <= nowMillis
    val background = when {
        selected && gridFocused -> NovaColors.Accent
        selected -> NovaColors.SurfaceBright
        live -> NovaColors.SurfaceRaised
        else -> NovaColors.Surface
    }
    val titleColor = when {
        selected && gridFocused -> NovaColors.OnAccent
        past -> NovaColors.TextSecondary
        else -> NovaColors.TextPrimary
    }
    val subColor = if (selected && gridFocused) NovaColors.OnAccent.copy(alpha = 0.8f) else NovaColors.TextMuted
    Box(modifier.padding(1.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CellShape)
                .background(background),
        ) {
            if (live && !(selected && gridFocused)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(NovaColors.Live))
            }
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (archive) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = stringResource(R.string.guide_archive_badge),
                        tint = if (selected && gridFocused) NovaColors.OnAccent else NovaColors.Secondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                } else if (!past && !live && selected) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = subColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = titleColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = clock.range(program.startMillis, program.endMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = subColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}
