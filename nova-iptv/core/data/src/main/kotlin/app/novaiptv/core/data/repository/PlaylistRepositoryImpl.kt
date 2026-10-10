package app.novaiptv.core.data.repository

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.common.Clock
import app.novaiptv.core.common.Limits
import app.novaiptv.core.common.UrlValidator
import app.novaiptv.core.common.getOrNull
import app.novaiptv.core.common.net.HttpDownloader
import app.novaiptv.core.common.net.LimitedInputStream
import app.novaiptv.core.common.net.SizeLimitExceededException
import app.novaiptv.core.data.db.ChannelDao
import app.novaiptv.core.data.db.ChannelEntity
import app.novaiptv.core.data.db.ChannelMappingRow
import app.novaiptv.core.data.db.PlaylistDao
import app.novaiptv.core.data.db.PlaylistEntity
import app.novaiptv.core.data.db.PlaylistTypes
import app.novaiptv.core.data.db.ProgramDao
import app.novaiptv.core.data.db.VodDao
import app.novaiptv.core.data.db.VodKinds
import app.novaiptv.core.data.importer.BatchImportOutcome
import app.novaiptv.core.data.importer.TransactionRunner
import app.novaiptv.core.data.importer.importInBatches
import app.novaiptv.core.data.logic.isStale
import app.novaiptv.core.data.mapper.joinList
import app.novaiptv.core.data.mapper.joinUrls
import app.novaiptv.core.data.mapper.toEntity
import app.novaiptv.core.data.mapper.toModel
import app.novaiptv.core.domain.CredentialStore
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.ImportProgress
import app.novaiptv.core.domain.ImportSummary
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.Entitlements
import app.novaiptv.core.model.Playlist
import app.novaiptv.core.model.XtreamCredentials
import app.novaiptv.data.playlist.M3uParser
import app.novaiptv.data.playlist.M3uStreamSummary
import app.novaiptv.data.xtream.XtreamAccount
import app.novaiptv.data.xtream.XtreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.URI

class PlaylistRepositoryImpl(
    private val contentResolver: ContentResolver,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val vodDao: VodDao,
    private val programDao: ProgramDao,
    private val downloader: HttpDownloader,
    private val xtream: XtreamClient,
    private val credentialStore: CredentialStore,
    private val preferences: PreferencesRepository,
    private val epg: EpgRepository,
    private val clock: Clock,
    private val appScope: CoroutineScope,
    /** Wraps the streamed M3U channel replacement in one transaction (`RoomTransactionRunner(database)`). */
    private val transactions: TransactionRunner,
    private val entitlements: () -> Entitlements = { Entitlements() },
) : PlaylistRepository {

    private val progress = MutableStateFlow<ImportProgress>(ImportProgress.Idle)

    /** Imports/refreshes run one at a time: they are memory-heavy on TV boxes. */
    private val importMutex = Mutex()

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAll().map { rows -> rows.map { it.toModel() } }

    override fun observeImportProgress(): Flow<ImportProgress> = progress.asStateFlow()

    override suspend fun getPlaylist(id: Long): Playlist? = withContext(Dispatchers.IO) { playlistDao.get(id)?.toModel() }

    // ---- add -----------------------------------------------------------------------------------

    override suspend fun addM3uUrl(name: String, url: String, epgUrl: String?): AppResult<ImportSummary> =
        runImport(null) {
            val sourceUrl = when (val r = UrlValidator.validateSourceUrl(url)) {
                is AppResult.Err -> return@runImport r
                is AppResult.Ok -> r.value
            }
            val epgValue = when (val r = validateOptionalEpgUrl(epgUrl)) {
                is AppResult.Err -> return@runImport r
                is AppResult.Ok -> r.value
            }
            checkPlaylistLimit()?.let { return@runImport it }

            val id = playlistDao.insert(
                PlaylistEntity(
                    name = cleanName(name, hostOf(sourceUrl)),
                    type = PlaylistTypes.M3U_URL,
                    url = sourceUrl,
                    epgUrls = epgValue.orEmpty(),
                    epgUrlsManual = epgValue != null,
                    createdAt = clock.nowMillis(),
                ),
            )
            importNew(id, credentialKey = null) { downloadM3u(id, sourceUrl) }
        }

    override suspend fun addM3uFile(name: String, uri: String, displayName: String, epgUrl: String?): AppResult<ImportSummary> =
        runImport(null) {
            val parsedUri = parseLocalUri(uri) ?: return@runImport AppResult.Err(AppError.InvalidInput("uri"))
            val epgValue = when (val r = validateOptionalEpgUrl(epgUrl)) {
                is AppResult.Err -> return@runImport r
                is AppResult.Ok -> r.value
            }
            checkPlaylistLimit()?.let { return@runImport it }

            if (parsedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                try {
                    contentResolver.takePersistableUriPermission(parsedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // Provider does not offer persistable grants; the import still works now.
                } catch (e: IllegalArgumentException) {
                    // Same, reported differently by some providers.
                }
            }
            val id = playlistDao.insert(
                PlaylistEntity(
                    name = cleanName(name, displayName.ifBlank { "Playlist" }),
                    type = PlaylistTypes.M3U_FILE,
                    url = uri,
                    displayName = displayName.take(Limits.MAX_NAME_LENGTH),
                    epgUrls = epgValue.orEmpty(),
                    epgUrlsManual = epgValue != null,
                    autoRefreshHours = 0,
                    createdAt = clock.nowMillis(),
                ),
            )
            importNew(id, credentialKey = null) { readM3uFile(id, parsedUri) }
        }

    override suspend fun addXtream(name: String, credentials: XtreamCredentials): AppResult<ImportSummary> =
        runImport(null) {
            val serverUrl = when (val r = UrlValidator.normalizeServerUrl(credentials.serverUrl)) {
                is AppResult.Err -> return@runImport r
                is AppResult.Ok -> r.value
            }
            val username = credentials.username.trim()
            if (username.isEmpty() || credentials.password.isEmpty()) {
                return@runImport AppResult.Err(AppError.InvalidInput("credentials"))
            }
            checkPlaylistLimit()?.let { return@runImport it }
            val creds = XtreamCredentials(serverUrl, username, credentials.password)

            // Authenticate before anything is stored: a wrong password creates nothing.
            progress.value = ImportProgress.Running(null, ImportProgress.Stage.Downloading)
            val account = when (val auth = authenticate(creds)) {
                is AppResult.Err -> return@runImport auth
                is AppResult.Ok -> auth.value
            }

            val key = credentialStore.put(creds)
            val id = try {
                playlistDao.insert(
                    PlaylistEntity(
                        name = cleanName(name, hostOf(serverUrl)),
                        type = PlaylistTypes.XTREAM,
                        url = serverUrl,
                        credentialKey = key,
                        serverTimezone = account.serverTimezone,
                        allowedFormats = joinList(account.allowedOutputFormats),
                        createdAt = clock.nowMillis(),
                    ),
                )
            } catch (e: RuntimeException) {
                withContext(NonCancellable) { credentialStore.delete(key) }
                throw e
            }
            importNew(id, credentialKey = key) { loadXtream(id, creds) }
        }

    // ---- refresh -------------------------------------------------------------------------------

    override suspend fun refresh(playlistId: Long): AppResult<ImportSummary> = runImport(playlistId) {
        refreshLocked(playlistId)
    }

    private suspend fun refreshLocked(playlistId: Long): AppResult<ImportSummary> {
        val playlist = playlistDao.get(playlistId) ?: return AppResult.Err(AppError.InvalidInput("playlist"))
        val result = when (playlist.type) {
            PlaylistTypes.M3U_FILE -> {
                val uri = parseLocalUri(playlist.url)
                if (uri == null) AppResult.Err(AppError.InvalidInput("uri")) else readM3uFile(playlistId, uri)
            }
            PlaylistTypes.XTREAM -> refreshXtream(playlist)
            else -> downloadM3u(playlistId, playlist.url)
        }
        when (result) {
            is AppResult.Ok -> scheduleEpgRefresh(playlistId)
            is AppResult.Err -> playlistDao.setLastError(playlistId, describe(result.error))
        }
        return result
    }

    /** Refreshes playlists whose auto-refresh interval elapsed (local files are never auto-refreshed). */
    suspend fun refreshStale() {
        val now = clock.nowMillis()
        val all = try {
            withContext(Dispatchers.IO) { playlistDao.getAll() }
        } catch (e: RuntimeException) {
            return // Database unavailable: try again on next start.
        }
        val stale = all
            .filter { it.type != PlaylistTypes.M3U_FILE && isStale(it.lastRefreshedAt, it.autoRefreshHours, now) }
        for (p in stale) refresh(p.id)
    }

    // ---- edit / delete -------------------------------------------------------------------------

    override suspend fun rename(playlistId: Long, name: String) {
        val clean = name.trim().take(Limits.MAX_NAME_LENGTH)
        if (clean.isEmpty()) return
        withContext(Dispatchers.IO) { playlistDao.rename(playlistId, clean) }
    }

    override suspend fun setEpgUrls(playlistId: Long, urls: List<String>) {
        val valid = urls.mapNotNull { UrlValidator.validateSourceUrl(it).getOrNull() }
        withContext(Dispatchers.IO) { playlistDao.setEpgUrls(playlistId, joinUrls(valid), manual = valid.isNotEmpty()) }
        scheduleEpgRefresh(playlistId)
    }

    override suspend fun delete(playlistId: Long) {
        withContext(Dispatchers.IO) {
            importMutex.withLock {
                val playlist = playlistDao.get(playlistId) ?: return@withLock
                playlistDao.deleteFully(playlistId)
                playlist.credentialKey?.let { credentialStore.delete(it) }
                programDao.deleteOrphans()
            }
            if (preferences.preferences.first().activePlaylistId == playlistId) {
                preferences.update { it.copy(activePlaylistId = null) }
            }
        }
    }

    // ---- import plumbing -----------------------------------------------------------------------

    /** Serialises imports, maps unexpected exceptions to [AppError] and publishes progress. */
    private suspend fun runImport(
        playlistId: Long?,
        block: suspend () -> AppResult<ImportSummary>,
    ): AppResult<ImportSummary> = withContext(Dispatchers.IO) {
        importMutex.withLock {
            progress.value = ImportProgress.Running(playlistId, ImportProgress.Stage.Downloading)
            val result = try {
                block()
            } catch (e: CancellationException) {
                progress.value = ImportProgress.Idle
                throw e
            } catch (e: IOException) {
                AppResult.Err(AppError.Network(e.javaClass.simpleName))
            } catch (e: RuntimeException) {
                AppResult.Err(AppError.Unknown(e.javaClass.simpleName))
            }
            progress.value = when (result) {
                is AppResult.Ok -> ImportProgress.Done(result.value)
                is AppResult.Err -> ImportProgress.Failed(playlistId, result.error)
            }
            result
        }
    }

    /**
     * Runs the first import of a just-inserted playlist. If it fails (or is cancelled) the row and
     * its credentials are removed, so a failed "add" leaves nothing behind.
     */
    private suspend fun importNew(
        playlistId: Long,
        credentialKey: String?,
        load: suspend () -> AppResult<ImportSummary>,
    ): AppResult<ImportSummary> {
        var success = false
        try {
            val result = load()
            success = result is AppResult.Ok
            if (success) scheduleEpgRefresh(playlistId)
            return result
        } finally {
            if (!success) {
                withContext(NonCancellable) {
                    playlistDao.deleteFully(playlistId)
                    credentialKey?.let { credentialStore.delete(it) }
                }
            }
        }
    }

    private fun scheduleEpgRefresh(playlistId: Long) {
        // EPG problems never fail an import; the result is visible through the guide itself.
        appScope.launch { epg.refresh(playlistId) }
    }

    private suspend fun checkPlaylistLimit(): AppResult.Err? =
        if (playlistDao.count() >= entitlements().maxPlaylists) AppResult.Err(AppError.InvalidInput("playlist_limit")) else null

    private fun validateOptionalEpgUrl(epgUrl: String?): AppResult<String?> {
        val trimmed = epgUrl?.trim()
        if (trimmed.isNullOrEmpty()) return AppResult.Ok(null)
        return when (val r = UrlValidator.validateSourceUrl(trimmed)) {
            is AppResult.Ok -> AppResult.Ok(r.value)
            is AppResult.Err -> AppResult.Err(AppError.InvalidInput("epg_url"))
        }
    }

    // ---- M3U -----------------------------------------------------------------------------------
    //
    // M3U imports stream: the parser hands over batches of channels which are written to the
    // database as they arrive, so the full channel list of a (possibly 100k+ entries) playlist is
    // never held in memory. The old channels are deleted and the new ones inserted in ONE
    // transaction that is only opened once the first channel has been parsed; a playlist without
    // channels, a truncated download or a parse error leaves the previous channels untouched.

    private suspend fun downloadM3u(playlistId: Long, url: String): AppResult<ImportSummary> {
        progress.value = ImportProgress.Running(playlistId, ImportProgress.Stage.Downloading)
        return importM3u(playlistId) { sink ->
            // The download block runs synchronously on the IO thread; the sink blocks while the
            // database catches up, which also throttles the download.
            downloader.download(url, Limits.MAX_PLAYLIST_BYTES) { input -> sink(input) }.flatten()
        }
    }

    private suspend fun readM3uFile(playlistId: Long, uri: Uri): AppResult<ImportSummary> {
        progress.value = ImportProgress.Running(playlistId, ImportProgress.Stage.Downloading)
        return importM3u(playlistId) { sink ->
            val raw: InputStream? = try {
                contentResolver.openInputStream(uri)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null // Permission lost (file moved, app reinstalled).
            }
            if (raw == null) {
                AppResult.Err(AppError.InvalidInput("file"))
            } else {
                try {
                    raw.use { r ->
                        val limited = LimitedInputStream(r, Limits.MAX_PLAYLIST_BYTES)
                        sink(HttpDownloader.maybeGunzip(limited, Limits.MAX_PLAYLIST_BYTES))
                    }
                } catch (e: SizeLimitExceededException) {
                    AppResult.Err(AppError.TooLarge(e.limit))
                } catch (e: IOException) {
                    AppResult.Err(AppError.Parse(e.javaClass.simpleName))
                }
            }
        }
    }

    /**
     * Parses the stream handed to `sink` by [source] and saves the channels batch by batch.
     * [source] runs on the IO dispatcher and must call `sink` synchronously (at most once).
     */
    private suspend fun importM3u(
        playlistId: Long,
        source: suspend (sink: (InputStream) -> AppResult<M3uStreamSummary>) -> AppResult<M3uStreamSummary>,
    ): AppResult<ImportSummary> {
        // EPG mappings (manual overrides / name-resolved ids) of channels that still exist after the
        // refresh are carried over. Only ids with a mapping are kept, read inside the transaction.
        var kept: Map<String, ChannelMappingRow> = emptyMap()
        var saved = 0
        val outcome = importInBatches<List<Channel>, AppResult<M3uStreamSummary>>(
            transactions = transactions,
            produce = { emit -> source { input -> parseM3uStreaming(input, playlistId, emit) } },
            isFailure = { it is AppResult.Err },
            begin = {
                kept = channelDao.mappings(playlistId).associateBy { it.id }
                channelDao.deleteForPlaylist(playlistId)
            },
            save = { batch ->
                val entities = ArrayList<ChannelEntity>(batch.size)
                for (c in batch) {
                    if (saved >= Limits.MAX_CHANNELS_PER_PLAYLIST) break
                    val e = c.toEntity(saved).copy(playlistId = playlistId)
                    val m = kept[e.id]
                    entities += if (m == null) e else e.copy(manualEpgId = m.manualEpgId, resolvedEpgId = m.resolvedEpgId)
                    saved++
                }
                if (entities.isNotEmpty()) channelDao.insertAll(entities)
                progress.value = ImportProgress.Running(playlistId, ImportProgress.Stage.Saving, saved)
            },
        )
        val summary = when (val r = outcome.result) {
            is AppResult.Err -> return r // NoBatches or RolledBack: old channels untouched.
            is AppResult.Ok -> r.value
        }
        if (outcome !is BatchImportOutcome.Committed || saved == 0) return AppResult.Err(AppError.Empty)
        return finishM3u(playlistId, summary, saved)
    }

    /** Blocking; runs on the IO thread inside the download/file read. */
    private fun parseM3uStreaming(
        input: InputStream,
        playlistId: Long,
        emit: (List<Channel>) -> Unit,
    ): AppResult<M3uStreamSummary> {
        progress.value = ImportProgress.Running(playlistId, ImportProgress.Stage.Parsing)
        return try {
            AppResult.Ok(M3uParser().parseStreaming(input, playlistId, M3U_BATCH, emit))
        } catch (e: CancellationException) {
            throw e // The consumer gave up (error or cancellation); abort the parse.
        } catch (e: IllegalArgumentException) {
            AppResult.Err(AppError.Parse(e.javaClass.simpleName))
        } catch (e: IllegalStateException) {
            AppResult.Err(AppError.Parse(e.javaClass.simpleName))
        } catch (e: IndexOutOfBoundsException) {
            AppResult.Err(AppError.Parse(e.javaClass.simpleName))
        }
    }

    private suspend fun finishM3u(playlistId: Long, parsed: M3uStreamSummary, saved: Int): AppResult<ImportSummary> {
        val current = playlistDao.get(playlistId) ?: return AppResult.Err(AppError.InvalidInput("playlist"))
        val epgUrls = if (current.epgUrlsManual) {
            current.epgUrls
        } else {
            joinUrls(parsed.epgUrls.mapNotNull { UrlValidator.validateSourceUrl(it).getOrNull() })
        }
        playlistDao.update(current.copy(epgUrls = epgUrls, lastRefreshedAt = clock.nowMillis(), lastError = null))
        return AppResult.Ok(
            ImportSummary(
                playlistId = playlistId,
                channels = saved,
                groups = parsed.groupCount,
                skippedEntries = parsed.skipped,
                warnings = parsed.warnings.take(MAX_WARNINGS),
            ),
        )
    }

    // ---- Xtream --------------------------------------------------------------------------------

    private suspend fun authenticate(creds: XtreamCredentials): AppResult<XtreamAccount> =
        when (val auth = xtream.authenticate(creds)) {
            is AppResult.Err -> auth
            is AppResult.Ok ->
                if (auth.value.status.equals("expired", ignoreCase = true)) AppResult.Err(AppError.AccountExpired) else auth
        }

    private suspend fun refreshXtream(playlist: PlaylistEntity): AppResult<ImportSummary> {
        val creds = playlist.credentialKey?.let { credentialStore.get(it) } ?: return AppResult.Err(AppError.AuthFailed)
        progress.value = ImportProgress.Running(playlist.id, ImportProgress.Stage.Downloading)
        val account = when (val auth = authenticate(creds)) {
            is AppResult.Err -> return auth
            is AppResult.Ok -> auth.value
        }
        playlistDao.update(
            playlist.copy(serverTimezone = account.serverTimezone, allowedFormats = joinList(account.allowedOutputFormats)),
        )
        return loadXtream(playlist.id, creds)
    }

    /** Live channels are required; VOD failures only produce warnings and keep the old catalogue. */
    private suspend fun loadXtream(playlistId: Long, creds: XtreamCredentials): AppResult<ImportSummary> {
        progress.value = ImportProgress.Running(playlistId, ImportProgress.Stage.Downloading)
        val live = when (val r = xtream.liveStreams(creds, playlistId)) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        progress.value = ImportProgress.Running(playlistId, ImportProgress.Stage.Saving, live.size)
        val channels = toChannelEntities(playlistId, live)
        channelDao.replaceForPlaylist(playlistId, channels)

        val warnings = ArrayList<String>()
        progress.value = ImportProgress.Running(playlistId, ImportProgress.Stage.LoadingVod)
        var movieCount = 0
        val movieCategories = xtream.movieCategories(creds, playlistId)
        when (val movies = xtream.movies(creds, playlistId)) {
            is AppResult.Ok -> {
                val cats = movieCategories.getOrNull().orEmpty()
                if (movieCategories is AppResult.Err) warnings += "movie_categories_unavailable"
                val list = movies.value
                vodDao.replaceMovies(
                    playlistId,
                    cats.mapIndexed { i, c -> c.copy(playlistId = playlistId).toEntity(VodKinds.MOVIE, i) },
                    list.mapIndexed { i, m -> m.copy(playlistId = playlistId).toEntity(i) },
                )
                movieCount = list.size
            }
            is AppResult.Err -> warnings += "movies_unavailable"
        }
        progress.value = ImportProgress.Running(playlistId, ImportProgress.Stage.LoadingVod, movieCount)
        var seriesCount = 0
        val seriesCategories = xtream.seriesCategories(creds, playlistId)
        when (val series = xtream.series(creds, playlistId)) {
            is AppResult.Ok -> {
                val cats = seriesCategories.getOrNull().orEmpty()
                if (seriesCategories is AppResult.Err) warnings += "series_categories_unavailable"
                val list = series.value
                vodDao.replaceSeries(
                    playlistId,
                    cats.mapIndexed { i, c -> c.copy(playlistId = playlistId).toEntity(VodKinds.SERIES, i) },
                    list.mapIndexed { i, s -> s.copy(playlistId = playlistId).toEntity(i) },
                )
                seriesCount = list.size
            }
            is AppResult.Err -> warnings += "series_unavailable"
        }

        val current = playlistDao.get(playlistId) ?: return AppResult.Err(AppError.InvalidInput("playlist"))
        playlistDao.update(current.copy(lastRefreshedAt = clock.nowMillis(), lastError = null))
        return AppResult.Ok(
            ImportSummary(
                playlistId = playlistId,
                channels = channels.size,
                groups = channels.mapTo(HashSet()) { it.groupName }.size,
                movies = movieCount,
                series = seriesCount,
                warnings = warnings,
            ),
        )
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun toChannelEntities(playlistId: Long, channels: List<Channel>): List<ChannelEntity> =
        channels.asSequence()
            .take(Limits.MAX_CHANNELS_PER_PLAYLIST)
            .mapIndexed { i, c -> c.toEntity(i).copy(playlistId = playlistId) }
            .toList()

    private fun <T> AppResult<AppResult<T>>.flatten(): AppResult<T> = when (this) {
        is AppResult.Ok -> value
        is AppResult.Err -> this
    }

    private fun parseLocalUri(raw: String): Uri? {
        val uri = try {
            Uri.parse(raw.trim())
        } catch (e: RuntimeException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        return if (scheme == ContentResolver.SCHEME_CONTENT || scheme == ContentResolver.SCHEME_FILE) uri else null
    }

    private fun hostOf(url: String): String = try {
        URI(url).host ?: "Playlist"
    } catch (e: Exception) {
        "Playlist"
    }

    private fun cleanName(name: String, fallback: String): String =
        name.trim().take(Limits.MAX_NAME_LENGTH).ifEmpty { fallback.take(Limits.MAX_NAME_LENGTH) }

    private companion object {
        const val MAX_WARNINGS = 50

        /** Channels per parser batch / insert; bounds the memory of an M3U import. */
        const val M3U_BATCH = 500

        /** Short, credential-free error code stored on the playlist row. */
        fun describe(error: AppError): String = when (error) {
            is AppError.Http -> "http_${error.code}"
            is AppError.Network -> "network"
            is AppError.InvalidInput -> "invalid_input"
            is AppError.TooLarge -> "too_large"
            is AppError.Parse -> "parse"
            AppError.AuthFailed -> "auth_failed"
            AppError.AccountExpired -> "account_expired"
            AppError.Empty -> "empty"
            is AppError.Unknown -> "unknown"
        }
    }
}
