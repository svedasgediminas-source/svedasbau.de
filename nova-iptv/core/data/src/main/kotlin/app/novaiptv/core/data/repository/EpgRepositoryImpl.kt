package app.novaiptv.core.data.repository

import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.common.Clock
import app.novaiptv.core.common.Limits
import app.novaiptv.core.common.net.HttpDownloader
import app.novaiptv.core.data.db.ChannelDao
import app.novaiptv.core.data.db.ChannelEpgRow
import app.novaiptv.core.data.db.PROGRAM_BATCH
import app.novaiptv.core.data.db.PlaylistDao
import app.novaiptv.core.data.db.PlaylistEntity
import app.novaiptv.core.data.db.PlaylistTypes
import app.novaiptv.core.data.db.ProgramDao
import app.novaiptv.core.data.db.ProgramEntity
import app.novaiptv.core.data.db.SQL_IN_CHUNK
import app.novaiptv.core.data.logic.DAY_MILLIS
import app.novaiptv.core.data.logic.EpgWindow
import app.novaiptv.core.data.logic.NowNextSelector
import app.novaiptv.core.data.logic.SearchText
import app.novaiptv.core.data.mapper.splitUrls
import app.novaiptv.core.data.mapper.toEntity
import app.novaiptv.core.data.mapper.toMatchChannel
import app.novaiptv.core.data.mapper.toModel
import app.novaiptv.core.domain.CredentialStore
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.model.EpgChannel
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.NowNext
import app.novaiptv.data.epg.EpgMatcher
import app.novaiptv.data.epg.XmltvParser
import app.novaiptv.data.xtream.XtreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream

class EpgRepositoryImpl(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val programDao: ProgramDao,
    private val downloader: HttpDownloader,
    private val xtream: XtreamClient,
    private val credentials: CredentialStore,
    private val preferences: PreferencesRepository,
    private val clock: Clock,
    private val parserFactory: () -> XmlPullParser,
) : EpgRepository {

    private val refreshMutex = Mutex()

    override fun observeNowNext(epgIds: List<String>, nowMillis: Long): Flow<Map<String, NowNext>> {
        val ids = epgIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return flowOf(emptyMap())
        // Current programme (ends after now) plus anything starting within the next day.
        return chunkedInFlow<ProgramEntity>(ids) { programDao.observeRange(it, nowMillis, nowMillis + DAY_MILLIS) }
            .map { rows -> NowNextSelector.select(rows.map { it.toModel() }, nowMillis) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    override suspend fun programs(epgId: String, fromMillis: Long, toMillis: Long): List<EpgProgram> =
        withContext(Dispatchers.IO) { programDao.range(listOf(epgId), fromMillis, toMillis).map { it.toModel() } }

    override suspend fun programsForChannels(
        epgIds: List<String>,
        fromMillis: Long,
        toMillis: Long,
    ): Map<String, List<EpgProgram>> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, List<EpgProgram>>()
        for (chunk in epgIds.filter { it.isNotBlank() }.distinct().chunked(SQL_IN_CHUNK)) {
            programDao.range(chunk, fromMillis, toMillis).map { it.toModel() }
                .groupBy { it.channelEpgId }
                .forEach { (id, list) -> out[id] = list }
        }
        out
    }

    override suspend fun searchPrograms(query: String, fromMillis: Long, limit: Int): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val pattern = SearchText.rawContainsPattern(query) ?: return@withContext emptyList()
            programDao.search(pattern, fromMillis, limit.coerceAtLeast(0)).map { it.toModel() }
        }

    override suspend fun setManualMapping(channelId: String, epgId: String?) {
        withContext(Dispatchers.IO) { channelDao.setManualEpgId(channelId, epgId?.trim()?.takeIf { it.isNotEmpty() }) }
    }

    override suspend fun refresh(playlistId: Long): AppResult<Int> = withContext(Dispatchers.IO) {
        try {
            refreshMutex.withLock { doRefresh(playlistId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppResult.Err(AppError.Network(e.javaClass.simpleName))
        } catch (e: RuntimeException) {
            AppResult.Err(AppError.Unknown(e.javaClass.simpleName))
        }
    }

    /** EPG sources: user/playlist URLs, plus the portal's XMLTV for Xtream when none were set. */
    private suspend fun epgUrlsFor(playlist: PlaylistEntity): List<String> {
        val urls = splitUrls(playlist.epgUrls).toMutableList()
        if (playlist.type == PlaylistTypes.XTREAM && urls.isEmpty()) {
            // Built at refresh time only: the URL embeds credentials and is never persisted.
            val creds = playlist.credentialKey?.let { credentials.get(it) }
            if (creds != null) urls += xtream.xmltvUrl(creds)
        }
        return urls.distinct()
    }

    private suspend fun doRefresh(playlistId: Long): AppResult<Int> {
        val playlist = playlistDao.get(playlistId) ?: return AppResult.Err(AppError.InvalidInput("playlist"))
        val urls = epgUrlsFor(playlist)
        if (urls.isEmpty()) return AppResult.Ok(0)
        val rows = channelDao.epgRows(playlistId)
        if (rows.isEmpty()) return AppResult.Ok(0)
        val shift = preferences.preferences.first().epgTimeShiftMinutes
        val now = clock.nowMillis()

        val session = ImportSession(rows, now)
        var total = 0
        var succeeded = false
        var lastError: AppError? = null
        for (url in urls) {
            val result = downloader.download(url, Limits.MAX_EPG_BYTES) { input -> session.import(input, shift) }
            when (result) {
                is AppResult.Ok -> when (val inner = result.value) {
                    is AppResult.Ok -> {
                        total += inner.value
                        succeeded = true
                    }
                    is AppResult.Err -> lastError = inner.error
                }
                is AppResult.Err -> lastError = result.error
            }
        }
        if (session.resolvedUpdates.isNotEmpty()) channelDao.setResolvedEpgIds(session.resolvedUpdates)
        programDao.deleteEndedBefore(EpgWindow.purgeBefore(now, channelDao.maxCatchupDays()))
        return if (succeeded) AppResult.Ok(total) else AppResult.Err(lastError ?: AppError.Empty)
    }

    /**
     * State of one EPG refresh across all its URLs. XMLTV lists <channel> elements before
     * <programme> elements, so the channel -> XMLTV id match is built when the first programme
     * arrives. Old programmes of an id are deleted in the same transaction as the first batch
     * that contains new programmes for it (so a failed download keeps the previous data).
     */
    private inner class ImportSession(private val rows: List<ChannelEpgRow>, private val now: Long) {
        val resolvedUpdates = HashMap<String, String?>()
        private val cleared = HashSet<String>()

        /** Runs on the IO thread inside the HTTP download. */
        fun import(input: InputStream, shiftMinutes: Int): AppResult<Int> {
            val byId = HashMap<String, EpgChannel>()
            var wanted: Map<String, Long>? = null
            val batch = ArrayList<ProgramEntity>(PROGRAM_BATCH)
            var inserted = 0

            fun flush() {
                if (batch.isEmpty()) return
                val toClear = batch.asSequence().map { it.epgId }.distinct().filter { cleared.add(it) }.toList()
                programDao.replaceBatchBlocking(toClear, ArrayList(batch))
                inserted += batch.size
                batch.clear()
            }

            val stats = XmltvParser(parserFactory).parse(
                input,
                onChannel = { ch -> if (byId.size < MAX_XMLTV_CHANNELS) byId[ch.id] = ch },
                onProgram = prog@{ p ->
                    val w = wanted ?: buildWanted(byId).also { wanted = it }
                    val back = w[p.channelEpgId] ?: return@prog
                    if (!EpgWindow.keep(p.startMillis, p.endMillis, now, back)) return@prog
                    batch += p.toEntity()
                    if (batch.size >= PROGRAM_BATCH) flush()
                },
                timeShiftMinutes = shiftMinutes,
            )
            if (wanted == null) buildWanted(byId) // Records name matches even when no programme was kept.
            flush()
            return if (stats.error != null && inserted == 0 && stats.programs == 0) {
                AppResult.Err(AppError.Parse(stats.error))
            } else {
                AppResult.Ok(inserted)
            }
        }

        /** XMLTV id -> look-back window, for every id any channel of the playlist maps to. */
        private fun buildWanted(byId: Map<String, EpgChannel>): Map<String, Long> {
            val nameIndex = EpgMatcher.buildNameIndex(byId.values)
            val wanted = HashMap<String, Long>()
            fun want(id: String, days: Int) {
                val back = EpgWindow.backMillis(days)
                val prev = wanted[id]
                if (prev == null || back > prev) wanted[id] = back
            }
            for (row in rows) {
                val manual = row.manualEpgId
                if (manual != null) {
                    want(manual, row.catchupDays)
                    continue
                }
                // Feeds without <channel> elements still match on the exact tvg-id.
                row.epgId?.let { want(it, row.catchupDays) }
                val matched = EpgMatcher.match(row.toMatchChannel(), byId, nameIndex)
                if (matched != null) {
                    want(matched, row.catchupDays)
                    if (!resolvedUpdates.containsKey(row.id)) {
                        if (matched != row.epgId) {
                            resolvedUpdates[row.id] = matched
                        } else if (row.resolvedEpgId != null) {
                            resolvedUpdates[row.id] = null // tvg-id matches exactly now
                        }
                    }
                } else if (row.resolvedEpgId != null && byId.isNotEmpty()) {
                    // Keep serving the previous resolution's programmes from this feed, if any.
                    want(row.resolvedEpgId, row.catchupDays)
                }
            }
            return wanted
        }
    }

    private companion object {
        /** Guards memory against absurd feeds; real-world XMLTV files have a few thousand channels. */
        const val MAX_XMLTV_CHANNELS = 100_000
    }
}
