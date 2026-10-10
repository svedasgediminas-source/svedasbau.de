package app.novaiptv.feature.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.novaiptv.core.model.AspectRatioMode
import app.novaiptv.core.model.PlaybackState
import app.novaiptv.core.player.NovaPlayer
import app.novaiptv.di.LocalAppContainer
import kotlinx.coroutines.delay

private const val OVERLAY_TIMEOUT_MS = 5_000L

/** Overlay level: [Info] is passive (title/EPG/time bar), [Controls] adds focusable buttons. */
internal enum class OverlayMode { Hidden, Info, Controls }

/**
 * Full-screen player for live channels, catch-up, movies and episodes.
 *
 * Remote keys: UP/DOWN and CH+/CH- switch channel (live/timeshift), LEFT/RIGHT seek ±10 s and
 * FF/REW ±30 s (VOD/catch-up/timeshift; holding accelerates to 30 s / 60 s / 5 min, the seek is
 * committed after release), OK toggles pause (VOD/archive) or shows the controls, long-press OK /
 * MENU opens the settings panel (audio, subtitles, speed, aspect), digits jump to a channel number,
 * BACK closes panels then exits.
 *
 * Live channels that declare catch-up can be rewound: LEFT / REW step back into the archive,
 * PAUSE + PLAY later continues from the paused moment, RIGHT past the live edge or "Back to live"
 * returns to live, "Restart" plays the current programme from its start. Channels without
 * catch-up get no fake timeshift (LEFT just shows the info bar, PAUSE pauses the live player).
 */
@Composable
fun PlayerScreen(args: PlayerArgs, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val appContext = LocalContext.current.applicationContext
    val viewModel: PlayerViewModel = viewModel(key = "player:$args") {
        // Uses the app's shared OkHttpClient (container.okHttpClient); nothing is shut down on exit.
        PlayerViewModel(args, PlayerDependencies.from(container), appContext)
    }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val audioTracks by viewModel.audioTracks.collectAsStateWithLifecycle()
    val subtitleTracks by viewModel.subtitleTracks.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }

    var overlay by remember { mutableStateOf(OverlayMode.Info) }
    var panelOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var centerDown by remember { mutableStateOf(false) }
    var centerLongHandled by remember { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }

    val hasError = ui.contentError != null || playback is PlaybackState.Error
    val isPaused = playback is PlaybackState.Paused
    val keepOverlay = panelOpen || hasError || isPaused || ui.loading || playback is PlaybackState.Ended ||
        ui.seekPreview != null

    fun show(mode: OverlayMode) {
        if (mode.ordinal > overlay.ordinal || mode == OverlayMode.Controls) overlay = mode
        interaction++
    }

    // Auto-hide after 5 s without interaction.
    LaunchedEffect(overlay, interaction, keepOverlay) {
        if (overlay != OverlayMode.Hidden && !keepOverlay) {
            delay(OVERLAY_TIMEOUT_MS)
            overlay = OverlayMode.Hidden
        }
    }

    // Focus must never get lost: always park it on something that exists.
    LaunchedEffect(panelOpen, overlay, hasError) {
        val target = when {
            panelOpen -> panelFocus
            hasError -> errorFocus
            overlay == OverlayMode.Controls -> controlsFocus
            else -> rootFocus
        }
        if (!target.tryRequestFocus()) rootFocus.tryRequestFocus()
    }

    fun handleBack() {
        when {
            ui.numberEntry != null -> viewModel.cancelNumberEntry()
            panelOpen -> panelOpen = false
            overlay == OverlayMode.Controls && !hasError -> overlay = OverlayMode.Hidden
            else -> onBack()
        }
    }

    BackHandler { handleBack() }

    fun onKey(event: KeyEvent): Boolean {
        val code = event.key.nativeKeyCode
        val down = event.type == KeyEventType.KeyDown
        val up = event.type == KeyEventType.KeyUp
        val repeat = event.nativeKeyEvent.repeatCount

        // BACK is handled here, before Compose turns an unconsumed BACK into a focus "exit" move:
        // with a button focused (track panel, controls, error panel) the first press only moved
        // focus to the root and the user had to press BACK twice.
        if (code == AndroidKeyEvent.KEYCODE_BACK) {
            if (up && !event.nativeKeyEvent.isCanceled) handleBack()
            return true
        }
        val controlsShown = panelOpen || hasError || overlay == OverlayMode.Controls
        if (down && overlay != OverlayMode.Hidden) interaction++

        // OK released after a press we consumed (short press action / long press already handled).
        if (up && centerDown && code.isSelectKey()) {
            centerDown = false
            if (!centerLongHandled) {
                when {
                    ui.numberEntry != null -> viewModel.commitNumberEntry()
                    !ui.isLive && !hasError -> {
                        viewModel.togglePlayPause()
                        show(OverlayMode.Controls)
                    }
                    else -> show(OverlayMode.Controls)
                }
            }
            centerLongHandled = false
            return true
        }

        // Release of a held seek key: commit the pending seek shortly.
        if (up && viewModel.isSeeking && code.isSeekKey()) {
            viewModel.onSeekKeyUp()
            return true
        }

        val digit = code.digitOrNull()
        if (digit != null) {
            if (!ui.isChannel) return false
            if (down) viewModel.onDigit(digit)
            show(OverlayMode.Info)
            return true
        }

        when (code) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (down && repeat == 0) {
                    viewModel.togglePlayPause()
                    show(OverlayMode.Info)
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (down && repeat == 0) {
                    viewModel.play()
                    show(OverlayMode.Info)
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && repeat == 0) {
                    viewModel.pause()
                    show(OverlayMode.Info)
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                // Live without catch-up: nothing to rewind into (no fake timeshift).
                if (ui.isLive && !ui.canTimeshift) return false
                if (down && !hasError) {
                    val direction = if (code == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 1 else -1
                    viewModel.onSeekKey(direction, media = true, repeatCount = repeat, eventTimeMillis = event.nativeKeyEvent.eventTime)
                    show(OverlayMode.Info)
                }
                if (up) viewModel.onSeekKeyUp()
                return true
            }
            AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (!ui.isChannel) return false
                if (down && repeat == 0) {
                    viewModel.switchChannel(if (code == AndroidKeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
                    panelOpen = false
                    overlay = OverlayMode.Info
                    interaction++
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_CAPTIONS -> {
                if (down && repeat == 0 && !hasError) panelOpen = !panelOpen
                return true
            }
            AndroidKeyEvent.KEYCODE_INFO -> {
                if (down && repeat == 0) show(OverlayMode.Controls)
                return true
            }
        }

        // With buttons on screen, D-pad/OK belong to focus navigation.
        if (controlsShown) return false

        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down && repeat == 0) {
                    if (ui.isChannel) {
                        viewModel.switchChannel(if (code == AndroidKeyEvent.KEYCODE_DPAD_UP) 1 else -1)
                        overlay = OverlayMode.Info
                        interaction++
                    } else {
                        show(OverlayMode.Controls)
                    }
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    // VOD/catch-up/timeshift seek; live catch-up channels rewind into the archive with LEFT.
                    // Returns false (nothing to do) e.g. RIGHT on the live edge: just show the info bar.
                    val direction = if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    viewModel.onSeekKey(direction, media = false, repeatCount = repeat, eventTimeMillis = event.nativeKeyEvent.eventTime)
                    show(OverlayMode.Info)
                }
                if (up) viewModel.onSeekKeyUp()
                return true
            }
        }

        if (code.isSelectKey()) {
            if (down) {
                if (repeat == 0) {
                    centerDown = true
                    centerLongHandled = false
                } else if (centerDown && !centerLongHandled) {
                    // Long press: open the track/aspect panel.
                    centerLongHandled = true
                    panelOpen = true
                }
            }
            return true
        }
        return false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { onKey(it) }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        PlayerSurface(player = viewModel.novaPlayer, aspectRatio = ui.aspectRatio)

        PlayerOverlay(
            ui = ui,
            playback = playback,
            speed = speed,
            mode = overlay,
            controlsFocus = controlsFocus,
            errorFocus = errorFocus,
            onTogglePlay = { viewModel.togglePlayPause(); interaction++ },
            onStartOver = { viewModel.startOver(); interaction++ },
            onPlay = { viewModel.play(); interaction++ },
            onRestartProgramme = { viewModel.restartProgramme(); interaction++ },
            onBackToLive = { viewModel.backToLive(); overlay = OverlayMode.Info; interaction++ },
            onOpenPanel = { panelOpen = true },
            onRetry = { viewModel.retry(); overlay = OverlayMode.Info },
            onBack = onBack,
        )

        if (panelOpen) {
            TrackPanel(
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                aspectRatio = ui.aspectRatio,
                speed = if (ui.speedAdjustable) speed else null,
                firstItemFocus = panelFocus,
                onSelectAudio = { viewModel.selectAudio(it) },
                onSelectSubtitle = { viewModel.selectSubtitle(it) },
                onSelectAspect = { viewModel.setAspectRatio(it) },
                onSelectSpeed = { viewModel.setSpeed(it) },
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(player: NovaPlayer, aspectRatio: AspectRatioMode) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                keepScreenOn = true
                // Compose owns focus and key handling.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                this.player = player.player
            }
        },
        update = { view ->
            if (view.player !== player.player) view.player = player.player
            view.resizeMode = resizeModeFor(aspectRatio)
        },
        onRelease = { view -> view.player = null },
    )
}

/**
 * Fit = letterbox, Fill = fill width (crop/letterbox height), Zoom = fill screen and crop,
 * Stretch = fill screen ignoring the aspect ratio.
 */
@OptIn(UnstableApi::class)
internal fun resizeModeFor(mode: AspectRatioMode): Int = when (mode) {
    AspectRatioMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectRatioMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
    AspectRatioMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioMode.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

private fun Int.isSelectKey(): Boolean =
    this == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        this == AndroidKeyEvent.KEYCODE_ENTER ||
        this == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

private fun Int.isSeekKey(): Boolean =
    this == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
        this == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
        this == AndroidKeyEvent.KEYCODE_MEDIA_REWIND ||
        this == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD

private fun Int.digitOrNull(): Int? = when (this) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> this - AndroidKeyEvent.KEYCODE_0
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> this - AndroidKeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

/** requestFocus throws if the requester is not attached (e.g. the target is not composed). */
internal fun FocusRequester.tryRequestFocus(): Boolean =
    try {
        requestFocus()
        true
    } catch (e: IllegalStateException) {
        false
    }
