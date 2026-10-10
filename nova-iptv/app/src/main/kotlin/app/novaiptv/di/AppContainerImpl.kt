package app.novaiptv.di

import app.novaiptv.core.common.Clock
import app.novaiptv.core.data.DataGraph
import app.novaiptv.core.domain.CatchupResolver
import app.novaiptv.core.domain.ChannelRepository
import app.novaiptv.core.domain.EpgRepository
import app.novaiptv.core.domain.FavoritesRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.domain.StreamResolver
import app.novaiptv.core.domain.VodRepository

/** [AppContainer] backed by the data layer's object graph. */
class AppContainerImpl(private val graph: DataGraph) : AppContainer {
    override val playlists: PlaylistRepository get() = graph.playlists
    override val channels: ChannelRepository get() = graph.channels
    override val epg: EpgRepository get() = graph.epg
    override val vod: VodRepository get() = graph.vod
    override val favorites: FavoritesRepository get() = graph.favorites
    override val history: HistoryRepository get() = graph.history
    override val preferences: PreferencesRepository get() = graph.preferences
    override val streamResolver: StreamResolver get() = graph.streamResolver
    override val catchup: CatchupResolver get() = graph.catchup
    override val clock: Clock get() = graph.clock
}
