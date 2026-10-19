package app.novaiptv.core.player

import androidx.media3.common.PlaybackException
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.StreamSource
import java.util.Locale

/**
 * Pure stream-format decisions (JVM unit-tested, no I/O).
 *
 * Real IPTV links often carry no telling extension (`http://host:port/user/pass/123`,
 * `/live/u/p/123`, `play.php?id=…`). Without an explicit MIME type ExoPlayer treats them as
 * progressive media, so an HLS playlist served from such a URL fails with
 * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED. [StreamProber] fetches the first bytes and lets
 * [decide] pick the format; [fallbackFormat] gives one bounded second chance after a parsing error.
 */
object StreamProbe {

    /** Bytes requested by the probe (`Range: bytes=0-(PROBE_BYTES-1)`). */
    const val PROBE_BYTES = 2048

    private const val TS_SYNC = 0x47
    private const val TS_PACKET = 188

    /** Format known without any network access (playlist hint or URL extension); Unknown otherwise. */
    fun knownFormat(stream: StreamSource): StreamFormat =
        when (stream.mimeTypeHint) {
            StreamFormat.Unknown -> fromExtension(stream.url)
            else -> stream.mimeTypeHint
        }

    /** True when the format cannot be told from the stream itself and a probe is worthwhile. */
    fun needsProbe(stream: StreamSource): Boolean {
        if (knownFormat(stream) != StreamFormat.Unknown) return false
        val scheme = stream.url.substringBefore(':').lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https"
    }

    /**
     * Decides the format from what the server returned. Order: the first bytes (ground truth),
     * then the Content-Type, then the extension of the final (redirected) URL. Unknown when none
     * is conclusive. [body] may be empty (e.g. HTTP error): then only the URL is used.
     */
    fun decide(finalUrl: String?, contentType: String?, body: ByteArray): StreamFormat {
        fromBytes(body).takeIf { it != StreamFormat.Unknown }?.let { return it }
        fromContentType(contentType).takeIf { it != StreamFormat.Unknown }?.let { return it }
        return finalUrl?.let { fromExtension(it) } ?: StreamFormat.Unknown
    }

    fun fromExtension(url: String): StreamFormat {
        val path = url.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return StreamFormat.Unknown
        return when (last.substringAfterLast('.')) {
            "m3u8", "m3u" -> StreamFormat.Hls
            "mpd" -> StreamFormat.Dash
            "ts", "mts", "m2ts" -> StreamFormat.MpegTs
            "mp4", "m4v", "mkv", "avi", "mov", "webm", "flv", "mp3", "aac" -> StreamFormat.Progressive
            else -> StreamFormat.Unknown
        }
    }

    fun fromContentType(contentType: String?): StreamFormat {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (type) {
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
            "application/mpegurl", "vnd.apple.mpegurl",
            -> StreamFormat.Hls
            "application/dash+xml" -> StreamFormat.Dash
            "video/mp2t", "video/mpeg2-ts", "video/mpegts", "video/mpeg" -> StreamFormat.MpegTs
            "video/mp4", "video/x-matroska", "video/webm", "video/x-flv", "audio/mpeg", "audio/aac" -> StreamFormat.Progressive
            else -> StreamFormat.Unknown
        }
    }

    fun fromBytes(body: ByteArray): StreamFormat {
        if (body.isEmpty()) return StreamFormat.Unknown
        // MPEG-TS: sync byte at 0 (and at 188 when that much was read).
        if (body[0].toInt() and 0xFF == TS_SYNC &&
            (body.size <= TS_PACKET || body[TS_PACKET].toInt() and 0xFF == TS_SYNC)
        ) {
            return StreamFormat.MpegTs
        }
        // MP4 / fMP4: "ftyp" box at offset 4.
        if (body.size >= 8 && String(body, 4, 4, Charsets.ISO_8859_1) == "ftyp") return StreamFormat.Progressive
        val text = String(body, Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
        return when {
            text.startsWith("#EXTM3U") -> StreamFormat.Hls
            text.startsWith("<MPD") -> StreamFormat.Dash
            text.startsWith("<?xml") && text.contains("<MPD") -> StreamFormat.Dash
            else -> StreamFormat.Unknown
        }
    }

    /** The other container to try after a format error or stall: HLS <-> progressive (TS sniffing). */
    fun alternateFormat(current: StreamFormat): StreamFormat =
        if (current == StreamFormat.Hls) StreamFormat.MpegTs else StreamFormat.Hls

    fun isFormatError(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> true
        else -> false
    }

    /** Short tag for the error hint (no URL). */
    fun label(format: StreamFormat): String = when (format) {
        StreamFormat.Hls -> "HLS"
        StreamFormat.Dash -> "DASH"
        StreamFormat.MpegTs -> "TS"
        StreamFormat.Progressive -> "FILE"
        StreamFormat.Unknown -> "AUTO"
    }
}
