package app.novaiptv.feature.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.Clock
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.UserPreferences
import app.novaiptv.feature.livetv.filterChannels
import app.novaiptv.feature.livetv.resolveActivePlaylistId
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface GuideChannelsState {
    data object Loading : GuideChannelsState
    data object NoPlaylist : GuideChannelsState
    data class Failed(val error: AppError) : GuideChannelsState
    data class Loaded(val channels: List<Channel>) : GuideChannelsState
}

/** Programmes of the selected day keyed by EPG id. A missing key means "not loaded yet". */
data class ProgramCache(val dayStart: Long, val byEpgId: Map<String, List<EpgProgram>> = emptyMap())

sealed interface GuideMessage {
    data object ArchiveUnavailable : GuideMessage
    data object ArchiveExpired : GuideMessage
    data class Upcoming(val startMillis: Long) : GuideMessage
    data object NoInformation : GuideMessage
}

sealed interface GuidePlayRequest {
    data class Live(val channelId: String) : GuidePlayRequest
    data class Catchup(val channelId: String, val startMillis: Long, val endMillis: Long) : GuidePlayRequest
}

data class GuideUiState(
    val channels: GuideChannelsState = GuideChannelsState.Loading,
    val days: List<GuideDay> = emptyList(),
    val day: GuideDay,
    val cursor: GuideCursor? = null,
    val programs: ProgramCache,
    val nowMillis: Long,
    val showNumbers: Boolean = true,
    val message: GuideMessage? = null,
) {
    val channelList: List<Channel> get() = (channels as? GuideChannelsState.Loaded)?.channels.orEmpty()

    /** Programmes for [channel]: null while loading, empty when there is no EPG. */
    fun programsFor(channel: Channel): List<EpgProgram>? {
        val id = channel.epgId ?: return emptyList()
        return programs.byEpgId[id]
    }
}

private data class PlaylistPick(val id: Long?)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GuideViewModel(
    private val playlists: PlaylistRepository,
    private val channelRepo: ChannelRepository,
    private val epg: EpgRepository,
    private val history: HistoryRepository,
    private val preferences: PreferencesRepository,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val retryTrigger = MutableStateFlow(0)
    private val selectedDate = MutableStateFlow(localDateOf(clock.nowMillis(), zone))
    private val cursor = MutableStateFlow<GuideCursor?>(null)
    private val spanMillis = MutableStateFlow(0L)
    private val visibleRows = MutableStateFlow(0..15)
    private val programs = MutableStateFlow(ProgramCache(guideDay(selectedDate.value, zone).startMillis))
    private val message = MutableStateFlow<GuideMessage?>(null)
    private var messageJob: Job? = null
    private var initialRow: Int = 0

    private val prefs: StateFlow<UserPreferences> = preferences.preferences
        .catch { emit(UserPreferences()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    val nowMillis: StateFlow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, clock.nowMillis())

    private val playlistPick: Flow<PlaylistPick> = retryTrigger.flatMapLatest {
        combine(prefs, playlists.observePlaylists()) { p, list ->
            PlaylistPick(resolveActivePlaylistId(p.activePlaylistId, list.map { it.id }))
        }
    }.catch { emit(PlaylistPick(null)) }.distinctUntilChanged()

    private val channelsState: StateFlow<GuideChannelsState> = combine(
        playlistPick,
        prefs.map { it.hideAdultContent }.distinctUntilChanged(),
    ) { pick, hideAdult -> pick to hideAdult }
        .flatMapLatest { (pick, hideAdult) ->
            val pid = pick.id
            if (pid == null) {
                flowOf(GuideChannelsState.NoPlaylist)
            } else {
                channelRepo.observeChannels(pid, null)
                    .map<List<Channel>, GuideChannelsState> { GuideChannelsState.Loaded(filterChannels(it, "", hideAdult)) }
                    .onStart { emit(GuideChannelsState.Loading) }
                    .catch { e -> emit(GuideChannelsState.Failed(AppError.Unknown(e.message))) }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, GuideChannelsState.Loading)

    private val days: Flow<List<GuideDay>> = combine(
        channelsState.map { s -> maxCatchupDays((s as? GuideChannelsState.Loaded)?.channels.orEmpty()) }.distinctUntilChanged(),
        nowMillis.map { localDateOf(it, zone) }.distinctUntilChanged(),
    ) { past, _ -> buildGuideDays(clock.nowMillis(), zone, past) }

    val state: StateFlow<GuideUiState> = combine(
        channelsState,
        days,
        selectedDate,
        combine(cursor, programs, message) { cur, cache, msg -> Triple(cur, cache, msg) },
        combine(nowMillis, prefs) { now, p -> now to p.showChannelNumbers },
    ) { chs, dayList, date, (cur, cache, msg), (now, showNumbers) ->
        GuideUiState(
            channels = chs,
            days = dayList,
            day = guideDay(date, zone),
            cursor = cur,
            programs = cache,
            nowMillis = now,
            showNumbers = showNumbers,
            message = msg,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GuideUiState(
            day = guideDay(selectedDate.value, zone),
            programs = programs.value,
            nowMillis = clock.nowMillis(),
        ),
    )

    init {
        // Lazy EPG loading for the visible channel range of the selected day.
        viewModelScope.launch {
            combine(
                visibleRows.debounce(150),
                channelsState.filterIsInstance<GuideChannelsState.Loaded>(),
                selectedDate,
            ) { rows, loaded, date -> Triple(rows, loaded.channels, date) }
                .collectLatest { (rows, list, date) -> loadPrograms(rows, list, guideDay(date, zone)) }
        }
        // Start on the last watched channel, if any.
        viewModelScope.launch {
            val lastId: String? = try {
                history.observeRecentChannels(1).first().firstOrNull()?.contentId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            if (lastId == null) return@launch
            val loaded = channelsState.filterIsInstance<GuideChannelsState.Loaded>().first()
            val index = loaded.channels.indexOfFirst { it.id == lastId }
            if (index >= 0) {
                initialRow = index
                cursor.update { it?.copy(row = index) }
            }
        }
    }

    private suspend fun loadPrograms(rows: IntRange, list: List<Channel>, day: GuideDay) {
        if (list.isEmpty()) return
        if (programs.value.dayStart != day.startMillis) programs.value = ProgramCache(day.startMillis)
        val from = (rows.first - PREFETCH_ROWS).coerceAtLeast(0)
        val to = (rows.last + PREFETCH_ROWS).coerceAtMost(list.lastIndex)
        if (from > to) return
        val window = list.subList(from, to + 1)
        val missing = window.mapNotNull { it.epgId }.distinct().filter { it !in programs.value.byEpgId }
        for (chunk in missing.chunked(LOAD_CHUNK)) {
            val result = try {
                epg.programsForChannels(chunk, day.startMillis, day.endMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                continue // not cached: retried on the next scroll
            }
            val loaded = chunk.associateWith { id -> result[id].orEmpty().sortedBy { it.startMillis } }
            programs.update { cache ->
                if (cache.dayStart != day.startMillis) {
                    cache
                } else {
                    var merged = cache.byEpgId + loaded
                    if (merged.size > CACHE_LIMIT) {
                        val keep = window.mapNotNull { it.epgId }.toHashSet()
                        merged = merged.filterKeys { it in keep }
                    }
                    cache.copy(byEpgId = merged)
                }
            }
        }
    }

    private fun currentDay(): GuideDay = guideDay(selectedDate.value, zone)

    private fun channelList(): List<Channel> = (channelsState.value as? GuideChannelsState.Loaded)?.channels.orEmpty()

    private fun cellsForRow(row: Int, day: GuideDay = currentDay()): List<GuideCell> {
        val channel = channelList().getOrNull(row) ?: return emptyList()
        val list = channel.epgId?.let { programs.value.byEpgId[it] }.orEmpty()
        return buildRowCells(list, day)
    }

    fun retry() {
        retryTrigger.value = retryTrigger.value + 1
    }

    fun onVisibleRows(first: Int, last: Int) {
        if (last >= first) visibleRows.value = first..last
    }

    /** Called by the UI with the time span that fits the grid width. */
    fun setViewSpan(span: Long) {
        if (span <= 0) return
        spanMillis.value = span
        val day = currentDay()
        val current = cursor.value
        cursor.value = if (current == null) {
            val now = clock.nowMillis()
            val focus = if (now in day) now else sameLocalTimeOn(now, day.date, zone)
            GuideCursor(row = initialRow, focusMillis = focus, viewStart = initialViewStart(focus, span, day))
        } else {
            current.copy(viewStart = ensureVisible(current.focusMillis, current.focusMillis + 1, current.viewStart, span, day))
        }
    }

    fun selectDay(date: LocalDate) {
        if (date == selectedDate.value) return
        val day = guideDay(date, zone)
        val now = clock.nowMillis()
        val previousFocus = cursor.value?.focusMillis ?: now
        val focus = if (now in day) now else sameLocalTimeOn(previousFocus, date, zone)
        programs.value = ProgramCache(day.startMillis)
        selectedDate.value = date
        val span = spanMillis.value
        cursor.update { cur ->
            val row = cur?.row ?: initialRow
            if (span > 0) GuideCursor(row, focus, initialViewStart(focus, span, day)) else cur
        }
    }

    fun jumpToNow() {
        val now = clock.nowMillis()
        val today = localDateOf(now, zone)
        if (today != selectedDate.value) {
            selectDay(today)
        }
        val span = spanMillis.value
        if (span <= 0) return
        val day = currentDay()
        cursor.update { cur -> GuideCursor(cur?.row ?: initialRow, now, initialViewStart(now, span, day)) }
    }

    /** LEFT/RIGHT. Always consumes the key so focus never leaves the grid sideways. */
    fun moveFocusHorizontally(direction: Int) {
        val cur = cursor.value ?: return
        val span = spanMillis.value
        if (span <= 0) return
        val day = currentDay()
        val next = moveHorizontal(cellsForRow(cur.row, day), cur, direction, span, day) ?: return
        cursor.value = next
    }

    /** UP/DOWN by [delta] rows. Returns false at the top edge so focus can move to the header. */
    fun moveFocusVertically(delta: Int): Boolean {
        val cur = cursor.value ?: return false
        val count = channelList().size
        if (count == 0) return false
        val target = (cur.row + delta).coerceIn(0, count - 1)
        if (target == cur.row) return delta > 0
        cursor.value = cur.copy(row = target)
        return true
    }

    /** OK on the focused cell: a play request, or null after showing an explanatory message. */
    fun select(): GuidePlayRequest? {
        val cur = cursor.value ?: return null
        val channel = channelList().getOrNull(cur.row) ?: return null
        val cells = cellsForRow(cur.row)
        val index = cellIndexAt(cells, cur.focusMillis)
        val cell = cells.getOrNull(index) ?: return null
        val now = clock.nowMillis()
        return when (cell) {
            is GuideCell.Gap -> if (now in cell.start until cell.end) {
                GuidePlayRequest.Live(channel.id)
            } else {
                showMessage(GuideMessage.NoInformation)
                null
            }
            is GuideCell.Program -> {
                val p = cell.program
                when (decideGuideAction(channel.catchup, p.startMillis, p.endMillis, now)) {
                    GuideAction.PlayLive -> GuidePlayRequest.Live(channel.id)
                    GuideAction.PlayCatchup -> GuidePlayRequest.Catchup(channel.id, p.startMillis, p.endMillis)
                    GuideAction.ArchiveUnavailable -> {
                        showMessage(GuideMessage.ArchiveUnavailable)
                        null
                    }
                    GuideAction.ArchiveExpired -> {
                        showMessage(GuideMessage.ArchiveExpired)
                        null
                    }
                    GuideAction.Upcoming -> {
                        showMessage(GuideMessage.Upcoming(p.startMillis))
                        null
                    }
                }
            }
        }
    }

    private fun showMessage(msg: GuideMessage) {
        message.value = msg
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(4_000)
            message.value = null
        }
    }

    private companion object {
        const val PREFETCH_ROWS = 8
        const val LOAD_CHUNK = 40
        const val CACHE_LIMIT = 1_500
    }
}
