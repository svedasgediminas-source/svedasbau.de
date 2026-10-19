package app.novaiptv.core.player

import app.novaiptv.core.model.StreamFormat

/**
 * Bounded recovery ladder for one stream start (pure, JVM unit-tested).
 *
 * Real providers fail in ways that ExoPlayer alone reports badly: an HLS playlist behind an
 * extension-less URL (parse error), a server that answers 403 or simply sends nothing to an
 * unknown User-Agent (endless buffering), or a panel whose `/user/pass/ID.ts` path stalls
 * while `/live/user/pass/ID.m3u8` works. [attempts] lists every variant to try, in order, and
 * [next] picks the following one for a failure. The list is finite and the index only moves
 * forward, so recovery can never loop.
 */
object PlaybackRecovery {

    data class Attempt(val url: String, val format: StreamFormat, val uaIndex: Int)

    enum class Failure {
        /** Buffering without progress, but bytes did arrive (container / extractor problem). */
        Stall,
        /** Buffering and no bytes at all (server withholding data, typically UA-based). */
        NoData,
        /** HTTP 401 / 403. */
        Forbidden,
        /** Parsing / unrecognised container. */
        Format,
        /** Anything else (only advances once the ladder has started). */
        Other,
    }

    const val MAX_ATTEMPTS = 8

    /**
     * Attempts for [url] played as [initialFormat] with [uaCount] User-Agent candidates.
     * [xtreamVariants] adds the HLS path variants of Xtream-style live URLs.
     */
    fun attempts(url: String, initialFormat: StreamFormat, uaCount: Int, xtreamVariants: Boolean): List<Attempt> {
        val out = ArrayList<Attempt>()
        out += Attempt(url, initialFormat, 0)
        out += Attempt(url, StreamProbe.alternateFormat(initialFormat), 0)
        if (xtreamVariants) hlsVariants(url).forEach { out += Attempt(it, StreamFormat.Hls, 0) }
        // Another agent may get a different answer (e.g. a playlist instead of a 403): both containers.
        for (ua in 1 until uaCount.coerceAtLeast(1)) {
            out += Attempt(url, initialFormat, ua)
            out += Attempt(url, StreamProbe.alternateFormat(initialFormat), ua)
        }
        return out.distinct().take(MAX_ATTEMPTS)
    }

    /** Index of the next attempt after [current] for [failure], or null to give up. */
    fun next(attempts: List<Attempt>, current: Int, failure: Failure): Int? {
        if (current !in attempts.indices) return null
        val ua = attempts[current].uaIndex
        val nextUa = (current + 1 until attempts.size).firstOrNull { attempts[it].uaIndex > ua }
        return when (failure) {
            // The server refuses this client: only another User-Agent can help.
            Failure.Forbidden -> nextUa
            // Nothing arrives at all: another User-Agent first, else keep walking the list.
            Failure.NoData -> nextUa ?: (current + 1).takeIf { it < attempts.size }
            Failure.Stall, Failure.Format -> (current + 1).takeIf { it < attempts.size }
            // A plain error on the original attempt is handled by the normal retry policy.
            Failure.Other -> if (current == 0) null else (current + 1).takeIf { it < attempts.size }
        }
    }

    private val XTREAM_LIVE = Regex("""^(https?://[^/?#]+)(/[^?#]*?)??(/live)?/([^/?#]+)/([^/?#]+)/(\d+)(\.ts|\.m3u8)?(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val NON_LIVE_PREFIXES = listOf("/movie", "/series", "/timeshift", "/vod")

    /**
     * HLS variants of an Xtream-style live URL (`/user/pass/ID[.ts]` or `/live/user/pass/ID[.ts]`):
     * `/live/user/pass/ID.m3u8` and `/user/pass/ID.m3u8`, minus the original. Empty otherwise.
     */
    fun hlsVariants(url: String): List<String> {
        val m = XTREAM_LIVE.matchEntire(url) ?: return emptyList()
        val (origin, prefixRaw, _, user, pass, id, ext) = m.destructured
        if (ext.equals(".m3u8", ignoreCase = true)) return emptyList()
        val prefix = prefixRaw.trimEnd('/')
        if (NON_LIVE_PREFIXES.any { prefix.endsWith(it, ignoreCase = true) }) return emptyList()
        if (user.contains('.') || pass.equals("live", ignoreCase = true)) return emptyList()
        return listOf(
            "$origin$prefix/live/$user/$pass/$id.m3u8",
            "$origin$prefix/$user/$pass/$id.m3u8",
        ).filter { it != url }.distinct()
    }
}
