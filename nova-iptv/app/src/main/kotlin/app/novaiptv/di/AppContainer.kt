package app.novaiptv.di

import androidx.compose.runtime.staticCompositionLocalOf
import app.novaiptv.core.domain.CatchupResolver
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.FavoritesRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.domain.StreamResolver
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.core.common.Clock
import kotlinx.coroutines.CoroutineScope

/**
 * Manual dependency container (no DI framework in the MVP). Created once in NovaApp and exposed
 * to Compose via [LocalAppContainer]. ViewModels receive the repositories they need through
 * `viewModel { FooViewModel(container.foo) }`.
 */
interface AppContainer {
    val playlists: PlaylistRepository
    val channels: ChannelRepository
    val epg: EpgRepository
    val vod: VodRepository
    val favorites: FavoritesRepository
    val history: HistoryRepository
    val preferences: PreferencesRepository
    val streamResolver: StreamResolver
    val catchup: CatchupResolver
    val clock: Clock

    /** The app-wide HTTP client (connection reuse for player and data layer). Never shut it down. */
    val okHttpClient: okhttp3.OkHttpClient

    /** Process-lifetime scope (SupervisorJob + Dispatchers.Default) for work that must outlive screens. */
    val appScope: CoroutineScope

    /** Runs playlist imports in [appScope] so leaving the add screen does not cancel them. */
    val imports: ImportTracker
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
