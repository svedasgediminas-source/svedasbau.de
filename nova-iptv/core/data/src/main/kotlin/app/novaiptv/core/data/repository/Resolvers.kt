package app.novaiptv.core.data.repository

import app.novaiptv.core.data.db.PlaylistDao
import app.novaiptv.core.data.db.PlaylistTypes
import app.novaiptv.core.data.logic.CatchupWindow
import app.novaiptv.core.data.logic.StreamFormats
import app.novaiptv.core.data.logic.XtreamPlaceholders
import app.novaiptv.core.domain.CatchupResolver
import app.novaiptv.core.domain.CredentialStore
import app.novaiptv.core.domain.StreamResolver
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.StreamSource
import app.novaiptv.data.playlist.CatchupUrlBuilder
import app.novaiptv.data.xtream.XtreamUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Substitutes Xtream credential placeholders right before playback. Streams without
 * placeholders, or playlists without stored credentials, are returned unchanged.
 */
class StreamResolverImpl(
    private val playlistDao: PlaylistDao,
    private val credentials: CredentialStore,
) : StreamResolver {

    override suspend fun resolve(playlistId: Long, stream: StreamSource): StreamSource {
        if (!XtreamPlaceholders.contains(stream.url)) return stream
        return withContext(Dispatchers.IO) {
            val key = playlistDao.get(playlistId)?.credentialKey ?: return@withContext stream
            val creds = credentials.get(key) ?: return@withContext stream
            stream.copy(url = XtreamPlaceholders.substitute(stream.url, creds.username, creds.password))
        }
    }
}

class CatchupResolverImpl(
    private val playlistDao: PlaylistDao,
    private val streamResolver: StreamResolver,
) : CatchupResolver {

    override suspend fun resolve(channel: Channel, program: EpgProgram, nowMillis: Long): StreamSource? {
        val capability = channel.catchup as? CatchupCapability.Supported ?: return null
        if (!CatchupWindow.isAvailable(program.startMillis, capability.windowDays, nowMillis)) return null

        val playlist = withContext(Dispatchers.IO) { playlistDao.get(channel.playlistId) } ?: return null
        // catchup="xc" in an M3U playlist also maps to CatchupType.Xtream, but only real Xtream
        // playlists have a portal URL and stored credentials; M3U uses the template builder.
        val url = if (capability.type == CatchupType.Xtream && playlist.type == PlaylistTypes.XTREAM) {
            val streamId = channel.id.substringAfterLast(':')
            if (streamId.isEmpty()) return null
            XtreamUrls.timeshift(
                playlist.url,
                streamId,
                program.startMillis,
                CatchupWindow.durationMinutes(program.startMillis, program.endMillis),
                playlist.serverTimezone,
            )
        } else {
            CatchupUrlBuilder.build(channel, program, nowMillis) ?: return null
        }
        val stream = channel.stream.copy(url = url, mimeTypeHint = StreamFormats.fromUrl(url, channel.stream.mimeTypeHint))
        return streamResolver.resolve(channel.playlistId, stream)
    }
}
