package app.novaiptv.feature.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.Clock
import app.novaiptv.core.common.net.HttpClients
import app.novaiptv.core.domain.CatchupResolver
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.domain.StreamResolver
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.core.model.AspectRatioMode
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.PlaybackState
import app.novaiptv.core.model.TrackOption
import app.novaiptv.core.model.WatchHistory
import app.novaiptv.core.player.NovaPlayer
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
        )
    }
}

enum class PlayerContentError { NotFound, CatchupUnavailable, StreamFailed }

sealed interface PlayerNotice {
    data class ChannelNotFound(val number: Int) : PlayerNotice
    data object NoMoreChannels : PlayerNotice
}

data class PlayerUiState(
    val loading: Boolean = true,
    val title: String = "",
    val subtitle: String? = null,
    val isLive: Boolean = false,
    val isCatchup: Boolean = false,
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
)

/**
 * Drives full-screen playback for all content types.
 *
 * OkHttp: [AppContainer] does not expose a shared OkHttpClient yet, so by default this ViewModel
 * creates its own via [HttpClients.create] and shuts it down in [onCleared]. When a shared
 * client becomes available, pass it as [okHttpClient] with [ownsHttpClient] = false.
 */
class PlayerViewModel(
    private val args: PlayerArgs,
    private val deps: PlayerDependencies,
    appContext: Context,
    private val okHttpClient: OkHttpClient = HttpClients.create(),
    private val ownsHttpClient: Boolean = true,
) : ViewModel() {

    val novaPlayer = NovaPlayer(appContext.applicationContext, okHttpClient)

    val playbackState: StateFlow<PlaybackState> = novaPlayer.state
    val audioTracks: StateFlow<List<TrackOption>> = novaPlayer.audioTracks
    val subtitleTracks: StateFlow<List<TrackOption>> = novaPlayer.subtitleTracks

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private sealed interface Current {
        data class Live(val channel: Channel) : Current
        data class Catchup(val channel: Channel, val program: EpgProgram) : Current
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
            // Periodic progress save for VOD (channels are saved on start/exit only).
            while (isActive) {
                delay(HISTORY_INTERVAL_MS)
                if (current is Current.Vod && playbackState.value is PlaybackState.Playing) saveHistory()
            }
        }
    }

    // ---- Loading -------------------------------------------------------------------------

    private fun load(target: PlayerArgs) {
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
                channelId = channel.id,
                channelNumber = channel.number,
                logoUrl = channel.logoUrl,
                currentProgram = null,
                nextProgram = null,
                contentError = null,
            )
        }
        val stream = deps.streamResolver.resolve(channel.playlistId, channel.stream)
        novaPlayer.play(stream, 0, isLive = true)
        saveHistory()
        startEpg(channel)
    }

    private fun switchToChannel(channel: Channel) {
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
                _uiState.update { it.copy(currentProgram = nowProgram, nextProgram = next) }
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
                channelId = channel.id,
                channelNumber = channel.number,
                logoUrl = channel.logoUrl,
                currentProgram = program,
                nextProgram = null,
            )
        }
        val stream = deps.streamResolver.resolve(channel.playlistId, archived)
        novaPlayer.play(stream, 0, isLive = false)
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
                channelId = null,
                channelNumber = null,
                logoUrl = null,
                currentProgram = null,
                nextProgram = null,
                resumedFromMillis = start.takeIf { s -> s > 0 },
            )
        }
        val stream = deps.streamResolver.resolve(movie.playlistId, movie.stream)
        novaPlayer.play(stream, start, isLive = false)
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
                channelId = null,
                channelNumber = null,
                logoUrl = null,
                currentProgram = null,
                nextProgram = null,
                resumedFromMillis = start.takeIf { s -> s > 0 },
            )
        }
        val stream = deps.streamResolver.resolve(playlistId, episode.stream)
        novaPlayer.play(stream, start, isLive = false)
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

    fun togglePlayPause() = novaPlayer.togglePlayPause()

    fun play() = novaPlayer.resume()

    fun pause() = novaPlayer.pause()

    fun seekBy(deltaMillis: Long) {
        if (!_uiState.value.isLive) novaPlayer.seekBy(deltaMillis)
    }

    fun startOver() {
        novaPlayer.seekTo(0)
        novaPlayer.resume()
        _uiState.update { it.copy(resumedFromMillis = null) }
    }

    fun retry() {
        val state = _uiState.value
        val live = current as? Current.Live
        when {
            // Re-resolve live streams: Xtream tokens/credentials may have changed.
            live != null -> switchToChannel(live.channel)
            state.contentError != null || current == null -> load(args)
            else -> novaPlayer.retry()
        }
    }

    /** [offset] +1 = next channel, -1 = previous, within the list the user came from. */
    fun switchChannel(offset: Int) {
        val live = current as? Current.Live ?: return
        viewModelScope.launch {
            val next = try {
                deps.channels.adjacentChannel(live.channel, channelGroup, offset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (next == null || next.id == live.channel.id) {
                showNotice(PlayerNotice.NoMoreChannels)
            } else if (current == live) {
                switchToChannel(next)
            }
        }
    }

    fun onDigit(digit: Int) {
        if (current !is Current.Live || digit !in 0..9) return
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
        val live = current as? Current.Live ?: return
        if (number == null) return
        if (number == live.channel.number) return
        val target = try {
            deps.channels.findByNumber(live.channel.playlistId, number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
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
        saveHistory()
        resumeOnStart = novaPlayer.player.playWhenReady
        novaPlayer.pause()
    }

    fun onStart() {
        if (!resumeOnStart) return
        resumeOnStart = false
        // Live: rejoin the live edge instead of playing stale buffer.
        if (current is Current.Live) novaPlayer.retry() else novaPlayer.resume()
    }

    override fun onCleared() {
        saveHistory()
        novaPlayer.release()
        if (ownsHttpClient) {
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
        }
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
        return when (val c = current) {
            is Current.Live -> WatchHistory(
                contentType = ContentType.Channel,
                contentId = c.channel.id,
                playlistId = c.channel.playlistId,
                title = c.channel.name,
                imageUrl = c.channel.logoUrl,
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

        /** Outlives the ViewModel so the final history write in onCleared completes. */
        val PersistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
