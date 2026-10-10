package app.novaiptv.core.model

/** What the player is asked to play. */
sealed interface PlaybackRequest {
    val title: String
    val stream: StreamSource

    data class Live(val channel: Channel) : PlaybackRequest {
        override val title get() = channel.name
        override val stream get() = channel.stream
    }

    data class Catchup(val channel: Channel, val program: EpgProgram, override val stream: StreamSource) : PlaybackRequest {
        override val title get() = "${channel.name} · ${program.title}"
    }

    data class Vod(
        val contentType: ContentType,
        val contentId: String,
        val playlistId: Long,
        override val title: String,
        override val stream: StreamSource,
        val imageUrl: String?,
        val startPositionMillis: Long = 0,
        val parentId: String? = null,
    ) : PlaybackRequest
}

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Buffering : PlaybackState
    data class Playing(val positionMillis: Long, val durationMillis: Long, val isLive: Boolean) : PlaybackState
    data class Paused(val positionMillis: Long, val durationMillis: Long) : PlaybackState
    data object Ended : PlaybackState
    data class Retrying(val attempt: Int, val maxAttempts: Int) : PlaybackState
    data class Error(val kind: PlaybackErrorKind, val message: String?) : PlaybackState
}

enum class PlaybackErrorKind { Network, SourceUnavailable, UnsupportedFormat, DrmProtected, Decoder, Unknown }

data class TrackOption(val id: String, val label: String, val language: String?, val selected: Boolean)
