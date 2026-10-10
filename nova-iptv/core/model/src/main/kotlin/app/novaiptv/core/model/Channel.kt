package app.novaiptv.core.model

data class ChannelGroup(
    val playlistId: Long,
    val name: String,
    val channelCount: Int = 0,
)

data class Channel(
    /** Stable id within the app: "<playlistId>:<sourceKey>". */
    val id: String,
    val playlistId: Long,
    val number: Int,
    val name: String,
    val groupName: String?,
    val logoUrl: String?,
    /** XMLTV channel id used to match EPG data (tvg-id or Xtream epg_channel_id). */
    val epgId: String?,
    val stream: StreamSource,
    val catchup: CatchupCapability = CatchupCapability.None,
    val isAdult: Boolean = false,
    /** Extra M3U attributes not mapped to a field (kept for diagnostics / future use). */
    val extras: Map<String, String> = emptyMap(),
)

data class StreamSource(
    val url: String,
    /** Optional headers from #EXTVLCOPT / attributes (user-agent, referrer). */
    val userAgent: String? = null,
    val referrer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val mimeTypeHint: StreamFormat = StreamFormat.Unknown,
)

enum class StreamFormat { Hls, Dash, Progressive, MpegTs, Unknown }

/**
 * Archive support as declared by the source. Nothing here is invented: if the playlist or
 * portal does not declare catch-up, the channel has [None].
 */
sealed interface CatchupCapability {
    data object None : CatchupCapability

    data class Supported(
        val type: CatchupType,
        val days: Int,
        /** Template from M3U catchup-source, if any (may contain {utc}, {lutc}, ${start} ...). */
        val sourceTemplate: String? = null,
    ) : CatchupCapability {
        /**
         * Archive window used for UI and URL decisions. A source can declare catch-up without a
         * day count (days = 0); we then assume [UNKNOWN_WINDOW_DAYS] (the common 14-day archive of
         * IPTV providers) and let the server reject programmes outside its real window. Channels
         * that declare no catch-up at all stay [None]: no archive is ever invented for them.
         */
        val windowDays: Int get() = if (days > 0) days else UNKNOWN_WINDOW_DAYS

        companion object {
            const val UNKNOWN_WINDOW_DAYS = 14
        }
    }
}

enum class CatchupType { Default, Append, Shift, Flussonic, Xtream }
