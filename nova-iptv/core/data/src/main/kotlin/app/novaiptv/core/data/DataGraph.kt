package app.novaiptv.core.data

import android.content.Context
import android.util.Xml
import app.novaiptv.core.common.Clock
import app.novaiptv.core.common.net.HttpClients
import app.novaiptv.core.common.net.HttpDownloader
import app.novaiptv.core.data.db.NovaDatabase
import app.novaiptv.core.data.importer.RoomTransactionRunner
import app.novaiptv.core.data.repository.CatchupResolverImpl
import app.novaiptv.core.data.repository.ChannelRepositoryImpl
import app.novaiptv.core.data.repository.EpgRepositoryImpl
import app.novaiptv.core.data.repository.FavoritesRepositoryImpl
import app.novaiptv.core.data.repository.HistoryRepositoryImpl
import app.novaiptv.core.data.repository.PlaylistRepositoryImpl
import app.novaiptv.core.data.repository.PreferencesRepositoryImpl
import app.novaiptv.core.data.repository.StreamResolverImpl
import app.novaiptv.core.data.repository.VodRepositoryImpl
import app.novaiptv.core.data.security.CredentialStoreImpl
import app.novaiptv.core.domain.CatchupResolver
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.CredentialStore
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.FavoritesRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.domain.StreamResolver
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.data.xtream.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Composition root of the data layer. Create exactly one instance per process (e.g. in the
 * Application) — the database, DataStore and OkHttpClient are process-wide singletons.
 * Everything is created lazily on first use; no I/O happens in the constructor.
 */
class DataGraph(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Background work that must outlive a screen (EPG refresh after an import). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val clock: Clock = Clock.System

    val okHttpClient: OkHttpClient by lazy { HttpClients.create() }

    private val database: NovaDatabase by lazy { NovaDatabase.create(appContext) }
    private val downloader: HttpDownloader by lazy { HttpDownloader(okHttpClient) }
    private val xtreamClient: XtreamClient by lazy { XtreamClient(okHttpClient) }

    val credentials: CredentialStore by lazy { CredentialStoreImpl(appContext) }

    val preferences: PreferencesRepository by lazy { PreferencesRepositoryImpl(appContext) }

    val streamResolver: StreamResolver by lazy { StreamResolverImpl(database.playlistDao(), credentials) }

    val catchup: CatchupResolver by lazy { CatchupResolverImpl(database.playlistDao(), streamResolver) }

    val epg: EpgRepository by lazy {
        EpgRepositoryImpl(
            playlistDao = database.playlistDao(),
            channelDao = database.channelDao(),
            programDao = database.programDao(),
            downloader = downloader,
            xtream = xtreamClient,
            credentials = credentials,
            preferences = preferences,
            clock = clock,
            parserFactory = { Xml.newPullParser() },
        )
    }

    private val playlistRepository: PlaylistRepositoryImpl by lazy {
        PlaylistRepositoryImpl(
            contentResolver = appContext.contentResolver,
            playlistDao = database.playlistDao(),
            channelDao = database.channelDao(),
            vodDao = database.vodDao(),
            programDao = database.programDao(),
            downloader = downloader,
            xtream = xtreamClient,
            credentialStore = credentials,
            preferences = preferences,
            epg = epg,
            clock = clock,
            appScope = appScope,
            transactions = RoomTransactionRunner(database),
        )
    }

    val playlists: PlaylistRepository get() = playlistRepository

    val channels: ChannelRepository by lazy { ChannelRepositoryImpl(database.channelDao()) }

    val vod: VodRepository by lazy {
        VodRepositoryImpl(database.vodDao(), database.playlistDao(), credentials, xtreamClient)
    }

    val favorites: FavoritesRepository by lazy { FavoritesRepositoryImpl(database.favoriteDao(), clock) }

    val history: HistoryRepository by lazy { HistoryRepositoryImpl(database.historyDao()) }

    /** Re-imports playlists whose auto-refresh interval has elapsed (and their EPG). Never throws. */
    suspend fun refreshStalePlaylists() {
        playlistRepository.refreshStale()
    }
}
