package app.novaiptv.data.epg

import app.novaiptv.core.model.EpgChannel
import app.novaiptv.core.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

/** Outcome of one XMLTV parse. [error] is set when the document ended abnormally (truncated / malformed). */
data class XmltvStats(val channels: Int, val programs: Int, val skipped: Int, val error: String? = null)

/**
 * Streaming XMLTV parser on top of XmlPullParser (android.util.Xml.newPullParser() on Android,
 * kXML2 on the JVM).
 *
 * - Only one `<channel>` / `<programme>` is held in memory at a time, plus at most one pending
 *   programme per channel whose `stop` is missing (see below).
 * - Malformed programmes (missing channel/title, bad times, end <= start) are skipped and counted.
 * - Missing `stop`: the programme is held back (one-element lookahead per channel) and ends at
 *   the start of the next programme of the same channel, if that is later and within
 *   [MAX_INFERRED_DURATION_MILLIS]; otherwise (including the last one of a channel) it is skipped.
 *   Programmes with stop times are emitted immediately, so emission order can differ from document
 *   order only for those inferred programmes.
 * - XXE: DOCTYPE processing is disabled where supported and external entities are never resolved
 *   (XmlPullParser has no entity resolver at all). Unknown entities are left as literal text
 *   in relaxed mode rather than failing the whole feed.
 * - A truncated/malformed document does not throw: everything parsed so far has been delivered
 *   and [XmltvStats.error] is set. Delivery lags one element behind parsing so that the element
 *   being parsed when the input ended (possibly auto-closed by a relaxed parser) is dropped
 *   (counted as skipped if it was a programme). Exceptions thrown by the callbacks propagate.
 */
class XmltvParser(private val parserFactory: () -> XmlPullParser) {

    fun parse(
        input: InputStream,
        onChannel: (EpgChannel) -> Unit,
        onProgram: (EpgProgram) -> Unit,
        timeShiftMinutes: Int = 0,
    ): XmltvStats = Session(onChannel, onProgram, timeShiftMinutes * 60_000L).run(input)

    private inner class Session(
        private val onChannel: (EpgChannel) -> Unit,
        private val onProgram: (EpgProgram) -> Unit,
        private val shiftMillis: Long,
    ) {
        var channels = 0
        var programs = 0
        var skipped = 0
        private val pendingNoStop = HashMap<String, EpgProgram>()

        fun run(rawInput: InputStream): XmltvStats {
            val input = TailTrackingInputStream(rawInput)
            val parser = try {
                parserFactory().also { configure(it, input) }
            } catch (e: XmlPullParserException) {
                return stats("Parser setup failed: ${e.message}")
            }
            var error: String? = null
            try {
                var sawTvEnd = false
                var sawRoot = false
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.depth) {
                            1 -> sawRoot = true
                            2 -> when (parser.name) {
                                "channel" -> readChannel(parser)
                                "programme" -> readProgramme(parser)
                                else -> skipElement(parser)
                            }
                            else -> skipElement(parser)
                        }
                    } else if (event == XmlPullParser.END_TAG && parser.depth == 1) {
                        sawTvEnd = true
                    }
                    event = parser.next()
                }
                if (!sawRoot) error = "No XMLTV root element"
                else if (!sawTvEnd || !input.endsWithRootClose()) error = "Unexpected end of document (truncated?)"
            } catch (e: XmlPullParserException) {
                error = "Malformed XMLTV: ${e.message?.take(200)}"
            } catch (e: IOException) {
                error = "I/O error: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
            } catch (e: TruncatedElement) {
                error = "Unexpected end of document (truncated?)"
            }
            if (error == null) flushHeld() else dropHeld()
            // Programmes still waiting for a successor have no end time: skip them.
            skipped += pendingNoStop.size
            pendingNoStop.clear()
            return stats(error)
        }

        private fun stats(error: String?) = XmltvStats(channels, programs, skipped, error)

        private fun configure(parser: XmlPullParser, input: InputStream) {
            trySetFeature(parser, XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            trySetFeature(parser, XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            trySetFeature(parser, XmlPullParser.FEATURE_VALIDATION, false)
            // kXML2 / Android: tolerate unknown entities (&nbsp;, &xxe;) as literal text.
            trySetFeature(parser, FEATURE_RELAXED, true)
            parser.setInput(input, null)
            for ((name, text) in EXTRA_ENTITIES) {
                try {
                    parser.defineEntityReplacementText(name, text)
                } catch (_: Exception) {
                    // Not supported by this implementation; harmless.
                }
            }
        }

        private fun trySetFeature(parser: XmlPullParser, name: String, value: Boolean) {
            try {
                parser.setFeature(name, value)
            } catch (_: Exception) {
                // Unsupported feature (e.g. kXML2 rejects process-docdecl); defaults are safe.
            }
        }

        private fun readChannel(p: XmlPullParser) {
            val id = p.getAttributeValue(null, "id")?.trim()?.take(MAX_ID)
            val names = ArrayList<String>(2)
            var icon: String? = null
            val depth = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) throw TruncatedElement()
                if (ev == XmlPullParser.END_TAG && p.depth == depth) break
                if (ev != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "display-name" -> readText(p, MAX_NAME)?.let { if (names.size < MAX_NAMES) names += it }
                    "icon" -> {
                        if (icon == null) icon = cleanUrl(p.getAttributeValue(null, "src"))
                        skipElement(p)
                    }
                    else -> skipElement(p)
                }
            }
            if (id.isNullOrEmpty()) return
            hold(EpgChannel(id = id, displayNames = names, iconUrl = icon))
        }

        private fun readProgramme(p: XmlPullParser) {
            val channel = p.getAttributeValue(null, "channel")?.trim()?.take(MAX_ID)
            val startRaw = p.getAttributeValue(null, "start")
            val stopRaw = p.getAttributeValue(null, "stop")
            var title: String? = null
            var desc: String? = null
            var category: String? = null
            var icon: String? = null
            var onscreen: String? = null
            var xmltvNs: String? = null
            val depth = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) throw TruncatedElement()
                if (ev == XmlPullParser.END_TAG && p.depth == depth) break
                if (ev != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "title" -> readText(p, MAX_TITLE).let { if (title == null) title = it }
                    "desc" -> readText(p, MAX_DESC).let { if (desc == null) desc = it }
                    "category" -> readText(p, MAX_NAME).let { if (category == null) category = it }
                    "icon" -> {
                        if (icon == null) icon = cleanUrl(p.getAttributeValue(null, "src"))
                        skipElement(p)
                    }
                    "episode-num" -> {
                        val system = p.getAttributeValue(null, "system")
                        val text = readText(p, MAX_NAME)
                        when (system) {
                            "onscreen" -> if (onscreen == null) onscreen = text
                            "xmltv_ns" -> if (xmltvNs == null) xmltvNs = text
                        }
                    }
                    else -> skipElement(p)
                }
            }

            val start = XmltvTime.parse(startRaw)
            if (channel.isNullOrEmpty() || title == null || start == null) {
                skipped++
                return
            }
            val stopParsed = XmltvTime.parse(stopRaw)
            if (stopRaw != null && stopParsed == null) {
                skipped++ // stop present but malformed
                return
            }
            val program = EpgProgram(
                channelEpgId = channel,
                startMillis = start + shiftMillis,
                endMillis = (stopParsed ?: start) + shiftMillis,
                title = title!!,
                description = desc,
                category = category,
                iconUrl = icon,
                episodeNumber = onscreen ?: xmltvNs?.let(::formatXmltvNs),
            )

            // Resolve a pending stop-less programme of this channel using this one's start.
            pendingNoStop.remove(channel)?.let { prev ->
                val end = program.startMillis
                if (end > prev.startMillis && end - prev.startMillis <= MAX_INFERRED_DURATION_MILLIS) {
                    emit(prev.copy(endMillis = end))
                } else {
                    skipped++
                }
            }

            if (stopParsed == null) {
                pendingNoStop[channel] = program
                return
            }
            if (program.endMillis <= program.startMillis) {
                skipped++
                return
            }
            emit(program)
        }

        private fun emit(program: EpgProgram) = hold(program)

        /**
         * The most recent element is delivered only once the next one is complete (or the document
         * ended cleanly): relaxed parsers auto-close an element cut off by truncation, and such a
         * partial element must not reach the callbacks.
         */
        private var held: Any? = null

        private fun hold(element: Any) {
            flushHeld()
            held = element
        }

        private fun flushHeld() {
            when (val h = held) {
                is EpgProgram -> { programs++; onProgram(h) }
                is EpgChannel -> { channels++; onChannel(h) }
            }
            held = null
        }

        private fun dropHeld() {
            if (held is EpgProgram) skipped++
            held = null
        }

        /** Reads the text content of the current element (skipping nested markup); clipped, null if blank. */
        private fun readText(p: XmlPullParser, max: Int): String? {
            val depth = p.depth
            val sb = StringBuilder()
            while (true) {
                val ev = p.next()
                when (ev) {
                    XmlPullParser.END_DOCUMENT -> throw TruncatedElement()
                    XmlPullParser.TEXT -> if (sb.length <= max) sb.append(p.text)
                    XmlPullParser.END_TAG -> if (p.depth == depth) break
                }
            }
            return clip(sb, max)
        }

        private fun skipElement(p: XmlPullParser) {
            val depth = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) throw TruncatedElement()
                if (ev == XmlPullParser.END_TAG && p.depth == depth) return
            }
        }
    }

    /**
     * Remembers the last few significant bytes read. Relaxed parsers (kXML2 / Android) silently
     * close open elements at EOF, so truncation is detected by checking that the raw document
     * really ends with `</tv>` (whitespace and NUL bytes of UTF-16 are ignored).
     */
    private class TailTrackingInputStream(input: InputStream) : java.io.FilterInputStream(input) {
        private val tail = ByteArray(TAIL_SIZE)
        private var pos = 0
        private var filled = 0

        private fun record(b: Int) {
            if (b == 0 || b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code) return
            tail[pos] = b.toByte()
            pos = (pos + 1) % TAIL_SIZE
            if (filled < TAIL_SIZE) filled++
        }

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) record(b)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            for (i in off until off + n) record(b[i].toInt() and 0xff)
            return n
        }


        fun endsWithRootClose(): Boolean {
            val expected = "</tv>".toByteArray()
            if (filled < expected.size) return false
            for (i in expected.indices) {
                val idx = (pos - expected.size + i + TAIL_SIZE) % TAIL_SIZE
                if (tail[idx] != expected[i]) return false
            }
            return true
        }

        companion object {
            const val TAIL_SIZE = 16
        }
    }

    private class TruncatedElement : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        const val MAX_TITLE = 512
        const val MAX_DESC = 4096
        private const val MAX_NAME = 512
        private const val MAX_ID = 512
        private const val MAX_NAMES = 16
        private const val MAX_URL = 8 * 1024
        const val MAX_INFERRED_DURATION_MILLIS = 12L * 60 * 60 * 1000
        private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"

        private val EXTRA_ENTITIES = listOf("nbsp" to " ")

        private fun clip(sb: CharSequence, max: Int): String? {
            val s = sb.trim()
            if (s.isEmpty()) return null
            if (s.length <= max) return s.toString()
            var end = max
            // Do not cut a surrogate pair in half.
            if (Character.isHighSurrogate(s[end - 1])) end--
            return s.substring(0, end).trimEnd()
        }

        private fun cleanUrl(src: String?): String? {
            val s = src?.trim() ?: return null
            if (s.isEmpty() || s.length > MAX_URL) return null
            return s
        }

        /** "0.4.0/1" (zero-based season.episode.part) -> "S01E05"; returns raw text if unparsable. */
        internal fun formatXmltvNs(raw: String): String? {
            val parts = raw.replace(" ", "").split('.')
            if (parts.size < 2) return raw
            val season = parts[0].substringBefore('/').toIntOrNull()?.plus(1)
            val episode = parts[1].substringBefore('/').toIntOrNull()?.plus(1)
            return when {
                season != null && episode != null -> String.format(java.util.Locale.ROOT, "S%02dE%02d", season, episode)
                episode != null -> String.format(java.util.Locale.ROOT, "E%02d", episode)
                season != null -> String.format(java.util.Locale.ROOT, "S%02d", season)
                else -> null
            }
        }
    }
}
