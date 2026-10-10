package app.novaiptv.data.playlist

import app.novaiptv.core.common.Limits
import app.novaiptv.core.common.Redact
import app.novaiptv.core.common.UrlValidator
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.StreamSource
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLDecoder
import java.security.MessageDigest

data class M3uParseResult(
    val channels: List<Channel>,
    /** From #EXTM3U url-tvg / x-tvg-url / tvg-url (comma separated allowed). Only valid http(s) URLs. */
    val epgUrls: List<String>,
    val skipped: Int,
    /** Human readable, capped at ~50 entries, credentials redacted. */
    val warnings: List<String>,
)

/**
 * Streaming M3U / M3U8 (extended M3U) parser.
 *
 * - Reads line by line through a bounded line reader; the file is never held in memory.
 * - Malformed content never throws: bad entries are skipped and reported in warnings.
 * - I/O errors of the underlying stream (e.g. a truncated download) DO propagate as
 *   [java.io.IOException] so the caller (HttpDownloader) can map them to a network error
 *   instead of silently importing half a playlist.
 *
 * Catch-up: [CatchupCapability.Supported] is produced only when the source declares it (a
 * `catchup`/`catchup-type` mode on the channel or the #EXTM3U header, or a `catchup-source`
 * template). `catchup-days`/`timeshift`/`tvg-rec` alone do not enable archive. When a mode is
 * declared but no day count is known, `days = 0` meaning "unknown window": the builder will
 * still try and the server decides.
 */
class M3uParser {

    /** Streaming, line-by-line; never loads the whole file in memory. Tolerates malformed entries. */
    fun parse(input: java.io.InputStream, playlistId: Long, onProgress: ((parsedChannels: Int) -> Unit)? = null): M3uParseResult =
        Session(playlistId, onProgress).run(input)

    private class Header(
        val catchup: String? = null,
        val catchupDays: Int? = null,
        val catchupSource: String? = null,
    )

    private class Pending(
        val lineNumber: Int,
        val attributes: Map<String, String>,
        val title: String?,
    )

    private class Session(
        private val playlistId: Long,
        private val onProgress: ((Int) -> Unit)?,
    ) {
        private val channels = ArrayList<Channel>()
        private val epgUrls = LinkedHashSet<String>()
        private val warnings = ArrayList<String>()
        private var suppressedWarnings = 0
        private var skipped = 0
        private var header = Header()
        private var sawHeader = false
        private var sawExtinf = false
        private var orphanLines = 0
        private val seenIds = HashMap<String, Int>()
        private val sha1 = MessageDigest.getInstance("SHA-1")

        // Per-entry state, reset after each URL line.
        private var pending: Pending? = null
        private var extGrp: String? = null
        private var vlcUserAgent: String? = null
        private var vlcReferrer: String? = null
        private val vlcHeaders = LinkedHashMap<String, String>()

        fun run(input: InputStream): M3uParseResult {
            val reader = M3uLineReader(InputStreamReader(input, Charsets.UTF_8), Limits.MAX_LINE_LENGTH)
            var first = true
            var limitReached = false
            while (true) {
                val raw = reader.readLine() ?: break
                val ln = reader.lineNumber
                if (reader.lastLineTooLong) {
                    warn("Line $ln ignored: longer than ${Limits.MAX_LINE_LENGTH} characters")
                    continue
                }
                var line = raw
                if (first) {
                    line = line.removePrefix("﻿")
                }
                line = line.trim()
                if (line.isEmpty()) continue
                if (first) {
                    first = false
                    if (line.startsWith("#EXTM3U", ignoreCase = true)) {
                        sawHeader = true
                        parseHeader(line)
                        continue
                    }
                }
                if (line.startsWith("#")) {
                    handleDirective(line, ln)
                    continue
                }
                // URL line.
                val p = pending
                if (p == null) {
                    orphanLines++
                    resetEntry()
                    continue
                }
                if (channels.size >= Limits.MAX_CHANNELS_PER_PLAYLIST) {
                    limitReached = true
                    break
                }
                buildChannel(p, line)
                resetEntry()
            }
            if (pending != null) {
                skipped++
                warn("Line ${pending!!.lineNumber}: #EXTINF without stream URL at end of file")
            }
            if (limitReached) {
                warn("Channel limit of ${Limits.MAX_CHANNELS_PER_PLAYLIST} reached; remaining entries ignored")
            }
            if (!sawHeader) {
                if (sawExtinf) warn("File does not start with #EXTM3U; parsed anyway")
                else warn("Not an M3U playlist (no #EXTM3U header and no #EXTINF entries)")
            } else if (channels.isEmpty() && skipped == 0) {
                warn("Playlist contains no channels")
            }
            if (orphanLines > 0 && sawExtinf) {
                warn("$orphanLines line(s) without a preceding #EXTINF were ignored")
            }
            if (suppressedWarnings > 0) warnings.add("… and $suppressedWarnings more warning(s)")
            onProgress?.invoke(channels.size)
            return M3uParseResult(channels, epgUrls.toList(), skipped, warnings)
        }

        private fun warn(msg: String) {
            if (warnings.size < MAX_WARNINGS) warnings.add(msg) else suppressedWarnings++
        }

        private fun resetEntry() {
            pending = null
            extGrp = null
            vlcUserAgent = null
            vlcReferrer = null
            vlcHeaders.clear()
        }

        private fun parseHeader(line: String) {
            val attrs = M3uAttributeParser.parse(line, "#EXTM3U".length).attributes
            for (key in EPG_KEYS) {
                attrs[key]?.split(',')?.forEach { u ->
                    val url = u.trim()
                    if (url.isNotEmpty()) {
                        if (UrlValidator.validateSourceUrl(url) is app.novaiptv.core.common.AppResult.Ok) epgUrls.add(url)
                        else warn("Ignored invalid EPG URL in header: ${Redact.url(url.take(200))}")
                    }
                }
            }
            header = Header(
                catchup = attrs["catchup"] ?: attrs["catchup-type"],
                catchupDays = positiveInt(attrs["catchup-days"]) ?: positiveInt(attrs["timeshift"]) ?: positiveInt(attrs["tvg-rec"]),
                catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
            )
        }

        private fun handleDirective(line: String, ln: Int) {
            val upper = line.take(12).uppercase()
            when {
                upper.startsWith("#EXTINF") -> {
                    sawExtinf = true
                    if (pending != null) {
                        skipped++
                        warn("Line ${pending!!.lineNumber}: #EXTINF without stream URL skipped")
                    }
                    val colon = line.indexOf(':')
                    if (colon < 0) {
                        pending = Pending(ln, emptyMap(), null)
                        return
                    }
                    // Skip the duration token.
                    var i = colon + 1
                    while (i < line.length && line[i].isWhitespace()) i++
                    while (i < line.length && !line[i].isWhitespace() && line[i] != ',') i++
                    val parsed = M3uAttributeParser.parse(line, i)
                    pending = Pending(ln, parsed.attributes, parsed.title)
                }
                upper.startsWith("#EXTGRP:") -> extGrp = line.substring(8).trim().takeIf { it.isNotEmpty() }
                upper.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.substring(11).trim()
                    val eq = opt.indexOf('=')
                    if (eq > 0) {
                        val key = opt.substring(0, eq).trim().lowercase()
                        val value = opt.substring(eq + 1).trim()
                        if (value.isNotEmpty()) when (key) {
                            "http-user-agent" -> vlcUserAgent = value
                            "http-referrer", "http-referer" -> vlcReferrer = value
                            "http-origin" -> vlcHeaders["Origin"] = value
                        }
                    }
                }
                // #KODIPROP (inputstream / DRM properties), #EXT-X-*, #PLAYLIST and plain comments: ignored.
                else -> Unit
            }
        }

        private fun buildChannel(p: Pending, rawUrlLine: String) {
            val (url, pipeHeaders) = splitKodiHeaders(rawUrlLine)
            if (!UrlValidator.isValidStreamUrl(url)) {
                skipped++
                warn("Line ${p.lineNumber}: invalid stream URL skipped (${Redact.url(url.take(120))})")
                return
            }
            val a = p.attributes
            val index = channels.size + 1
            val tvgName = a["tvg-name"]?.trim()?.takeIf { it.isNotEmpty() }
            val name = clip(p.title?.takeIf { it.isNotEmpty() } ?: tvgName ?: "Channel $index")
            val group = (a["group-title"]?.trim()?.takeIf { it.isNotEmpty() } ?: extGrp)?.let(::clip)
            val epgId = a["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() }
            val logo = (a["tvg-logo"] ?: a["logo"])?.trim()?.takeIf {
                it.isNotEmpty() && UrlValidator.validateSourceUrl(it) is app.novaiptv.core.common.AppResult.Ok
            }
            val number = a["tvg-chno"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: index

            var userAgent = a["user-agent"] ?: a["http-user-agent"]
            var referrer = a["http-referrer"] ?: a["referrer"] ?: a["referer"] ?: a["http-referer"]
            val headers = LinkedHashMap<String, String>()
            vlcUserAgent?.let { userAgent = it }
            vlcReferrer?.let { referrer = it }
            headers.putAll(vlcHeaders)
            for ((k, v) in pipeHeaders) {
                when (k.lowercase()) {
                    "user-agent" -> userAgent = v
                    "referer", "referrer" -> referrer = v
                    else -> headers[k] = v
                }
            }

            val stream = StreamSource(
                url = url,
                userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() },
                referrer = referrer?.trim()?.takeIf { it.isNotEmpty() },
                headers = headers,
                mimeTypeHint = formatOf(url),
            )
            val extras = LinkedHashMap<String, String>()
            for ((k, v) in a) {
                if (k in KNOWN_KEYS) continue
                if (extras.size >= MAX_EXTRAS) break
                extras[k] = v.take(MAX_EXTRA_VALUE)
            }
            tvgName?.let { extras["tvg-name"] = it.take(MAX_EXTRA_VALUE) }

            channels.add(
                Channel(
                    id = "$playlistId:${stableKey(epgId, url, name)}",
                    playlistId = playlistId,
                    number = number,
                    name = name,
                    groupName = group,
                    logoUrl = logo,
                    epgId = epgId,
                    stream = stream,
                    catchup = catchupOf(a),
                    isAdult = isAdult(group, name),
                    extras = extras,
                ),
            )
            if (onProgress != null && channels.size % PROGRESS_EVERY == 0) onProgress.invoke(channels.size)
        }

        private fun catchupOf(a: Map<String, String>): CatchupCapability {
            val modeRaw = (a["catchup"] ?: a["catchup-type"])?.trim() ?: header.catchup?.trim()
            val source = a["catchup-source"]?.trim()?.takeIf { it.isNotEmpty() } ?: header.catchupSource
            val type: CatchupType = when {
                modeRaw != null -> mapCatchupType(modeRaw) ?: return CatchupCapability.None
                source != null -> CatchupType.Default
                else -> return CatchupCapability.None
            }
            val days = positiveInt(a["catchup-days"]) ?: positiveInt(a["timeshift"]) ?: positiveInt(a["tvg-rec"])
                ?: header.catchupDays ?: 0
            return CatchupCapability.Supported(type = type, days = days, sourceTemplate = source)
        }

        private fun stableKey(epgId: String?, url: String, name: String): String {
            sha1.reset()
            sha1.update((epgId ?: "").toByteArray(Charsets.UTF_8))
            sha1.update(0)
            sha1.update(url.toByteArray(Charsets.UTF_8))
            sha1.update(0)
            sha1.update(name.toByteArray(Charsets.UTF_8))
            val digest = sha1.digest()
            val sb = StringBuilder(16)
            for (i in 0 until 8) {
                val b = digest[i].toInt() and 0xff
                sb.append(HEX[b ushr 4]).append(HEX[b and 0xf])
            }
            val base = sb.toString()
            // Identical entries repeated in one file get a deterministic ordinal suffix.
            val seen = seenIds[base]
            return if (seen == null) {
                seenIds[base] = 1
                base
            } else {
                seenIds[base] = seen + 1
                "$base-$seen"
            }
        }
    }

    companion object {
        private const val MAX_WARNINGS = 50
        private const val MAX_EXTRAS = 32
        private const val MAX_EXTRA_VALUE = 1024
        private const val PROGRESS_EVERY = 1000
        private val HEX = "0123456789abcdef".toCharArray()
        private val EPG_KEYS = listOf("url-tvg", "x-tvg-url", "tvg-url")
        private val KNOWN_KEYS = setOf(
            "tvg-id", "tvg-name", "tvg-logo", "logo", "tvg-chno", "group-title",
            "catchup", "catchup-type", "catchup-days", "catchup-source", "timeshift", "tvg-rec",
            "user-agent", "http-user-agent", "http-referrer", "http-referer", "referrer", "referer",
        )
        private val ADULT = Regex("""(?i)(xxx|\badult|18\s*\+|\bporn|\bfor adults)""")
        private val HEADER_NAME = Regex("""^[A-Za-z0-9-]{1,64}$""")

        internal fun mapCatchupType(raw: String): CatchupType? = when (raw.trim().lowercase()) {
            "default" -> CatchupType.Default
            "append" -> CatchupType.Append
            "shift", "timeshift" -> CatchupType.Shift
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> CatchupType.Flussonic
            "xc" -> CatchupType.Xtream
            else -> null
        }

        private fun positiveInt(s: String?): Int? = s?.trim()?.toIntOrNull()?.takeIf { it > 0 }

        private fun clip(s: String): String = if (s.length <= Limits.MAX_NAME_LENGTH) s else s.take(Limits.MAX_NAME_LENGTH)

        internal fun isAdult(group: String?, name: String): Boolean {
            fun hit(s: String?) = s != null && ADULT.containsMatchIn(s) && !s.contains("adult swim", ignoreCase = true)
            return hit(group) || hit(name)
        }

        internal fun formatOf(url: String): StreamFormat {
            val cut = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
            val path = url.substring(0, cut).lowercase()
            val query = url.substring(cut).lowercase()
            return when {
                path.endsWith(".m3u8") -> StreamFormat.Hls
                path.endsWith(".mpd") -> StreamFormat.Dash
                path.endsWith(".ts") -> StreamFormat.MpegTs
                path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") -> StreamFormat.Progressive
                query.contains("output=m3u8") || query.contains("extension=m3u8") -> StreamFormat.Hls
                query.contains("output=ts") || query.contains("output=mpegts") -> StreamFormat.MpegTs
                else -> StreamFormat.Unknown
            }
        }

        /** Splits Kodi style `url|User-Agent=x&Referer=y` into the URL and its header map. */
        internal fun splitKodiHeaders(raw: String): Pair<String, Map<String, String>> {
            val idx = raw.indexOf('|')
            if (idx < 0) return raw.trim() to emptyMap()
            val url = raw.substring(0, idx).trim()
            val out = LinkedHashMap<String, String>()
            for (kv in raw.substring(idx + 1).split('&')) {
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                val key = kv.substring(0, eq).trim()
                if (!HEADER_NAME.matches(key)) continue
                var value = kv.substring(eq + 1).trim()
                if (value.contains('%')) {
                    value = try {
                        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
                    } catch (e: IllegalArgumentException) {
                        value
                    }
                }
                if (value.isNotEmpty() && value.none { it == '\r' || it == '\n' }) out[key] = value
            }
            return url to out
        }
    }
}
