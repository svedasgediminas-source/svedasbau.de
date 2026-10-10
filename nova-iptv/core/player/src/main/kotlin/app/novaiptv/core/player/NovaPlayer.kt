package app.novaiptv.core.player

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.novaiptv.core.common.net.HttpClients
import app.novaiptv.core.model.PlaybackErrorKind
import app.novaiptv.core.model.PlaybackState
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.model.TrackOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Thin wrapper around ExoPlayer for IPTV playback (HLS, DASH, MPEG-TS/progressive over HTTP).
 *
 * - All methods must be called on the main thread (ExoPlayer's application looper).
 * - Per-stream user agent / referrer / headers come from [StreamSource] (see [StreamRequests]).
 * - Live streams reconnect automatically with a bounded [RetryPolicy]; BEHIND_LIVE_WINDOW rejoins
 *   the live edge without counting as a failure.
 * - DRM-protected content is reported as [PlaybackErrorKind.DrmProtected]; nothing is bypassed.
 *
 * The [okHttpClient] is not owned: [release] does not shut it down.
 */
@OptIn(UnstableApi::class)
class NovaPlayer(
    context: Context,
    private val okHttpClient: OkHttpClient,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val subtitleTracks: StateFlow<List<TrackOption>> = _subtitleTracks.asStateFlow()

    private var isLive = false
    private var released = false
    private var retryingAttempt: Int? = null
    private var retryJob: Job? = null
    private var tickerJob: Job? = null
    private var trackRefs: Map<String, TrackRef> = emptyMap()

    private class TrackRef(val group: TrackGroup, val index: Int)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && isLive) {
                // A live stream should never end: the server closed the connection.
                handleFailure(PlaybackErrorKind.Network, "STREAM_ENDED")
                return
            }
            updateState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startTicker()
            } else {
                stopTicker()
                retryPolicy.onInterrupted()
            }
            updateState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            updateState()
        }

        override fun onPlayerError(error: PlaybackException) {
            onError(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateTracks(tracks)
        }
    }

    private val exoPlayer: ExoPlayer

    init {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        exoPlayer = ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer.addListener(listener)
    }

    /** The underlying player, for attaching to a PlayerView. */
    val player: Player get() = exoPlayer

    fun play(stream: StreamSource, startPositionMillis: Long = 0, isLive: Boolean) {
        if (released) return
        cancelRetry()
        retryPolicy.reset()
        this.isLive = isLive
        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
        trackRefs = emptyMap()
        _state.value = PlaybackState.Buffering
        val mediaSource = buildMediaSource(stream, isLive)
        if (!isLive && startPositionMillis > 0) {
            exoPlayer.setMediaSource(mediaSource, startPositionMillis)
        } else {
            exoPlayer.setMediaSource(mediaSource, true)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /** Stops playback and clears the media (e.g. when the requested content cannot be played). */
    fun stop() {
        if (released) return
        cancelRetry()
        stopTicker()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = PlaybackState.Idle
    }

    fun pause() {
        if (released) return
        exoPlayer.pause()
    }

    fun resume() {
        if (released) return
        when {
            exoPlayer.currentMediaItem == null -> Unit
            exoPlayer.playbackState == Player.STATE_IDLE -> retry()
            exoPlayer.playbackState == Player.STATE_ENDED && !isLive -> {
                exoPlayer.seekToDefaultPosition()
                exoPlayer.play()
            }
            else -> exoPlayer.play()
        }
    }

    fun togglePlayPause() {
        if (released) return
        if (exoPlayer.playWhenReady && exoPlayer.playbackState != Player.STATE_ENDED &&
            exoPlayer.playbackState != Player.STATE_IDLE
        ) {
            pause()
        } else {
            resume()
        }
    }

    /**
     * Manual retry (e.g. the Retry button) or rejoining a live stream after the app returns to
     * the foreground. Resets the retry budget. Live streams jump to the live edge.
     */
    fun retry() {
        if (released || exoPlayer.currentMediaItem == null) return
        cancelRetry()
        retryPolicy.reset()
        _state.value = PlaybackState.Buffering
        restartPlayback()
    }

    fun seekBy(deltaMillis: Long) {
        if (released) return
        seekTo(exoPlayer.currentPosition + deltaMillis)
    }

    fun seekTo(ms: Long) {
        if (released || !exoPlayer.isCurrentMediaItemSeekable) return
        val duration = durationMillis()
        val target = if (duration > 0) ms.coerceIn(0L, duration) else ms.coerceAtLeast(0L)
        exoPlayer.seekTo(target)
    }

    fun selectAudio(id: String) {
        if (released) return
        val ref = trackRefs[id] ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(ref.group, ref.index))
            .build()
    }

    /** [id] null turns subtitles off. */
    fun selectSubtitle(id: String?) {
        if (released) return
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        if (id == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val ref = trackRefs[id] ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(ref.group, ref.index))
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    /** Applies user preferences (ISO 639 language tags, null = no preference). */
    fun setTrackPreferences(audioLanguage: String?, subtitleLanguage: String?, subtitlesEnabled: Boolean) {
        if (released) return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(audioLanguage?.takeIf { it.isNotBlank() })
            .setPreferredTextLanguage(subtitleLanguage?.takeIf { it.isNotBlank() })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .build()
    }

    fun currentPositionMillis(): Long = if (released) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)

    fun durationMillis(): Long {
        if (released) return 0L
        val duration = exoPlayer.duration
        return if (duration == C.TIME_UNSET || duration < 0) 0L else duration
    }

    fun release() {
        if (released) return
        released = true
        scope.cancel()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
        _state.value = PlaybackState.Idle
    }

    // ---------------------------------------------------------------------------------------

    private fun buildMediaSource(stream: StreamSource, isLive: Boolean): MediaSource {
        val spec = StreamRequests.spec(stream, HttpClients.USER_AGENT)
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(spec.userAgent)
            .setDefaultRequestProperties(spec.headers)
        // IPTV MPEG-TS often lacks proper access-unit delimiters / starts mid-GOP.
        val extractorsFactory = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        val itemBuilder = MediaItem.Builder().setUri(stream.url)
        StreamRequests.mimeTypeFor(stream)?.let { itemBuilder.setMimeType(it) }
        if (isLive) itemBuilder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        return mediaSourceFactory.createMediaSource(itemBuilder.build())
    }

    private fun onError(error: PlaybackException) {
        if (PlaybackErrors.isBehindLiveWindow(error.errorCode)) {
            exoPlayer.seekToDefaultPosition()
            exoPlayer.prepare()
            return
        }
        handleFailure(PlaybackErrors.classify(error.errorCode), error.errorCodeName)
    }

    private fun handleFailure(kind: PlaybackErrorKind, message: String?) {
        stopTicker()
        cancelRetry()
        val delayMs = retryPolicy.onFailure(kind, isLive)
        if (delayMs == null) {
            retryingAttempt = null
            // Message is an error-code name only: stream URLs (which may hold credentials) are never exposed.
            _state.value = PlaybackState.Error(kind, message)
            return
        }
        val attempt = retryPolicy.attempts
        retryingAttempt = attempt
        _state.value = PlaybackState.Retrying(attempt, retryPolicy.maxAttempts)
        retryJob = scope.launch {
            delay(delayMs)
            restartPlayback()
        }
    }

    private fun restartPlayback() {
        if (released) return
        if (isLive) exoPlayer.seekToDefaultPosition()
        if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        retryingAttempt = null
    }

    private fun updateState() {
        if (released) return
        val retrying = retryingAttempt
        val playbackState = exoPlayer.playbackState
        // Keep the Error published by handleFailure until the player is prepared again.
        if (retrying == null && exoPlayer.playerError != null && _state.value is PlaybackState.Error) return
        if (retrying == null && _state.value is PlaybackState.Error && playbackState == Player.STATE_IDLE) return
        _state.value = when (playbackState) {
            Player.STATE_READY -> {
                retryingAttempt = null
                if (exoPlayer.isPlaying) {
                    PlaybackState.Playing(currentPositionMillis(), durationMillis(), isLive)
                } else {
                    PlaybackState.Paused(currentPositionMillis(), durationMillis())
                }
            }
            Player.STATE_BUFFERING ->
                if (retrying != null) PlaybackState.Retrying(retrying, retryPolicy.maxAttempts) else PlaybackState.Buffering
            Player.STATE_ENDED -> PlaybackState.Ended
            else ->
                if (retrying != null) PlaybackState.Retrying(retrying, retryPolicy.maxAttempts) else PlaybackState.Idle
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    _state.value = PlaybackState.Playing(currentPositionMillis(), durationMillis(), isLive)
                    retryPolicy.onPlaying(SystemClock.elapsedRealtime())
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun updateTracks(tracks: Tracks) {
        val refs = HashMap<String, TrackRef>()
        val audio = ArrayList<TrackOption>()
        val text = ArrayList<TrackOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format: Format = group.getTrackFormat(trackIndex)
                val id = "$type:$groupIndex:$trackIndex"
                refs[id] = TrackRef(group.mediaTrackGroup, trackIndex)
                val option = TrackOption(
                    id = id,
                    label = TrackLabels.label(
                        format.label,
                        format.language,
                        if (type == C.TRACK_TYPE_AUDIO) format.channelCount else Format.NO_VALUE,
                    ),
                    language = format.language,
                    selected = group.isTrackSelected(trackIndex),
                )
                if (type == C.TRACK_TYPE_AUDIO) audio += option else text += option
            }
        }
        trackRefs = refs
        _audioTracks.value = audio
        _subtitleTracks.value = text
    }

    private companion object {
        // Modest buffers for low-RAM TV boxes; start quickly, rebuffer a bit more conservatively.
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 30_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_000
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000
        const val TICK_MS = 1_000L
    }
}
