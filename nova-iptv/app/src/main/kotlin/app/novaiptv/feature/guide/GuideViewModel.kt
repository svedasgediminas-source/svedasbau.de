package app.novaiptv.feature.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.Clock
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.FavoritesRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.domain.ReminderRepository
import app.novaiptv.core.domain.reminderKey
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.UserPreferences
import app.novaiptv.feature.livetv.favoriteChannelIds
import app.novaiptv.feature.livetv.filterChannels
import app.novaiptv.feature.livetv.resolveActivePlaylistId
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface GuideChannelsState {
    data object Loading : GuideChannelsState
    data object NoPlaylist : GuideChannelsState
    data class Failed(val error: AppError) : GuideChannelsState
    /**
     * [channels] are the rows of the selected group; [unfilteredCount] is the size of the whole
     * (adult-filtered) playlist, so an empty group can be told apart from an empty playlist.
     */
    data class Loaded(val channels: List<Channel>, val unfilteredCount: Int = channels.size) : GuideChannelsState
}

/** Programmes of the selected day keyed by EPG id. A missing key means "not loaded yet". */
data class ProgramCache(val dayStart: Long, val byEpgId: Map<String, List<EpgProgram>> = emptyMap())

sealed interface GuideMessage {
    data object ArchiveUnavailable : GuideMessage
    data object ArchiveExpired : GuideMessage
    data object NoInformation : GuideMessage
    data class ReminderSet(val startMillis: Long) : GuideMessage
    data object ReminderRemoved : GuideMessage
}

/** OK on a future programme: ask whether to set (or remove) a reminder. */
data class GuideReminderPrompt(val channel: Channel, val program: EpgProgram, val reminded: Boolean)

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
    val groups: List<GuideGroup> = emptyList(),
    val selectedGroup: GuideGroupKey = GuideGroupKey.All,
    /** [reminderKey]s of programmes with a reminder. */
    val reminderKeys: Set<String> = emptySet(),
    val reminderPrompt: GuideReminderPrompt? = null,
) {
    val channelList: List<Channel> get() = (channels as? GuideChannelsState.Loaded)?.channels.orEmpty()

    /** Programmes for [channel]: null while loading, empty when there is no EPG. */
    fun programsFor(channel: Channel): List<EpgProgram>? {
        val id = channel.epgId ?: return emptyList()
        return programs.byEpgId[id]
    }

    fun isReminded(channel: Channel, program: EpgProgram): Boolean =
        reminderKeys.isNotEmpty() && reminderKey(channel.id, program.startMillis) in reminderKeys
}

private data class PlaylistPick(val id: Long?)

/** Base (adult-filtered) playlist channels, the group chips and the rows of the selected group. */
private data class GuideListData(
    val base: GuideChannelsState,
    val shown: GuideChannelsState,
    val groups: List<GuideGroup>,
    val selected: GuideGroupKey,
)

private data class GuideExtras(
    val groups: List<GuideGroup>,
    val selected: GuideGroupKey,
    val reminderKeys: Set<String>,
    val prompt: GuideReminderPrompt?,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GuideViewModel(
    private val playlists: PlaylistRepository,
    private val channelRepo: ChannelRepository,
    private val epg: EpgRepository,
    private val history: HistoryRepository,
    private val preferences: PreferencesRepository,
    private val favorites: FavoritesRepository,
    private val reminders: ReminderRepository,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Where list filtering runs; injectable for tests. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val retryTrigger = MutableStateFlow(0)
    private val selectedDate = MutableStateFlow(localDateOf(clock.nowMillis(), zone))
    private val cursor = MutableStateFlow<GuideCursor?>(null)
    private val spanMillis = MutableStateFlow(0L)
    private val visibleRows = MutableStateFlow(0..15)
    /** When an EPG id last came back empty; such ids are re-queried after [EMPTY_RECHECK_MS]. */
    private val emptyLoadedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val programs = MutableStateFlow(ProgramCache(guideDay(selectedDate.value, zone).startMillis))
    private val message = MutableStateFlow<GuideMessage?>(null)
    private var messageJob: Job? = null
    private var initialRow: Int = 0
    private val selectedGroup = MutableStateFlow<GuideGroupKey>(GuideGroupKey.All)
    private val reminderPrompt = MutableStateFlow<GuideReminderPrompt?>(null)

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
    }.catch { emit(PlaylistPick(null)) }
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** Favourite channel ids of the active playlist, in the user's order. */
    private val favoriteIds: Flow<List<String>> = combine(
        playlistPick,
        favorites.observeFavorites(ContentType.Channel).catch { emit(emptyList()) },
    ) { pick, favs -> pick.id?.let { favoriteChannelIds(favs, it) }.orEmpty() }
        .distinctUntilChanged()

    private val reminderKeys: StateFlow<Set<String>> = reminders.observeReminders()
        .map { list -> list.map { it.key }.toSet() }
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val baseChannels: Flow<GuideChannelsState> = combine(
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
        .flowOn(computeDispatcher)

    private val listData: StateFlow<GuideListData> = combine(
        baseChannels,
        selectedGroup,
        favoriteIds.onStart { emit(emptyList()) },
        prefs.map { it.hideAdultContent }.distinctUntilChanged(),
    ) { base, selected, favIds, hideAdult ->
        if (base !is GuideChannelsState.Loaded) {
            GuideListData(base, base, emptyList(), selected)
        } else {
            val all = base.channels
            val favCount = filterGuideChannels(all, GuideGroupKey.Favorites, favIds).size
            val groups = buildGuideGroups(all, favCount, hideAdult)
            val key = resolveGuideGroup(selected, groups)
            val shown = GuideChannelsState.Loaded(filterGuideChannels(all, key, favIds), unfilteredCount = all.size)
            GuideListData(base, shown, groups, key)
        }
    }
        .flowOn(computeDispatcher)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            GuideListData(GuideChannelsState.Loading, GuideChannelsState.Loading, emptyList(), GuideGroupKey.All),
        )

    private val channelsState: StateFlow<GuideChannelsState> = listData
        .map { it.shown }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GuideChannelsState.Loading)

    private val days: Flow<List<GuideDay>> = combine(
        // Whole playlist (not the selected group) so the day list stays stable while filtering.
        listData.map { d -> maxCatchupDays((d.base as? GuideChannelsState.Loaded)?.channels.orEmpty()) }.distinctUntilChanged(),
        nowMillis.map { localDateOf(it, zone) }.distinctUntilChanged(),
    ) { past, _ -> buildGuideDays(clock.nowMillis(), zone, past) }

    val state: StateFlow<GuideUiState> = combine(
        channelsState,
        days,
        selectedDate,
        combine(cursor, programs, message) { cur, cache, msg -> Triple(cur, cache, msg) },
        combine(nowMillis, prefs, listData, reminderKeys, reminderPrompt) { now, p, data, keys, prompt ->
            Triple(now, p.showChannelNumbers, GuideExtras(data.groups, data.selected, keys, prompt))
        },
    ) { chs, dayList, date, (cur, cache, msg), (now, showNumbers, ex) ->
        GuideUiState(
            channels = chs,
            days = dayList,
            day = guideDay(date, zone),
            cursor = cur,
            programs = cache,
            nowMillis = now,
            showNumbers = showNumbers,
            message = msg,
            groups = ex.groups,
            selectedGroup = ex.selected,
            reminderKeys = ex.reminderKeys,
            reminderPrompt = ex.prompt,
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
                nowMillis, // periodic tick: re-checks rows whose EPG was still empty
            ) { rows, loaded, date, _ -> Triple(rows, loaded.channels, date) }
                .collectLatest { (rows, list, date) -> loadPrograms(rows, list, guideDay(date, zone)) }
        }
        // Keep the cursor inside the list when it shrinks (group switch, favourite removed).
        viewModelScope.launch {
            channelsState.filterIsInstance<GuideChannelsState.Loaded>().collect { loaded ->
                val size = loaded.channels.size
                cursor.update { cur -> if (cur != null && size > 0 && cur.row > size - 1) cur.copy(row = size - 1) else cur }
            }
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
        val now = System.currentTimeMillis()
        // Empty results are re-queried after a while: EPG may still be importing in the background.
        val missing = window.mapNotNull { it.epgId }.distinct().filter { id ->
            val cached = programs.value.byEpgId[id]
            cached == null || (cached.isEmpty() && now - (emptyLoadedAt[id] ?: 0L) > EMPTY_RECHECK_MS)
        }
        for (chunk in missing.chunked(LOAD_CHUNK)) {
            val result = try {
                epg.programsForChannels(chunk, day.startMillis, day.endMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                continue // not cached: retried on the next scroll
            }
            val loaded = chunk.associateWith { id -> result[id].orEmpty().sortedBy { it.startMillis } }
            loaded.forEach { (id, list) -> if (list.isEmpty()) emptyLoadedAt[id] = now else emptyLoadedAt.remove(id) }
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
        emptyLoadedAt.clear()
        programs.value = ProgramCache(programs.value.dayStart)
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
                        val reminded = reminderKey(channel.id, p.startMillis) in reminderKeys.value
                        reminderPrompt.value = GuideReminderPrompt(channel, p, reminded)
                        null
                    }
                }
            }
        }
    }

    /** Chip row: shows only [key]'s channels; the cursor moves to the first row. */
    fun selectGroup(key: GuideGroupKey) {
        if (key == selectedGroup.value) return
        selectedGroup.value = key
        initialRow = 0
        cursor.update { it?.copy(row = 0) }
    }

    /** Restores a saved selection (process death) without moving the cursor. */
    fun restoreGroup(key: GuideGroupKey) {
        if (key != selectedGroup.value) selectedGroup.value = key
    }

    /** [Remind me] / [Remove reminder] in the prompt. */
    fun confirmReminderPrompt() {
        val prompt = reminderPrompt.value ?: return
        reminderPrompt.value = null
        val p = prompt.program
        viewModelScope.launch {
            try {
                if (prompt.reminded) {
                    reminders.remove(prompt.channel.id, p.startMillis)
                    showMessage(GuideMessage.ReminderRemoved)
                } else {
                    // The repository ignores programmes that ended meanwhile; a running one fires at once.
                    reminders.add(reminderFor(prompt.channel, p))
                    showMessage(GuideMessage.ReminderSet(p.startMillis))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Storage failure: the bell simply does not appear; nothing else to do.
            }
        }
    }

    fun dismissReminderPrompt() {
        reminderPrompt.value = null
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
        const val EMPTY_RECHECK_MS = 60_000L
        const val CACHE_LIMIT = 1_500
    }
}
