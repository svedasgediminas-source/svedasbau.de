package app.novaiptv.core.player

import androidx.media3.common.MimeTypes
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.StreamSource
import java.util.Locale

/** Pure helpers that turn a [StreamSource] into request parameters. Unit-tested. */
object StreamRequests {

    data class Spec(val userAgent: String, val headers: Map<String, String>)

    /**
     * User agent precedence: [StreamSource.userAgent], then a "User-Agent" entry in
     * [StreamSource.headers], then [defaultUserAgent]. The referrer becomes a "Referer" header
     * unless one is already present. Headers with CR/LF or blank names are dropped.
     */
    fun spec(stream: StreamSource, defaultUserAgent: String): Spec {
        var userAgent = stream.userAgent?.takeIf { it.isNotBlank() && it.isSafeHeaderValue() }
        val headers = LinkedHashMap<String, String>()
        for ((name, value) in stream.headers) {
            val key = name.trim()
            if (key.isEmpty() || !key.isSafeHeaderValue() || !value.isSafeHeaderValue()) continue
            if (key.equals("User-Agent", ignoreCase = true)) {
                if (userAgent == null && value.isNotBlank()) userAgent = value
            } else {
                headers[key] = value
            }
        }
        val referrer = stream.referrer?.takeIf { it.isNotBlank() && it.isSafeHeaderValue() }
        if (referrer != null && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            headers["Referer"] = referrer
        }
        return Spec(userAgent ?: defaultUserAgent, headers)
    }

    /** Explicit MIME type for adaptive formats; null lets ExoPlayer infer/sniff (TS, MP4, ...). */
    fun mimeTypeFor(stream: StreamSource): String? = when (stream.mimeTypeHint) {
        StreamFormat.Hls -> MimeTypes.APPLICATION_M3U8
        StreamFormat.Dash -> MimeTypes.APPLICATION_MPD
        else -> {
            val path = stream.url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            when {
                path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
    }

    private fun String.isSafeHeaderValue(): Boolean = none { it == '\r' || it == '\n' || it == '\u0000' }
}

/** Human-readable track labels. Unit-tested. */
object TrackLabels {

    /** Returns "" when nothing useful is known; the UI substitutes a localized "Track N". */
    fun label(label: String?, language: String?, channelCount: Int, locale: Locale = Locale.getDefault()): String {
        val base = label?.trim()?.takeIf { it.isNotEmpty() }
            ?: language?.let { displayLanguage(it, locale) }
            ?: ""
        val channels = when (channelCount) {
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        return listOfNotNull(base.takeIf { it.isNotEmpty() }, channels).joinToString(" ")
    }

    fun displayLanguage(tag: String, locale: Locale = Locale.getDefault()): String? {
        val clean = tag.trim()
        if (clean.isEmpty() || clean.equals("und", ignoreCase = true)) return null
        val name = Locale.forLanguageTag(clean).getDisplayLanguage(locale)
        val result = name.ifBlank { clean }
        return result.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}

/** When to resume VOD automatically. Unit-tested. */
object ResumePolicy {
    const val MIN_RESUME_MS = 5_000L
    const val MAX_PROGRESS = 0.95

    /** Saved position if 5 s < position < 95 % of duration, otherwise 0 (start from beginning). */
    fun resumePositionMillis(positionMillis: Long, durationMillis: Long): Long =
        if (durationMillis > 0 && positionMillis > MIN_RESUME_MS && positionMillis < durationMillis * MAX_PROGRESS) {
            positionMillis
        } else {
            0L
        }
}
