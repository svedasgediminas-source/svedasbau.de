package app.novaiptv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import app.novaiptv.core.common.net.HttpClients
import app.novaiptv.core.model.StreamUserAgent

/**
 * User-Agent strings for the "Stream User-Agent" player setting. Used only when the stream
 * itself does not specify one (see [StreamRequests.spec]); playlist / EPG downloads keep the
 * app's own agent. Unit-tested.
 */
@OptIn(UnstableApi::class)
object StreamUserAgents {
    const val VLC = "VLC/3.0.20 LibVLC/3.0.20"

    /** Media3's default agent (what a stock ExoPlayer app sends), e.g. "AndroidXMedia3/1.11.1". */
    val EXOPLAYER: String = MediaLibraryInfo.DEFAULT_USER_AGENT

    const val MAX_CUSTOM_LENGTH = 256

    /**
     * Agents to try in order. [StreamUserAgent.Auto]: the app agent, then VLC, then Media3's
     * default (generic, widely accepted player agents; no third-party app brands).
     */
    fun candidates(mode: StreamUserAgent, custom: String?, appDefault: String = HttpClients.USER_AGENT): List<String> =
        when (mode) {
            StreamUserAgent.Auto -> listOf(appDefault, VLC, EXOPLAYER).distinct()
            else -> listOf(resolve(mode, custom, appDefault))
        }

    fun resolve(
        mode: StreamUserAgent,
        custom: String?,
        appDefault: String = HttpClients.USER_AGENT,
        exoDefault: String = EXOPLAYER,
    ): String = when (mode) {
        StreamUserAgent.Auto, StreamUserAgent.AppDefault -> appDefault
        StreamUserAgent.Vlc -> VLC
        StreamUserAgent.ExoPlayer -> exoDefault
        StreamUserAgent.Custom -> sanitizeCustom(custom) ?: appDefault
    }

    /** Trimmed, single-line, printable ASCII, bounded; null when nothing usable remains. */
    fun sanitizeCustom(value: String?): String? {
        val v = value?.trim() ?: return null
        if (v.isEmpty() || v.length > MAX_CUSTOM_LENGTH) return null
        if (v.any { it.code < 0x20 || it.code > 0x7E }) return null
        return v
    }
}
