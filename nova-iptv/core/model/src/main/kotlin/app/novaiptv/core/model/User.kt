package app.novaiptv.core.model

enum class ContentType { Channel, Movie, Series, Episode }

data class Favorite(
    val contentType: ContentType,
    val contentId: String,
    val playlistId: Long,
    val addedAtMillis: Long,
    val sortOrder: Int = 0,
)

data class WatchHistory(
    val contentType: ContentType,
    val contentId: String,
    val playlistId: Long,
    val title: String,
    val imageUrl: String?,
    val positionMillis: Long,
    val durationMillis: Long,
    val lastWatchedAtMillis: Long,
    /** For episodes: the parent series id, to resume a series from its card. */
    val parentId: String? = null,
) {
    val progress: Float
        get() = if (durationMillis <= 0) 0f else (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)

    /** Considered finished above 95 % so it drops out of Continue Watching. */
    val isFinished: Boolean get() = durationMillis > 0 && progress >= 0.95f
}

enum class AppLanguage(val tag: String) { System(""), English("en"), Lithuanian("lt"), German("de"), Russian("ru") }

enum class AspectRatioMode { Fit, Fill, Zoom, Stretch }

/**
 * User-Agent sent with stream requests when the stream itself does not specify one
 * (#EXTVLCOPT / http-user-agent). Some providers only serve known players.
 * [Auto] starts with the app agent and switches to a widely accepted player agent when the
 * server refuses (401/403) or sends no data.
 */
enum class StreamUserAgent { Auto, AppDefault, Vlc, ExoPlayer, Custom }

data class UserPreferences(
    val language: AppLanguage = AppLanguage.System,
    val activePlaylistId: Long? = null,
    val aspectRatio: AspectRatioMode = AspectRatioMode.Fit,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val subtitlesEnabled: Boolean = false,
    val subtitleScale: Float = 1f,
    val showChannelNumbers: Boolean = true,
    val hideAdultContent: Boolean = true,
    val autoRefreshOnStart: Boolean = true,
    val epgTimeShiftMinutes: Int = 0,
    val bufferMillis: Int = 15_000,
    val streamUserAgent: StreamUserAgent = StreamUserAgent.Auto,
    /** Used when [streamUserAgent] is [StreamUserAgent.Custom]; blank falls back to the app default. */
    val customUserAgent: String = "",
)

/** Monetisation tier. Billing is not implemented in the MVP; everything runs as [Free] with limits relaxed. */
enum class Tier { Free, Premium }

data class Entitlements(val tier: Tier = Tier.Free) {
    val maxPlaylists: Int get() = if (tier == Tier.Premium) Int.MAX_VALUE else MVP_PLAYLIST_LIMIT

    companion object {
        /** MVP keeps multiple playlists available while billing does not exist. */
        const val MVP_PLAYLIST_LIMIT = 10
    }
}
