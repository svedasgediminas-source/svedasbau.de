package app.novaiptv.feature.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.Clock
import app.novaiptv.core.domain.CatchupResolver
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.domain.StreamResolver
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.core.model.AspectRatioMode
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.PlaybackState
import app.novaiptv.core.model.TrackOption
import app.novaiptv.core.model.WatchHistory
import app.novaiptv.core.player.LiveTimeshift
import app.novaiptv.core.player.NovaPlayer
import app.novaiptv.core.player.SeekAccelerator
import app.novaiptv.core.player.StreamUserAgents
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.player.ResumePolicy
import app.novaiptv.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Repositories the player needs; [from] picks them out of the app container. */
class PlayerDependencies(
    val channels: ChannelRepository,
    val epg: EpgRepository,
    val vod: VodRepository,
    val history: HistoryRepository,
    val preferences: PreferencesRepository,
    val streamResolver: StreamResolver,
    val catchup: CatchupResolver,
    val clock: Clock,
    /** Shared app client: owned by the app container, never shut down by the player. */
    val okHttpClient: OkHttpClient,
) {
    companion object {
        fun from(container: AppContainer) = PlayerDependencies(
            channels = container.channels,
            epg = container.epg,
            vod = container.vod,
            history = container.history,
            preferences = container.preferences,
            streamResolver = container.streamResolver,
            catchup = container.catchup,
            clock = container.clock,
            okHttpClient = container.okHttpClient,
        )
    }
}

enum class PlayerContentError { NotFound, CatchupUnavailable, StreamFailed }

sealed interface PlayerNotice {
    data class ChannelNotFound(val number: Int) : PlayerNotice
    data object NoMoreChannels : PlayerNotice
    /** Rewinding live TV failed: no guide data for that moment or the archive did not answer. */
    data object TimeshiftUnavailable : PlayerNotice
}

/** Pending accelerated seek shown while a seek key is held (committed on release). */
data class SeekPreview(
    /** Media position (VOD / catch-up) or wall-clock time (live / timeshift, [wallClock] = true). */
    val targetMillis: Long,
    val deltaMillis: Long,
    val wallClock: Boolean,
    /** Wall-clock target reaches the live edge: committing returns to live. */
    val toLive: Boolean = false,
)

data class PlayerUiState(
    val loading: Boolean = true,
    val title: String = "",
    val subtitle: String? = null,
    /** Playing the live edge of a channel. */
    val isLive: Boolean = false,
    val isCatchup: Boolean = false,
    /** Live channel rewound through the catch-up archive ("behind live"); [currentProgram] is the archived one. */
    val isTimeshift: Boolean = false,
    /** The channel declares catch-up, so live TV can be rewound / restarted. */
    val canTimeshift: Boolean = false,
    /** Wall-clock time when live playback was paused (catch-up channels resume from the archive). */
    val pausedLiveAtMillis: Long? = null,
    val seekPreview: SeekPreview? = null,
    val channelId: String? = null,
    val channelNumber: Int? = null,
    val logoUrl: String? = null,
    /** Live: programme on air now. Catch-up: the archived programme. */
    val currentProgram: EpgProgram? = null,
    val nextProgram: EpgProgram? = null,
    val aspectRatio: AspectRatioMode = AspectRatioMode.Fit,
    val showChannelNumbers: Boolean = true,
    /** Digits typed on the remote, waiting for the 2 s timeout. */
    val numberEntry: String? = null,
    /** Set when VOD playback resumed from history (offers "Start over"). */
    val resumedFromMillis: Long? = null,
    val contentError: PlayerContentError? = null,
    val notice: PlayerNotice? = null,
) {
    /** Watching a channel (live edge or timeshifted): channel up/down and number entry apply. */
    val isChannel: Boolean get() = isLive || isTimeshift

    /** Speed can be changed (VOD, catch-up, timeshift; never the live edge). */
    val speedAdjustable: Boolean get() = !isLive && !loading && contentError == null
}

/**
 * Drives full-screen playback for all content types.
 *
 * OkHttp: uses the app's shared client ([PlayerDependencies.okHttpClient]). Nothing network
 * related is closed in [onCleared]: evicting/closing TLS sockets on the main thread throws
 * NetworkOnMainThreadException, and the shared client outlives the player anyway.
 *
 * Live timeshift: on channels whose source declares catch-up, LEFT / REW / a longer pause switch
 * to the archive of the programme at the chosen moment ([Current.Timeshift]); returning to the
 * live edge (RIGHT past it, "Back to live", archive end) plays the live stream again.
 */
class PlayerViewModel(
    private val args: PlayerArgs,
    private val deps: PlayerDependencies,
    appContext: Context,
) : ViewModel() {

    val novaPlayer = NovaPlayer(appContext.applicationContext, deps.okHttpClient)

    val playbackState: StateFlow<PlaybackState> = novaPlayer.state
    val audioTracks: StateFlow<List<TrackOption>> = novaPlayer.audioTracks
    val subtitleTracks: StateFlow<List<TrackOption>> = novaPlayer.subtitleTracks
    val speed: StateFlow<Float> = novaPlayer.speed

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private sealed interface Current {
        data class Live(val channel: Channel) : Current
        data class Catchup(val channel: Channel, val program: EpgProgram) : Current
        /** Live channel rewound into the archive of [program]; [programs] = guide around it. */
        data class Timeshift(val channel: Channel, val program: EpgProgram, val programs: List<EpgProgram>) : Current
        data class Vod(
            val type: ContentType,
            val id: String,
            val playlistId: Long,
            val title: String,
            val imageUrl: String?,
            val parentId: String?,
        ) : Current
    }

    private var current: Current? = null
    private val channelGroup: String? = (args as? PlayerArgs.Channel)?.group
    private var loadJob: Job? = null
    private var epgJob: Job? = null
    private var numberJob: Job? = null
    private var noticeJob: Job? = null
    private var resumeOnStart = false
    private var seekCommitJob: Job? = null
    private val seekAccelerator = SeekAccelerator()
    /** Guide of the live channel around now (kept by the EPG loop, used to pick timeshift programmes). */
    private var livePrograms: List<EpgProgram> = emptyList()

    init {
        viewModelScope.launch {
            deps.preferences.preferences.collect { prefs ->
                _uiState.update {
                    it.copy(aspectRatio = prefs.aspectRatio, showChannelNumbers = prefs.showChannelNumbers)
                }
            }
        }
        viewModelScope.launch {
            deps.preferences.preferences
                .map { Triple(it.preferredAudioLanguage, it.preferredSubtitleLanguage, it.subtitlesEnabled) }
                .distinctUntilChanged()
                .collect { (audio, subtitle, enabled) -> novaPlayer.setTrackPreferences(audio, subtitle, enabled) }
        }
        load(args)
        viewModelScope.launch {
            playbackState.collect { onPlaybackState(it) }
        }
        viewModelScope.launch {
            // Periodic progress save for VOD (channels are saved on start/exit only).
            while (isActive) {
                delay(HISTORY_INTERVAL_MS)
                if (current is Current.Vod && playbackState.value is PlaybackState.Playing) saveHistory()
            }
        }
    }

    // ---- Loading -------------------------------------------------------------------------

    private fun load(target: PlayerArgs) {
        cancelSeek()
        loadJob?.cancel()
        epgJob?.cancel()
        _uiState.update { it.copy(loading = true, contentError = null, resumedFromMillis = null) }
        loadJob = viewModelScope.launch {
            guarded {
                when (target) {
                    is PlayerArgs.Channel -> {
                        val channel = deps.channels.getChannel(target.channelId)
                        if (channel == null) fail(PlayerContentError.NotFound) else startLive(channel)
                    }
                    is PlayerArgs.Catchup -> loadCatchup(target)
                    is PlayerArgs.Movie -> loadMovie(target.movieId)
                    is PlayerArgs.Episode -> loadEpisode(target.episodeId)
                }
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(PlayerContentError.StreamFailed)
        }
    }

    private suspend fun startLive(channel: Channel) {
        current = Current.Live(channel)
        _uiState.update {
            it.copy(
                loading = false,
                title = channel.name,
                subtitle = null,
                isLive = true,
                isCatchup = false,
                isTimeshift = false,
                canTimeshift = LiveTimeshift.canTimeshift(channel.catchup),
                pausedLiveAtMillis = null,
                seekPreview = null,
                channelId = channel.id,
                channelNumber = channel.number,
                logoUrl = channel.logoUrl,
                currentProgram = null,
                nextProgram = null,
                contentError = null,
            )
        }
        val stream = deps.streamResolver.resolve(channel.playlistId, channel.stream)
        playStream(stream, 0, isLive = true)
        saveHistory()
        startEpg(channel)
    }

    private fun switchToChannel(channel: Channel) {
        cancelSeek()
        saveHistory()
        loadJob?.cancel()
        epgJob?.cancel()
        loadJob = viewModelScope.launch { guarded { startLive(channel) } }
    }

    private fun startEpg(channel: Channel) {
        epgJob?.cancel()
        val epgId = channel.epgId ?: return
        epgJob = viewModelScope.launch {
            while (isActive) {
                val now = deps.clock.nowMillis()
                val programs = try {
                    deps.epg.programs(epgId, now - EPG_WINDOW_MS, now + EPG_WINDOW_MS).sortedBy { it.startMillis }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                val nowProgram = programs.firstOrNull { it.isLive(now) }
                val next = programs.firstOrNull { it.startMillis >= (nowProgram?.endMillis ?: now) }
                livePrograms = programs
                // While timeshifted the overlay shows the archived programme instead.
                if (current is Current.Live) _uiState.update { it.copy(currentProgram = nowProgram, nextProgram = next) }
                val untilChange = (nowProgram?.endMillis ?: (now + EPG_REFRESH_MS)) - now
                delay(untilChange.coerceIn(5_000L, EPG_REFRESH_MS))
            }
        }
    }

    private suspend fun loadCatchup(target: PlayerArgs.Catchup) {
        val channel = deps.channels.getChannel(target.channelId) ?: return fail(PlayerContentError.NotFound)
        val epgId = channel.epgId
        val program = epgId?.let { id ->
            val programs = deps.epg.programs(id, target.startMillis, target.endMillis)
            programs.firstOrNull { it.startMillis == target.startMillis }
                ?: programs.firstOrNull { it.startMillis < target.endMillis && it.endMillis > target.startMillis }
        } ?: EpgProgram(
            channelEpgId = epgId ?: channel.id,
            startMillis = target.startMillis,
            endMillis = target.endMillis,
            title = channel.name,
        )
        val archived = deps.catchup.resolve(channel, program, deps.clock.nowMillis())
            ?: return fail(PlayerContentError.CatchupUnavailable)
        current = Current.Catchup(channel, program)
        _uiState.update {
            it.copy(
                loading = false,
                title = channel.name,
                subtitle = program.title,
                isLive = false,
                isCatchup = true,
                isTimeshift = false,
                canTimeshift = false,
                pausedLiveAtMillis = null,
                channelId = channel.id,
                channelNumber = channel.number,
                logoUrl = channel.logoUrl,
                currentProgram = program,
                nextProgram = null,
            )
        }
        val stream = deps.streamResolver.resolve(channel.playlistId, archived)
        playStream(stream, 0, isLive = false)
    }

    private suspend fun loadMovie(movieId: String) {
        val movie = deps.vod.getMovie(movieId) ?: return fail(PlayerContentError.NotFound)
        val fallbackDuration = (movie.durationSeconds ?: 0) * 1000L
        val start = resumePosition(ContentType.Movie, movie.id, fallbackDuration)
        current = Current.Vod(ContentType.Movie, movie.id, movie.playlistId, movie.name, movie.posterUrl, null)
        _uiState.update {
            it.copy(
                loading = false,
                title = movie.name,
                subtitle = movie.year?.toString(),
                isLive = false,
                isCatchup = false,
                isTimeshift = false,
                canTimeshift = false,
                pausedLiveAtMillis = null,
                channelId = null,
                channelNumber = null,
                logoUrl = null,
                currentProgram = null,
                nextProgram = null,
                resumedFromMillis = start.takeIf { s -> s > 0 },
            )
        }
        val stream = deps.streamResolver.resolve(movie.playlistId, movie.stream)
        playStream(stream, start, isLive = false)
    }

    private suspend fun loadEpisode(episodeId: String) {
        val episode = deps.vod.getEpisode(episodeId) ?: return fail(PlayerContentError.NotFound)
        val series = deps.vod.getSeries(episode.seriesId)
        val playlistId = series?.playlistId
            ?: episode.seriesId.substringBefore(':').toLongOrNull()
            ?: episode.id.substringBefore(':').toLongOrNull()
            ?: return fail(PlayerContentError.NotFound)
        val code = "S${episode.seasonNumber}E${episode.episodeNumber}"
        val seriesName = series?.name
        val historyTitle = if (seriesName != null) "$seriesName · $code" else "$code · ${episode.title}"
        val fallbackDuration = (episode.durationSeconds ?: 0) * 1000L
        val start = resumePosition(ContentType.Episode, episode.id, fallbackDuration)
        current = Current.Vod(
            type = ContentType.Episode,
            id = episode.id,
            playlistId = playlistId,
            title = historyTitle,
            imageUrl = episode.thumbnailUrl ?: series?.posterUrl,
            parentId = episode.seriesId,
        )
        _uiState.update {
            it.copy(
                loading = false,
                title = seriesName ?: episode.title,
                subtitle = if (seriesName != null) "$code · ${episode.title}" else code,
                isLive = false,
                isCatchup = false,
                isTimeshift = false,
                canTimeshift = false,
                pausedLiveAtMillis = null,
                channelId = null,
                channelNumber = null,
                logoUrl = null,
                currentProgram = null,
                nextProgram = null,
                resumedFromMillis = start.takeIf { s -> s > 0 },
            )
        }
        val stream = deps.streamResolver.resolve(playlistId, episode.stream)
        playStream(stream, start, isLive = false)
    }

    /**
     * Applies the "Stream User-Agent" setting (read fresh, so a change in Settings counts on the
     * next start) and plays. Streams that declare their own agent keep it (see NovaPlayer).
     */
    private suspend fun playStream(stream: StreamSource, startPositionMillis: Long, isLive: Boolean) {
        val prefs = try {
            deps.preferences.preferences.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (prefs != null) {
            novaPlayer.setUserAgentCandidates(StreamUserAgents.candidates(prefs.streamUserAgent, prefs.customUserAgent))
        }
        novaPlayer.play(stream, startPositionMillis, isLive = isLive)
    }

    private suspend fun resumePosition(type: ContentType, id: String, fallbackDurationMillis: Long): Long {
        val entry = try {
            deps.history.get(type, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return 0L
        val duration = if (entry.durationMillis > 0) entry.durationMillis else fallbackDurationMillis
        return ResumePolicy.resumePositionMillis(entry.positionMillis, duration)
    }

    private fun fail(error: PlayerContentError) {
        novaPlayer.stop()
        _uiState.update { it.copy(loading = false, contentError = error) }
    }

    // ---- User actions --------------------------------------------------------------------

    /** Channel being watched (live edge or timeshifted), else null. */
    private fun watchedChannel(c: Current? = current): Channel? = when (c) {
        is Current.Live -> c.channel
        is Current.Timeshift -> c.channel
        else -> null
    }

    fun togglePlayPause() {
        val live = current as? Current.Live
        if (live == null || !LiveTimeshift.canTimeshift(live.channel.catchup)) {
            novaPlayer.togglePlayPause()
            return
        }
        when {
            _uiState.value.pausedLiveAtMillis != null -> play()
            novaPlayer.player.playWhenReady -> pause()
            else -> novaPlayer.resume()
        }
    }

    /**
     * Resumes playback. A catch-up channel paused on live for longer than
     * [LiveTimeshift.RESUME_FROM_ARCHIVE_AFTER_MS] continues from the archive at the paused moment.
     */
    fun play() {
        val live = current as? Current.Live
        val pausedAt = _uiState.value.pausedLiveAtMillis
        if (live != null && pausedAt != null) {
            _uiState.update { it.copy(pausedLiveAtMillis = null) }
            if (LiveTimeshift.shouldResumeFromArchive(pausedAt, deps.clock.nowMillis())) {
                startTimeshift(live.channel, pausedAt)
                return
            }
        }
        novaPlayer.resume()
    }

    /** Pauses. On a catch-up channel's live stream the paused moment is remembered (see [play]). */
    fun pause() {
        val live = current as? Current.Live
        if (live != null && LiveTimeshift.canTimeshift(live.channel.catchup) && _uiState.value.pausedLiveAtMillis == null) {
            _uiState.update { it.copy(pausedLiveAtMillis = deps.clock.nowMillis()) }
        }
        novaPlayer.pause()
    }

    fun setSpeed(speed: Float) {
        if (_uiState.value.speedAdjustable) novaPlayer.setSpeed(speed)
    }

    fun startOver() {
        when (current) {
            is Current.Timeshift -> restartProgramme()
            is Current.Live -> restartProgramme()
            else -> {
                novaPlayer.seekTo(0)
                novaPlayer.resume()
                _uiState.update { it.copy(resumedFromMillis = null) }
            }
        }
    }

    /** Live/timeshift on a catch-up channel: play the current programme from its beginning. */
    fun restartProgramme() {
        val channel = watchedChannel() ?: return
        if (!LiveTimeshift.canTimeshift(channel.catchup)) return
        val program = when (val c = current) {
            is Current.Timeshift -> c.program
            else -> _uiState.value.currentProgram
        } ?: return showNotice(PlayerNotice.TimeshiftUnavailable)
        startTimeshift(channel, program.startMillis)
    }

    /** Leaves timeshift (or a paused live stream) and plays the live edge again. */
    fun backToLive() {
        cancelSeek()
        when (val c = current) {
            is Current.Timeshift -> switchToChannel(c.channel)
            is Current.Live -> if (_uiState.value.pausedLiveAtMillis != null) {
                _uiState.update { it.copy(pausedLiveAtMillis = null) }
                novaPlayer.retry()
            }
            else -> Unit
        }
    }

    /** Kept for callers that seek by a fixed amount (VOD / catch-up only). */
    fun seekBy(deltaMillis: Long) {
        val c = current
        if (c is Current.Vod || c is Current.Catchup) novaPlayer.seekBy(deltaMillis)
    }

    // ---- Accelerated seeking -------------------------------------------------------------

    val isSeeking: Boolean get() = seekAccelerator.isActive

    /**
     * LEFT/RIGHT ([media] = false) or REW/FF ([media] = true) key-down. Returns false when the key
     * does nothing here (live edge forward, channels without catch-up), so the screen can fall back.
     */
    fun onSeekKey(direction: Int, media: Boolean, repeatCount: Int, eventTimeMillis: Long): Boolean {
        val kind = if (media) SeekAccelerator.Kind.Media else SeekAccelerator.Kind.Dpad
        val now = deps.clock.nowMillis()
        val target: Long
        val wallClock: Boolean
        when (val c = current) {
            is Current.Vod, is Current.Catchup -> {
                if (!seekAccelerator.isActive && !novaPlayer.isSeekable()) return true
                val duration = novaPlayer.durationMillis()
                target = seekAccelerator.press(
                    direction, kind, repeatCount, eventTimeMillis,
                    positionMillis = novaPlayer.currentPositionMillis(),
                    minMillis = 0L,
                    maxMillis = if (duration > 0) duration else Long.MAX_VALUE,
                )
                wallClock = false
            }
            is Current.Live -> {
                val earliest = LiveTimeshift.earliestMillis(c.channel.catchup, now) ?: return false
                if (!seekAccelerator.isActive && direction > 0) return false
                target = seekAccelerator.press(
                    direction, kind, repeatCount, eventTimeMillis,
                    positionMillis = _uiState.value.pausedLiveAtMillis ?: now,
                    minMillis = earliest,
                    maxMillis = now,
                )
                wallClock = true
            }
            is Current.Timeshift -> {
                val earliest = LiveTimeshift.earliestMillis(c.channel.catchup, now) ?: return false
                target = seekAccelerator.press(
                    direction, kind, repeatCount, eventTimeMillis,
                    positionMillis = LiveTimeshift.wallClockAt(c.program.startMillis, novaPlayer.currentPositionMillis()),
                    minMillis = earliest,
                    maxMillis = now,
                )
                wallClock = true
            }
            null -> return false
        }
        val preview = SeekPreview(
            targetMillis = target,
            deltaMillis = seekAccelerator.deltaMillis,
            wallClock = wallClock,
            toLive = wallClock && LiveTimeshift.isAtLiveEdge(target, now),
        )
        _uiState.update { it.copy(seekPreview = preview) }
        scheduleSeekCommit(SeekAccelerator.COMMIT_WHILE_HELD_MS)
        return true
    }

    /** Seek key released: commit shortly (rapid taps keep accumulating). */
    fun onSeekKeyUp() {
        if (seekAccelerator.isActive) scheduleSeekCommit(SeekAccelerator.COMMIT_AFTER_RELEASE_MS)
    }

    private fun scheduleSeekCommit(delayMillis: Long) {
        seekCommitJob?.cancel()
        seekCommitJob = viewModelScope.launch {
            delay(delayMillis)
            commitSeek()
        }
    }

    private fun cancelSeek() {
        seekCommitJob?.cancel()
        seekCommitJob = null
        seekAccelerator.cancel()
        if (_uiState.value.seekPreview != null) _uiState.update { it.copy(seekPreview = null) }
    }

    private fun commitSeek() {
        val target = seekAccelerator.commit()
        _uiState.update { it.copy(seekPreview = null) }
        if (target == null) return
        val now = deps.clock.nowMillis()
        when (val c = current) {
            is Current.Vod, is Current.Catchup -> novaPlayer.seekTo(target)
            is Current.Live -> if (!LiveTimeshift.isAtLiveEdge(target, now)) {
                _uiState.update { it.copy(pausedLiveAtMillis = null) }
                startTimeshift(c.channel, target)
            }
            is Current.Timeshift -> when {
                LiveTimeshift.isAtLiveEdge(target, now) -> switchToChannel(c.channel)
                target >= c.program.startMillis && target < c.program.endMillis -> {
                    novaPlayer.seekTo(LiveTimeshift.startPositionMillis(target, c.program.startMillis))
                    novaPlayer.resume()
                }
                else -> startTimeshift(c.channel, target)
            }
            null -> Unit
        }
    }

    // ---- Live timeshift ------------------------------------------------------------------

    /**
     * Plays [channel]'s archive from the wall-clock moment [targetWallMillis] (clamped into the
     * declared archive window). Falls back to (or stays on) live with a notice when no programme
     * covers that moment or the archive cannot be resolved. Never used for channels without catch-up.
     */
    private fun startTimeshift(channel: Channel, targetWallMillis: Long) {
        if (channel.catchup !is CatchupCapability.Supported) return
        cancelSeek()
        val before = current
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ok = try {
                tryStartTimeshift(channel, targetWallMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                showNotice(PlayerNotice.TimeshiftUnavailable)
                // A paused live stream must not stay frozen: rejoin the live edge.
                if (before is Current.Live && !novaPlayer.player.playWhenReady) novaPlayer.retry()
            }
        }
    }

    private suspend fun tryStartTimeshift(channel: Channel, targetWallMillis: Long): Boolean {
        val now = deps.clock.nowMillis()
        if (LiveTimeshift.isAtLiveEdge(targetWallMillis, now)) {
            if (current !is Current.Live) startLive(channel)
            return true
        }
        val earliest = LiveTimeshift.earliestMillis(channel.catchup, now) ?: return false
        val target = targetWallMillis.coerceAtLeast(earliest)
        val epgId = channel.epgId ?: return false
        val programs = livePrograms.takeIf { list -> LiveTimeshift.programAt(list, target) != null }
            ?: deps.epg.programs(epgId, target - TIMESHIFT_EPG_WINDOW_MS, target + TIMESHIFT_EPG_WINDOW_MS)
                .sortedBy { it.startMillis }
        val program = LiveTimeshift.programAt(programs, target) ?: return false
        val archived = deps.catchup.resolve(channel, program, now) ?: return false
        val stream = deps.streamResolver.resolve(channel.playlistId, archived)
        current = Current.Timeshift(channel, program, programs)
        _uiState.update {
            it.copy(
                loading = false,
                title = channel.name,
                subtitle = null,
                isLive = false,
                isCatchup = false,
                isTimeshift = true,
                canTimeshift = true,
                pausedLiveAtMillis = null,
                channelId = channel.id,
                channelNumber = channel.number,
                logoUrl = channel.logoUrl,
                currentProgram = program,
                nextProgram = LiveTimeshift.nextProgram(programs, program),
                contentError = null,
                resumedFromMillis = null,
            )
        }
        playStream(stream, LiveTimeshift.startPositionMillis(target, program.startMillis), isLive = false)
        if (epgJob?.isActive != true) startEpg(channel)
        return true
    }

    private fun onPlaybackState(state: PlaybackState) {
        val shift = current as? Current.Timeshift ?: return
        when (state) {
            // Archive finished (programme over, or playback caught up with live).
            PlaybackState.Ended -> when (val next = LiveTimeshift.onArchiveEnded(shift.program, shift.programs, deps.clock.nowMillis())) {
                is LiveTimeshift.OnEnded.ContinueWith -> startTimeshift(shift.channel, next.program.startMillis)
                LiveTimeshift.OnEnded.BackToLive -> switchToChannel(shift.channel)
            }
            // The archive stream failed for good: go back to live instead of an error screen.
            is PlaybackState.Error -> {
                showNotice(PlayerNotice.TimeshiftUnavailable)
                switchToChannel(shift.channel)
            }
            else -> Unit
        }
    }

    fun retry() {
        val state = _uiState.value
        val channel = watchedChannel()
        when {
            // Re-resolve live streams: Xtream tokens/credentials may have changed.
            channel != null -> switchToChannel(channel)
            state.contentError != null || current == null -> load(args)
            else -> novaPlayer.retry()
        }
    }

    /** [offset] +1 = next channel, -1 = previous, within the list the user came from. */
    fun switchChannel(offset: Int) {
        val from = current
        val channel = watchedChannel(from) ?: return
        viewModelScope.launch {
            val next = try {
                deps.channels.adjacentChannel(channel, channelGroup, offset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (next == null || next.id == channel.id) {
                showNotice(PlayerNotice.NoMoreChannels)
            } else if (current == from) {
                switchToChannel(next)
            }
        }
    }

    fun onDigit(digit: Int) {
        if (watchedChannel() == null || digit !in 0..9) return
        val entry = ((_uiState.value.numberEntry ?: "") + digit).takeLast(MAX_DIGITS)
        _uiState.update { it.copy(numberEntry = entry) }
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(NUMBER_ENTRY_TIMEOUT_MS)
            commitNumberEntryNow()
        }
    }

    /** OK pressed during number entry: jump immediately. */
    fun commitNumberEntry() {
        numberJob?.cancel()
        numberJob = viewModelScope.launch { commitNumberEntryNow() }
    }

    fun cancelNumberEntry() {
        numberJob?.cancel()
        _uiState.update { it.copy(numberEntry = null) }
    }

    private suspend fun commitNumberEntryNow() {
        val number = _uiState.value.numberEntry?.toIntOrNull()
        _uiState.update { it.copy(numberEntry = null) }
        val channel = watchedChannel() ?: return
        if (number == null) return
        if (number == channel.number && current is Current.Live) return
        val target = if (number == channel.number) {
            channel
        } else {
            try {
                deps.channels.findByNumber(channel.playlistId, number)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (target == null) showNotice(PlayerNotice.ChannelNotFound(number)) else switchToChannel(target)
    }

    fun selectAudio(option: TrackOption) {
        novaPlayer.selectAudio(option.id)
        val language = option.language ?: return
        viewModelScope.launch { deps.preferences.update { it.copy(preferredAudioLanguage = language) } }
    }

    /** null = subtitles off. Remembered as the default for future playback. */
    fun selectSubtitle(option: TrackOption?) {
        novaPlayer.selectSubtitle(option?.id)
        viewModelScope.launch {
            deps.preferences.update {
                it.copy(
                    subtitlesEnabled = option != null,
                    preferredSubtitleLanguage = option?.language ?: it.preferredSubtitleLanguage,
                )
            }
        }
    }

    fun setAspectRatio(mode: AspectRatioMode) {
        _uiState.update { it.copy(aspectRatio = mode) }
        viewModelScope.launch { deps.preferences.setAspectRatio(mode) }
    }

    private fun showNotice(notice: PlayerNotice) {
        _uiState.update { it.copy(notice = notice) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _uiState.update { it.copy(notice = null) }
        }
    }

    // ---- Lifecycle -----------------------------------------------------------------------

    fun onStop() {
        cancelSeek()
        saveHistory()
        resumeOnStart = novaPlayer.player.playWhenReady
        novaPlayer.pause()
    }

    fun onStart() {
        if (!resumeOnStart) return
        resumeOnStart = false
        // Live: rejoin the live edge instead of playing stale buffer.
        if (current is Current.Live) novaPlayer.retry() else novaPlayer.resume()
        // (Timeshift resumes the archive where it was: the user chose to be behind live.)
    }

    override fun onCleared() {
        saveHistory()
        // Only the player is released. The OkHttpClient is the app's shared client: shutting it
        // down or evicting its pool here would close sockets on Main (NetworkOnMainThreadException).
        novaPlayer.release()
    }

    /** Snapshot taken synchronously (player access must stay on main), persisted off-scope. */
    private fun saveHistory() {
        val entry = historyEntry() ?: return
        PersistScope.launch {
            try {
                deps.history.save(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // History is best effort.
            }
        }
    }

    private fun historyEntry(): WatchHistory? {
        val now = deps.clock.nowMillis()
        val channel = watchedChannel()
        return when (val c = current) {
            is Current.Live, is Current.Timeshift -> WatchHistory(
                contentType = ContentType.Channel,
                contentId = channel!!.id,
                playlistId = channel.playlistId,
                title = channel.name,
                imageUrl = channel.logoUrl,
                positionMillis = 0,
                durationMillis = 0,
                lastWatchedAtMillis = now,
            )
            is Current.Vod -> {
                val position = novaPlayer.currentPositionMillis()
                val duration = novaPlayer.durationMillis()
                if (position <= 0 || duration <= 0) {
                    null
                } else {
                    WatchHistory(
                        contentType = c.type,
                        contentId = c.id,
                        playlistId = c.playlistId,
                        title = c.title,
                        imageUrl = c.imageUrl,
                        positionMillis = position,
                        durationMillis = duration,
                        lastWatchedAtMillis = now,
                        parentId = c.parentId,
                    )
                }
            }
            is Current.Catchup, null -> null
        }
    }

    private companion object {
        const val HISTORY_INTERVAL_MS = 10_000L
        const val NUMBER_ENTRY_TIMEOUT_MS = 2_000L
        const val MAX_DIGITS = 5
        const val NOTICE_MS = 3_000L
        const val EPG_WINDOW_MS = 12 * 60 * 60 * 1000L
        const val EPG_REFRESH_MS = 60_000L
        const val TIMESHIFT_EPG_WINDOW_MS = 12 * 60 * 60 * 1000L

        /** Outlives the ViewModel so the final history write in onCleared completes. */
        val PersistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
