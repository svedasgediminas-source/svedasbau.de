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
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
