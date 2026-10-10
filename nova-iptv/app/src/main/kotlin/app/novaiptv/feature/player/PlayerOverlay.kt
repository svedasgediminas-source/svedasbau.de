package app.novaiptv.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.ChannelLogo
import app.novaiptv.core.designsystem.NovaButton
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.model.AspectRatioMode
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.PlaybackErrorKind
import app.novaiptv.core.model.PlaybackState
import app.novaiptv.core.model.TrackOption
import kotlinx.coroutines.delay
import java.util.Date

@Composable
internal fun PlayerOverlay(
    ui: PlayerUiState,
    playback: PlaybackState,
    mode: OverlayMode,
    controlsFocus: FocusRequester,
    errorFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onStartOver: () -> Unit,
    onOpenPanel: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    // Keep the last known position so the time bar does not jump while buffering.
    val lastPosition = remember { longArrayOf(0L, 0L) }
    when (playback) {
        is PlaybackState.Playing -> { lastPosition[0] = playback.positionMillis; lastPosition[1] = playback.durationMillis }
        is PlaybackState.Paused -> { lastPosition[0] = playback.positionMillis; lastPosition[1] = playback.durationMillis }
        else -> Unit
    }
    val positionMillis = lastPosition[0]
    val durationMillis = lastPosition[1]

    val errorText: String? = when {
        ui.contentError != null -> stringResource(ui.contentError.messageRes())
        playback is PlaybackState.Error -> stringResource(playback.kind.messageRes())
        else -> null
    }

    Box(Modifier.fillMaxSize()) {
        // Centre: loading / buffering / reconnecting.
        val busyText: String? = when {
            errorText != null -> null
            ui.loading -> stringResource(R.string.player_loading)
            playback is PlaybackState.Retrying -> stringResource(R.string.player_retrying, playback.attempt, playback.maxAttempts)
            playback is PlaybackState.Buffering -> stringResource(R.string.player_buffering)
            else -> null
        }
        if (busyText != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spinner()
                Spacer(Modifier.height(12.dp))
                Text(busyText, style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextPrimary)
            }
        }

        // Bottom info bar (+ controls).
        AnimatedVisibility(
            visible = mode != OverlayMode.Hidden && errorText == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InfoBar(
                ui = ui,
                playback = playback,
                positionMillis = positionMillis,
                durationMillis = durationMillis,
                showControls = mode == OverlayMode.Controls,
                controlsFocus = controlsFocus,
                onTogglePlay = onTogglePlay,
                onStartOver = onStartOver,
                onOpenPanel = onOpenPanel,
            )
        }

        if (errorText != null) {
            ErrorBox(
                message = errorText,
                focus = errorFocus,
                onRetry = onRetry,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Top-right: number being typed on the remote.
        val entry = ui.numberEntry
        if (entry != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(NovaDimens.ScreenPadding)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NovaColors.Scrim)
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text(
                    text = entry,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = NovaColors.Accent,
                )
            }
        }

        // Top-centre: transient notice.
        val notice = ui.notice
        if (notice != null) {
            val text = when (notice) {
                is PlayerNotice.ChannelNotFound -> stringResource(R.string.player_channel_not_found, notice.number)
                PlayerNotice.NoMoreChannels -> stringResource(R.string.player_no_more_channels)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = NovaDimens.ScreenPadding)
                    .clip(RoundedCornerShape(50))
                    .background(NovaColors.Scrim)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun InfoBar(
    ui: PlayerUiState,
    playback: PlaybackState,
    positionMillis: Long,
    durationMillis: Long,
    showControls: Boolean,
    controlsFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onStartOver: () -> Unit,
    onOpenPanel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(horizontal = NovaDimens.ScreenPadding, vertical = 32.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ui.isLive || ui.isCatchup) {
                ChannelLogo(url = ui.logoUrl, name = ui.title, modifier = Modifier.size(width = 96.dp, height = 64.dp))
                Spacer(Modifier.width(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val number = ui.channelNumber
                    if (number != null && ui.showChannelNumbers && (ui.isLive || ui.isCatchup)) {
                        Text(
                            text = number.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = NovaColors.Accent,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = ui.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = NovaColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (ui.isLive || ui.isCatchup) {
                        Spacer(Modifier.width(12.dp))
                        Badge(
                            text = stringResource(if (ui.isLive) R.string.player_live else R.string.player_archive),
                            color = if (ui.isLive) NovaColors.Live else NovaColors.Secondary,
                        )
                    }
                }
                val subtitle = ui.subtitle
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NovaColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (ui.isLive) {
            LiveProgramme(current = ui.currentProgram, next = ui.nextProgram)
        } else if (!ui.loading) {
            TimeBar(positionMillis = positionMillis, durationMillis = durationMillis)
            val resumed = ui.resumedFromMillis
            if (resumed != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.player_resumed_from, formatDuration(resumed)),
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                )
            }
            if (playback is PlaybackState.Ended) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.player_ended), style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextPrimary)
            }
        }

        if (showControls) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!ui.isLive) {
                    val playing = playback is PlaybackState.Playing || playback is PlaybackState.Buffering
                    NovaButton(
                        text = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                        onClick = onTogglePlay,
                        primary = true,
                        modifier = Modifier.focusRequester(controlsFocus),
                    )
                    NovaButton(text = stringResource(R.string.player_start_over), onClick = onStartOver)
                    NovaButton(text = stringResource(R.string.player_settings), onClick = onOpenPanel)
                } else {
                    NovaButton(
                        text = stringResource(R.string.player_settings),
                        onClick = onOpenPanel,
                        modifier = Modifier.focusRequester(controlsFocus),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveProgramme(current: EpgProgram?, next: EpgProgram?) {
    if (current == null) {
        Text(stringResource(R.string.player_no_epg), style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextMuted)
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(current) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val range = timeFormat.format(Date(current.startMillis)) + " – " + timeFormat.format(Date(current.endMillis))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = current.title,
            style = MaterialTheme.typography.titleMedium,
            color = NovaColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(16.dp))
        Text(range, style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextSecondary)
    }
    Spacer(Modifier.height(8.dp))
    val duration = current.durationMillis
    ProgressBar(fraction = if (duration > 0) (now - current.startMillis).toFloat() / duration else 0f)
    if (next != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = timeFormat.format(Date(next.startMillis)) + "  " +
                stringResource(R.string.player_next_programme, next.title),
            style = MaterialTheme.typography.bodyMedium,
            color = NovaColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TimeBar(positionMillis: Long, durationMillis: Long) {
    ProgressBar(fraction = if (durationMillis > 0) positionMillis.toFloat() / durationMillis else 0f)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(formatDuration(positionMillis), style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        if (durationMillis > 0) {
            Text(formatDuration(durationMillis), style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextSecondary)
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.25f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(NovaColors.Accent),
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
    }
}

@Composable
private fun ErrorBox(
    message: String,
    focus: FocusRequester,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(NovaColors.Surface.copy(alpha = 0.94f))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.player_error_title),
            style = MaterialTheme.typography.headlineSmall,
            color = NovaColors.Error,
        )
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = NovaColors.TextPrimary)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NovaButton(
                text = stringResource(R.string.player_retry),
                onClick = onRetry,
                primary = true,
                modifier = Modifier.focusRequester(focus),
            )
            NovaButton(text = stringResource(R.string.player_back), onClick = onBack)
        }
    }
}

@Composable
private fun Spinner() {
    val transition = rememberInfiniteTransition(label = "player-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(Modifier.size(56.dp)) {
        val stroke = 5.dp.toPx()
        val inset = stroke / 2
        drawArc(
            color = NovaColors.Accent,
            startAngle = angle,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** Right-side panel: audio track, subtitles, aspect ratio. */
@Composable
internal fun TrackPanel(
    audioTracks: List<TrackOption>,
    subtitleTracks: List<TrackOption>,
    aspectRatio: AspectRatioMode,
    firstItemFocus: FocusRequester,
    onSelectAudio: (TrackOption) -> Unit,
    onSelectSubtitle: (TrackOption?) -> Unit,
    onSelectAspect: (AspectRatioMode) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(400.dp)
                .background(NovaColors.Surface.copy(alpha = 0.96f))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(stringResource(R.string.player_audio))
            if (audioTracks.isEmpty()) {
                Text(stringResource(R.string.player_no_tracks), style = MaterialTheme.typography.bodyMedium, color = NovaColors.TextMuted)
            }
            audioTracks.forEachIndexed { index, track ->
                OptionButton(
                    text = track.label.ifEmpty { stringResource(R.string.player_track_fallback, index + 1) },
                    selected = track.selected,
                    onClick = { onSelectAudio(track) },
                    modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.player_subtitles))
            OptionButton(
                text = stringResource(R.string.player_subtitles_off),
                selected = subtitleTracks.none { it.selected },
                onClick = { onSelectSubtitle(null) },
                modifier = if (audioTracks.isEmpty()) Modifier.focusRequester(firstItemFocus) else Modifier,
            )
            subtitleTracks.forEachIndexed { index, track ->
                OptionButton(
                    text = track.label.ifEmpty { stringResource(R.string.player_track_fallback, index + 1) },
                    selected = track.selected,
                    onClick = { onSelectSubtitle(track) },
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.player_aspect_ratio))
            AspectRatioMode.entries.forEach { mode ->
                OptionButton(
                    text = stringResource(mode.labelRes()),
                    selected = mode == aspectRatio,
                    onClick = { onSelectAspect(mode) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = NovaColors.TextSecondary)
}

@Composable
private fun OptionButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NovaButton(
        text = if (selected) "✓  $text" else text,
        onClick = onClick,
        primary = selected,
        modifier = modifier.fillMaxWidth(),
    )
}

private fun PlayerContentError.messageRes(): Int = when (this) {
    PlayerContentError.NotFound -> R.string.player_error_not_found
    PlayerContentError.CatchupUnavailable -> R.string.player_error_catchup_unavailable
    PlayerContentError.StreamFailed -> R.string.player_error_resolve
}

private fun PlaybackErrorKind.messageRes(): Int = when (this) {
    PlaybackErrorKind.Network -> R.string.player_error_network
    PlaybackErrorKind.SourceUnavailable -> R.string.player_error_source
    PlaybackErrorKind.UnsupportedFormat -> R.string.player_error_format
    PlaybackErrorKind.DrmProtected -> R.string.player_error_drm
    PlaybackErrorKind.Decoder -> R.string.player_error_decoder
    PlaybackErrorKind.Unknown -> R.string.player_error_unknown
}

private fun AspectRatioMode.labelRes(): Int = when (this) {
    AspectRatioMode.Fit -> R.string.player_aspect_fit
    AspectRatioMode.Fill -> R.string.player_aspect_fill
    AspectRatioMode.Zoom -> R.string.player_aspect_zoom
    AspectRatioMode.Stretch -> R.string.player_aspect_stretch
}

/** "m:ss" or "h:mm:ss". */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
