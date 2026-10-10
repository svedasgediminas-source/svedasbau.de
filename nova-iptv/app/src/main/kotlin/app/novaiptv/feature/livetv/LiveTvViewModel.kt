package app.novaiptv.feature.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.Clock
import app.novaiptv.core.domain.ChannelListItem
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.FavoritesRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.domain.toListItem
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.NowNext
import app.novaiptv.core.model.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of loading the channel list for the selected category. */
sealed interface ChannelListState {
    data object Loading : ChannelListState
    data object NoPlaylist : ChannelListState
    data class Failed(val error: AppError) : ChannelListState
    /**
     * Slim rows (no stream/extras) so 200k-channel playlists stay cheap; the full channel is
     * loaded by id only for playback. [category] is the category these channels belong to (it
     * lags the selection by a debounce).
     */
    data class Loaded(val channels: List<ChannelListItem>, val category: CategoryKey) : ChannelListState
}

data class LiveTvUiState(
    val list: ChannelListState = ChannelListState.Loading,
    val categories: List<LiveCategory> = emptyList(),
    val selectedCategory: CategoryKey = CategoryKey.All,
    val favoriteIds: Set<String> = emptySet(),
    val showNumbers: Boolean = true,
    val query: String = "",
) {
    val channels: List<ChannelListItem> get() = (list as? ChannelListState.Loaded)?.channels.orEmpty()
}

sealed interface LiveTvEvent {
    /** Focus (and scroll to) this channel. */
    data class JumpTo(val channelId: String) : LiveTvEvent
    data class NumberNotFound(val number: Int) : LiveTvEvent
    data class FavoriteChanged(val channelName: String, val added: Boolean) : LiveTvEvent
}

private data class PlaylistPick(val id: Long?)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveTvViewModel(
    private val playlists: PlaylistRepository,
    private val channels: ChannelRepository,
    private val epg: EpgRepository,
    private val favorites: FavoritesRepository,
    private val history: HistoryRepository,
    private val preferences: PreferencesRepository,
    private val clock: Clock,
    /** Where list filtering runs; injectable for tests. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val retryTrigger = MutableStateFlow(0)
    private val selectedCategory = MutableStateFlow<CategoryKey>(CategoryKey.All)
    private val query = MutableStateFlow("")
    /** EPG ids of the rows currently on screen. */
    private val visibleEpgIds = MutableStateFlow<List<String>>(emptyList())
    private val _numberEntry = MutableStateFlow("")
    private val _focusedChannelId = MutableStateFlow<String?>(null)
    private val _initialChannelId = MutableStateFlow<String?>(null)
    private val _initialResolved = MutableStateFlow(false)
    private val events = EventChannel<LiveTvEvent>(EventChannel.BUFFERED)
    private var numberJob: Job? = null

    val eventFlow: Flow<LiveTvEvent> = events.receiveAsFlow()
    val numberEntry: StateFlow<String> = _numberEntry.asStateFlow()
    val focusedChannelId: StateFlow<String?> = _focusedChannelId.asStateFlow()

    /** Last played channel (from history), used once to place initial focus. */
    val initialChannelId: StateFlow<String?> = _initialChannelId.asStateFlow()
    val initialResolved: StateFlow<Boolean> = _initialResolved.asStateFlow()

    private val prefs: StateFlow<UserPreferences> = preferences.preferences
        .catch { emit(UserPreferences()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    /** null until playlists are known; [PlaylistPick.id] null = the user has no playlist. */
    private val playlistPick: StateFlow<PlaylistPick?> = retryTrigger
        .flatMapLatest {
            combine(prefs, playlists.observePlaylists()) { p, list ->
                PlaylistPick(resolveActivePlaylistId(p.activePlaylistId, list.map { it.id }))
            }
        }
        .catch { emit(PlaylistPick(null)) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val playlistId: Flow<Long?> = playlistPick.filterNotNull().map { it.id }.distinctUntilChanged()

    private val favoriteIdList: Flow<List<String>> = combine(
        favorites.observeFavorites(ContentType.Channel).catch { emit(emptyList()) },
        playlistId,
    ) { favs, pid -> if (pid == null) emptyList() else favoriteChannelIds(favs, pid) }
        .distinctUntilChanged()

    private val rawList: Flow<ChannelListState> = retryTrigger
        .flatMapLatest {
            combine(playlistId, selectedCategory.debounce(CATEGORY_DEBOUNCE_MS)) { pid, cat -> pid to cat }
                .distinctUntilChanged()
                .flatMapLatest { (pid, cat) -> channelsFor(pid, cat) }
        }

    private val listState: Flow<ChannelListState> = combine(
        rawList,
        query.debounce(QUERY_DEBOUNCE_MS).onStart { emit(query.value) }.distinctUntilChanged(),
        prefs.map { it.hideAdultContent }.distinctUntilChanged(),
    ) { raw, q, hideAdult ->
        if (raw is ChannelListState.Loaded) {
            withContext(computeDispatcher) { raw.copy(channels = filterChannels(raw.channels, q, hideAdult)) }
        } else {
            raw
        }
    }

    private val categories: Flow<List<LiveCategory>> = combine(
        playlistId.flatMapLatest { pid ->
            if (pid == null) flowOf(emptyList()) else channels.observeGroups(pid).catch { emit(emptyList()) }
        },
        prefs.map { it.hideAdultContent }.distinctUntilChanged(),
        favoriteIdList,
    ) { groups, hideAdult, favIds -> buildCategories(groups, hideAdult, favIds.size) }

    val state: StateFlow<LiveTvUiState> = combine(
        listState,
        categories,
        selectedCategory,
        favoriteIdList,
        combine(prefs, query) { p, q -> p.showChannelNumbers to q },
    ) { list, cats, selected, favIds, (showNumbers, q) ->
        LiveTvUiState(
            list = list,
            categories = cats,
            selectedCategory = selected,
            favoriteIds = favIds.toHashSet(),
            showNumbers = showNumbers,
            query = q,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveTvUiState())

    /** Ticks every 30 s; drives progress bars and now/next refresh. */
    val nowMillis: StateFlow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(TICK_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock.nowMillis())

    /**
     * Now/next for the channels currently on screen only (keyed by EPG id), re-queried every
     * minute so the current programme advances. Results are merged so scrolling back does not
     * flash empty rows; the cache is reset when it grows large. Never scans the whole list.
     */
    val nowNext: StateFlow<Map<String, NowNext>> = combine(
        visibleEpgIds.debounce(VISIBLE_DEBOUNCE_MS).distinctUntilChanged(),
        nowMillis.map { it / 60_000L }.distinctUntilChanged(),
    ) { ids, minute -> ids to minute }
        .distinctUntilChanged()
        .flatMapLatest { (epgIds, _) ->
            if (epgIds.isEmpty()) {
                flowOf(emptyMap())
            } else {
                epg.observeNowNext(epgIds, clock.nowMillis()).catch { emit(emptyMap()) }
            }
        }
        .scan(emptyMap<String, NowNext>()) { acc, latest -> if (acc.size > NOW_NEXT_CACHE) latest else acc + latest }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            val last = try {
                history.observeRecentChannels(1).first().firstOrNull()?.contentId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            _initialChannelId.value = last
            _initialResolved.value = true
        }
    }

    private fun channelsFor(pid: Long?, cat: CategoryKey): Flow<ChannelListState> {
        if (pid == null) return flowOf(ChannelListState.NoPlaylist)
        val source: Flow<List<ChannelListItem>> = when (cat) {
            CategoryKey.All -> channels.observeChannelItems(pid, null)
            is CategoryKey.Group -> channels.observeChannelItems(pid, cat.name)
            CategoryKey.Favorites -> favoriteIdList.flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    // Favourites are few: full channels mapped to slim rows.
                    channels.observeChannelsByIds(ids).map { list -> orderByIds(list.map { it.toListItem() }, ids) }
                }
            }
        }
        return source
            .map<List<ChannelListItem>, ChannelListState> { ChannelListState.Loaded(it, cat) }
            .onStart { emit(ChannelListState.Loading) }
            .catch { e -> emit(ChannelListState.Failed(AppError.Unknown(e.message))) }
    }

    fun retry() {
        retryTrigger.value = retryTrigger.value + 1
    }

    fun selectCategory(key: CategoryKey) {
        selectedCategory.value = key
    }

    fun setQuery(text: String) {
        query.value = text.take(MAX_QUERY)
    }

    fun onChannelFocused(channelId: String) {
        _focusedChannelId.value = channelId
    }

    /** Rows currently on screen; only these get now/next. */
    fun onVisibleChannels(visible: List<ChannelListItem>) {
        visibleEpgIds.value = visible.mapNotNull { it.epgId }.distinct()
    }

    fun toggleFavorite(channel: ChannelListItem) {
        viewModelScope.launch {
            val added = try {
                favorites.toggle(ContentType.Channel, channel.id, channel.playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@launch
            }
            events.send(LiveTvEvent.FavoriteChanged(channel.name, added))
        }
    }

    fun appendNumberDigit(digit: Int) {
        _numberEntry.value = appendDigit(_numberEntry.value, digit)
        numberJob?.cancel()
        if (_numberEntry.value.isEmpty()) return
        numberJob = viewModelScope.launch {
            delay(NUMBER_COMMIT_MS)
            commitNumber()
        }
    }

    /** OK pressed while a number is being typed: jump immediately. */
    fun commitNumberNow(): Boolean {
        if (_numberEntry.value.isEmpty()) return false
        numberJob?.cancel()
        numberJob = viewModelScope.launch { commitNumber() }
        return true
    }

    private suspend fun commitNumber() {
        val number = _numberEntry.value.toIntOrNull()
        _numberEntry.value = ""
        if (number == null) return
        val pid = playlistPick.value?.id ?: return
        val channel = try {
            channels.findByNumber(pid, number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        if (channel == null || (prefs.value.hideAdultContent && isAdultChannel(channel))) {
            events.send(LiveTvEvent.NumberNotFound(number))
            return
        }
        val inList = state.value.channels.any { it.id == channel.id }
        if (!inList) {
            // Show it in "All channels" so it can be focused.
            query.value = ""
            selectedCategory.value = CategoryKey.All
        }
        _focusedChannelId.value = channel.id
        events.send(LiveTvEvent.JumpTo(channel.id))
    }

    private companion object {
        const val CATEGORY_DEBOUNCE_MS = 200L
        const val QUERY_DEBOUNCE_MS = 250L
        const val VISIBLE_DEBOUNCE_MS = 150L
        const val NUMBER_COMMIT_MS = 1_500L
        const val TICK_MS = 30_000L
        const val NOW_NEXT_CACHE = 400
        const val MAX_QUERY = 60
    }
}
