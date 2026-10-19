package app.novaiptv.core.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.novaiptv.core.common.net.HttpClients
import app.novaiptv.core.model.PlaybackErrorKind
import app.novaiptv.core.model.PlaybackState
import app.novaiptv.core.model.StreamFormat
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin wrapper around ExoPlayer for IPTV playback (HLS, DASH, MPEG-TS/progressive over HTTP).
 *
 * - All methods must be called on the main thread (ExoPlayer's application looper).
 * - Per-stream user agent / referrer / headers come from [StreamSource] (see [StreamRequests]).
 * - Live streams reconnect automatically with a bounded [RetryPolicy]; BEHIND_LIVE_WINDOW rejoins
 *   the live edge without counting as a failure.
 * - DRM-protected content is reported as [PlaybackErrorKind.DrmProtected]; nothing is bypassed.
 * - Format: playlist hint / URL extension; otherwise a short probe ([StreamProber]) decides
 *   HLS / DASH / MPEG-TS before preparing.
 * - Start-up recovery ([PlaybackRecovery]): a parsing error, HTTP 401/403, or buffering without
 *   progress ([Watchdog]) moves through a finite list of attempts (other container, Xtream HLS
 *   path variants for live, further User-Agent candidates) before an error is shown. Never loops.
 * - Logs (tag "NovaPlayer") HTTP status / Content-Type / bytes received during start-up; never URLs.
 * - Requests follow redirects across http/https and use HTTP/1.1 (like VLC and stock players;
 *   some IPTV servers misbehave on long-lived HTTP/2 streams).
 *
 * The [okHttpClient] is not owned: [release] does not shut it down (the derived client shares
 * its connection pool and dispatcher).
 */
@OptIn(UnstableApi::class)
class NovaPlayer(
    context: Context,
    okHttpClient: OkHttpClient,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val watchdog: Watchdog = Watchdog(),
) {
    /** Buffering limits: no byte at all / no start / live stream stuck mid-way. */
    data class Watchdog(
        val noDataMs: Long = 10_000,
        val startStallMs: Long = 20_000,
        val midStreamStallMs: Long = 15_000,
    )

    /** Status lines are logged only while a stream starts (segment loads would flood logcat). */
    @Volatile
    private var logResponses = true

    private val okHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (logResponses) {
                // No URL / host: they may carry credentials. Scheme shows http <-> https redirects.
                Log.i(
                    TAG,
                    "http ${chain.request().url.scheme}: ${response.code} type=${response.header("Content-Type")} " +
                        "length=${response.header("Content-Length")} location=${response.header("Location") != null}",
                )
            }
            response
        }
        .build()

    private val prober = StreamProber(this.okHttpClient)

    /** Agents tried in order when the stream does not specify one (setting "Stream User-Agent"). */
    @Volatile
    private var userAgents: List<String> = listOf(HttpClients.USER_AGENT)

    private var prepareJob: Job? = null
    private var watchdogJob: Job? = null
    /** The stream as requested (attempts may change URL / format / agent). */
    private var currentStream: StreamSource? = null
    private var currentAgents: List<String> = userAgents
    private var attempts: List<PlaybackRecovery.Attempt> = emptyList()
    private var attemptIndex = 0
    private var currentFormat: StreamFormat = StreamFormat.Unknown
    /** Hint of the first start-up failure (the original URL), shown if every attempt fails. */
    private var firstFailureHint: String? = null
    /** The current attempt reached playback; later failures use the normal retry policy. */
    private var hasPlayed = false
    private val bytesLoaded = AtomicLong(0)

    private val transferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytesLoaded.addAndGet(bytesTransferred.toLong())
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val subtitleTracks: StateFlow<List<TrackOption>> = _subtitleTracks.asStateFlow()

    /** Current playback speed; always [PlaybackSpeeds.NORMAL] for live streams. */
    private val _speed = MutableStateFlow(PlaybackSpeeds.NORMAL)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Speed chosen by the user for non-live playback (kept across items of this player). */
    private var requestedSpeed = PlaybackSpeeds.NORMAL

    private var isLive = false
    private var released = false
    private var retryingAttempt: Int? = null
    /** Consecutive BEHIND_LIVE_WINDOW rejoins; bounded so a broken live playlist can't loop forever. */
    private var behindLiveRejoins = 0
    private var retryJob: Job? = null
    private var tickerJob: Job? = null
    private var trackRefs: Map<String, TrackRef> = emptyMap()

    private class TrackRef(val group: TrackGroup, val index: Int)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) onAttemptWorked()
            if (playbackState == Player.STATE_ENDED && isLive) {
                // A live stream should never end: the server closed the connection.
                val hint = PlaybackErrors.hint("STREAM_ENDED", null, StreamProbe.label(currentFormat))
                val failure = if (bytesLoaded.get() == 0L) PlaybackRecovery.Failure.NoData else PlaybackRecovery.Failure.Stall
                if (tryRecover(failure, hint)) return
                handleFailure(PlaybackErrorKind.Network, hint)
                return
            }
            updateState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                onAttemptWorked()
                behindLiveRejoins = 0
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

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _speed.value = playbackParameters.speed
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
        // Decoder fallback: TV SoCs often list a hardware decoder that then fails to init for odd
        // IPTV streams (interlaced H.264, unusual profiles); try the next decoder instead of failing.
        val renderersFactory = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
        exoPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer.addListener(listener)
    }

    /** The underlying player, for attaching to a PlayerView. */
    val player: Player get() = exoPlayer

    /**
     * Starts [stream]. [startPositionMillis] applies to non-live streams (VOD resume, catch-up /
     * timeshift offset). [playWhenReady] false prepares the stream paused at that position.
     */
    fun play(
        stream: StreamSource,
        startPositionMillis: Long = 0,
        isLive: Boolean,
        playWhenReady: Boolean = true,
    ) {
        if (released) return
        cancelRetry()
        retryPolicy.reset()
        behindLiveRejoins = 0
        this.isLive = isLive
        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
        trackRefs = emptyMap()
        prepareJob?.cancel()
        prepareJob = null
        stopWatchdog()
        currentStream = stream
        val known = StreamProbe.knownFormat(stream)
        val cached = if (known == StreamFormat.Unknown) prober.cached(stream.url) else null
        if (known != StreamFormat.Unknown || cached != null || !StreamProbe.needsProbe(stream)) {
            _state.value = PlaybackState.Buffering
            startAttempts(stream, cached ?: known, startPositionMillis, playWhenReady)
            return
        }
        // Unknown format: stop the previous item, probe off the main thread, then prepare.
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = PlaybackState.Buffering
        val agents = agentsFor(stream)
        val spec = StreamRequests.spec(stream, agents.first())
        prepareJob = scope.launch {
            val format = prober.probe(stream.url, spec.userAgent, spec.headers)
            if (released || currentStream !== stream) return@launch
            startAttempts(stream, format, startPositionMillis, playWhenReady)
        }
    }

    /**
     * User-Agent candidates for streams that do not specify their own (see
     * [StreamUserAgents.candidates]). Applies from the next [play].
     */
    fun setUserAgentCandidates(agents: List<String>) {
        userAgents = agents.filter { it.isNotBlank() }.distinct().ifEmpty { listOf(HttpClients.USER_AGENT) }
    }

    /** Candidates for [stream]: its own agent only, or the setting's list with the last agent that worked for this host first. */
    private fun agentsFor(stream: StreamSource): List<String> {
        val own = StreamRequests.spec(stream, "").userAgent
        if (own.isNotEmpty()) return listOf(own)
        val agents = userAgents
        val worked = hostOf(stream.url)?.let { workingAgentByHost[it] }
        return if (worked != null && worked in agents) listOf(worked) + (agents - worked) else agents
    }

    private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

    private fun startAttempts(stream: StreamSource, format: StreamFormat, startPositionMillis: Long, playWhenReady: Boolean) {
        currentAgents = agentsFor(stream)
        attempts = PlaybackRecovery.attempts(stream.url, format, currentAgents.size, xtreamVariants = isLive)
        attemptIndex = 0
        firstFailureHint = null
        prepareAttempt(startPositionMillis, playWhenReady)
    }

    private fun prepareAttempt(startPositionMillis: Long, playWhenReady: Boolean) {
        val stream = currentStream ?: return
        val attempt = attempts.getOrNull(attemptIndex) ?: return
        currentFormat = attempt.format
        hasPlayed = false
        logResponses = true
        bytesLoaded.set(0)
        Log.i(TAG, "start attempt ${attemptIndex + 1}/${attempts.size}: format=${StreamProbe.label(attempt.format)} ua#${attempt.uaIndex} live=$isLive")
        val mediaSource = buildMediaSource(
            stream.copy(url = attempt.url),
            isLive,
            attempt.format,
            currentAgents.getOrElse(attempt.uaIndex) { currentAgents.first() },
        )
        if (!isLive && startPositionMillis > 0) {
            exoPlayer.setMediaSource(mediaSource, startPositionMillis)
        } else {
            exoPlayer.setMediaSource(mediaSource, true)
        }
        applySpeed()
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
        startWatchdog()
    }

    /** First READY / playing of an attempt: remember what worked. */
    private fun onAttemptWorked() {
        if (hasPlayed) return
        hasPlayed = true
        logResponses = false
        val stream = currentStream ?: return
        val attempt = attempts.getOrNull(attemptIndex) ?: return
        Log.i(TAG, "attempt ${attemptIndex + 1} ready: format=${StreamProbe.label(attempt.format)} ua#${attempt.uaIndex} bytes=${bytesLoaded.get()}")
        if (attempt.url == stream.url && attemptIndex > 0) prober.remember(stream.url, attempt.format)
        hostOf(stream.url)?.let { host ->
            currentAgents.getOrNull(attempt.uaIndex)?.let { workingAgentByHost[host] = it }
        }
    }

    /** Moves to the next start-up attempt for [failure]; false when there is none (or playback had started). */
    private fun tryRecover(failure: PlaybackRecovery.Failure, hint: String?): Boolean {
        if (hasPlayed || currentStream == null) return false
        val next = PlaybackRecovery.next(attempts, attemptIndex, failure) ?: return false
        if (firstFailureHint == null) firstFailureHint = hint
        Log.i(TAG, "attempt ${attemptIndex + 1} failed ($failure, $hint) -> attempt ${next + 1}")
        attemptIndex = next
        cancelRetry()
        _state.value = PlaybackState.Buffering
        val position = if (isLive) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)
        prepareAttempt(position, playWhenReady = true)
        return true
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = scope.launch {
            var bufferingMs = 0L
            var elapsedMs = 0L
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                elapsedMs += WATCHDOG_TICK_MS
                val buffering = exoPlayer.playbackState == Player.STATE_BUFFERING && exoPlayer.playWhenReady
                bufferingMs = if (buffering) bufferingMs + WATCHDOG_TICK_MS else 0L
                if (!hasPlayed && elapsedMs in LOG_AT_MS) {
                    Log.i(TAG, "t=${elapsedMs / 1000}s state=${exoPlayer.playbackState} bytes=${bytesLoaded.get()} format=${StreamProbe.label(currentFormat)}")
                }
                if (!hasPlayed) {
                    val failure = when {
                        bufferingMs >= watchdog.noDataMs && bytesLoaded.get() == 0L -> PlaybackRecovery.Failure.NoData
                        bufferingMs >= watchdog.startStallMs -> PlaybackRecovery.Failure.Stall
                        else -> null
                    }
                    if (failure != null) {
                        onStall(failure)
                        return@launch
                    }
                } else if (isLive && bufferingMs >= watchdog.midStreamStallMs) {
                    // Live stream stuck mid-way: reconnect through the bounded retry policy.
                    Log.i(TAG, "live stream stalled for ${bufferingMs / 1000}s: reconnecting")
                    bufferingMs = 0L
                    handleFailure(PlaybackErrorKind.Network, PlaybackErrors.hint("BUFFERING_TIMEOUT", null, StreamProbe.label(currentFormat)))
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun onStall(failure: PlaybackRecovery.Failure) {
        val code = if (failure == PlaybackRecovery.Failure.NoData) "NO_DATA_TIMEOUT" else "BUFFERING_TIMEOUT"
        val hint = PlaybackErrors.hint(code, null, StreamProbe.label(currentFormat))
        if (tryRecover(failure, hint)) return
        Log.i(TAG, "start-up failed for good: ${firstFailureHint ?: hint}")
        failFinal(PlaybackErrorKind.Network, firstFailureHint ?: hint)
    }

    /** Error without automatic retries (the recovery ladder already tried everything). */
    private fun failFinal(kind: PlaybackErrorKind, hint: String?) {
        stopTicker()
        stopWatchdog()
        cancelRetry()
        _state.value = PlaybackState.Error(kind, hint)
        exoPlayer.stop()
        _state.value = PlaybackState.Error(kind, hint)
    }

    /**
     * Sets the playback speed for VOD / catch-up / timeshift (snapped to [PlaybackSpeeds.OPTIONS]).
     * Ignored while a live stream plays: the live edge always runs at normal speed.
     */
    fun setSpeed(speed: Float) {
        if (released) return
        requestedSpeed = PlaybackSpeeds.nearest(speed)
        applySpeed()
    }

    private fun applySpeed() {
        val effective = PlaybackSpeeds.effective(requestedSpeed, isLive)
        if (exoPlayer.playbackParameters.speed != effective) exoPlayer.setPlaybackSpeed(effective)
        _speed.value = effective
    }

    /** Stops playback and clears the media (e.g. when the requested content cannot be played). */
    fun stop() {
        if (released) return
        prepareJob?.cancel()
        prepareJob = null
        stopWatchdog()
        currentStream = null
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

    /** True when the current item supports seeking (VOD, archive; not most live streams). */
    fun isSeekable(): Boolean = !released && exoPlayer.isCurrentMediaItemSeekable

    fun currentPositionMillis(): Long = if (released) 0L else exoPlayer.currentPosition.coerceAtLeast(0L)

    fun durationMillis(): Long {
        if (released) return 0L
        val duration = exoPlayer.duration
        return if (duration == C.TIME_UNSET || duration < 0) 0L else duration
    }

    /**
     * Releases the player. Only ExoPlayer.release() runs here (it hands network teardown to its
     * own loading threads); the shared OkHttpClient is never shut down or evicted on Main.
     */
    fun release() {
        if (released) return
        released = true
        prepareJob?.cancel()
        stopWatchdog()
        scope.cancel()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
        _state.value = PlaybackState.Idle
    }

    // ---------------------------------------------------------------------------------------

    private fun buildMediaSource(stream: StreamSource, isLive: Boolean, format: StreamFormat, userAgent: String): MediaSource {
        val spec = StreamRequests.spec(stream, userAgent)
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(spec.userAgent)
            .setDefaultRequestProperties(spec.headers)
            .setTransferListener(transferListener)
        val itemBuilder = MediaItem.Builder().setUri(stream.url)
        if (isLive) itemBuilder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        return when (format) {
            StreamFormat.Hls -> HlsMediaSource.Factory(dataSourceFactory)
                .setExtractorFactory(DefaultHlsExtractorFactory(TS_FLAGS, true))
                .createMediaSource(itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8).build())
            StreamFormat.Dash -> DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD).build())
            else -> {
                val extractorsFactory = DefaultExtractorsFactory()
                    .setTsExtractorFlags(TS_FLAGS)
                    .setTsExtractorTimestampSearchBytes(TS_TIMESTAMP_SEARCH_BYTES)
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                    .createMediaSource(itemBuilder.build())
            }
        }
    }

    private fun onError(error: PlaybackException) {
        if (PlaybackErrors.isBehindLiveWindow(error.errorCode) && behindLiveRejoins < MAX_BEHIND_LIVE_REJOINS) {
            behindLiveRejoins++
            exoPlayer.seekToDefaultPosition()
            exoPlayer.prepare()
            return
        }
        val status = httpStatus(error)
        val hint = PlaybackErrors.hint(error.errorCodeName, status, StreamProbe.label(currentFormat))
        val failure = when {
            StreamProbe.isFormatError(error.errorCode) -> PlaybackRecovery.Failure.Format
            status == 401 || status == 403 -> PlaybackRecovery.Failure.Forbidden
            else -> PlaybackRecovery.Failure.Other
        }
        if (tryRecover(failure, hint)) return
        val kind = PlaybackErrors.classify(error.errorCode)
        if (!hasPlayed && attemptIndex > 0) {
            // Every start-up attempt failed: report the original URL's problem.
            failFinal(kind, firstFailureHint ?: hint)
            return
        }
        handleFailure(kind, hint)
    }

    /** HTTP status from the cause chain (bad-status errors), else null. */
    private fun httpStatus(error: Throwable): Int? {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
            depth++
        }
        return null
    }

    private fun handleFailure(kind: PlaybackErrorKind, message: String?) {
        stopTicker()
        cancelRetry()
        val delayMs = retryPolicy.onFailure(kind, isLive)
        if (delayMs == null) {
            retryingAttempt = null
            // Message is an error-code hint only: stream URLs (which may hold credentials) are never exposed.
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
        const val TAG = "NovaPlayer"
        const val WATCHDOG_TICK_MS = 1_000L
        val LOG_AT_MS = setOf(2_000L, 5_000L, 10_000L, 15_000L)

        /** Process-wide: the agent that last worked per host (Auto mode skips known-bad agents). */
        val workingAgentByHost = ConcurrentHashMap<String, String>()

        // IPTV MPEG-TS often lacks access-unit delimiters, starts mid-GOP and may carry DTS audio.
        const val TS_FLAGS = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
        // Search further for PCR/PTS when reading durations of TS files (default 600 packets).
        const val TS_TIMESTAMP_SEARCH_BYTES = 1_500 * 188
        const val MAX_BEHIND_LIVE_REJOINS = 3
        // Modest buffers for low-RAM TV boxes; start quickly, rebuffer a bit more conservatively.
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 30_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_000
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000
        const val TICK_MS = 1_000L
    }
}
