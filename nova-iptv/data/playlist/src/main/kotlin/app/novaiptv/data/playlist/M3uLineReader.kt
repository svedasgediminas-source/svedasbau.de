package app.novaiptv.data.playlist

import java.io.Reader

/**
 * Reads lines from [reader] without ever buffering more than [maxLength] characters per line,
 * so a hostile file with a multi-megabyte single line cannot exhaust memory. Handles LF, CRLF
 * and lone CR line endings. IOExceptions from the underlying stream propagate to the caller.
 */
internal class M3uLineReader(private val reader: Reader, private val maxLength: Int) {
    private val buf = CharArray(16 * 1024)
    private var pos = 0
    private var lim = 0
    private var skipLf = false
    private val sb = StringBuilder(256)

    /** True when the last line returned by [readLine] exceeded [maxLength] (it is returned empty). */
    var lastLineTooLong: Boolean = false
        private set

    /** 1-based number of the line last returned. */
    var lineNumber: Int = 0
        private set

    fun readLine(): String? {
        sb.setLength(0)
        lastLineTooLong = false
        var any = false
        while (true) {
            if (pos >= lim) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) {
                    lim = 0
                    pos = 0
                    return if (any) finish() else null
                }
                pos = 0
                lim = n
                continue
            }
            val c = buf[pos++]
            if (skipLf) {
                skipLf = false
                if (c == '\n') continue
            }
            any = true
            when (c) {
                '\n' -> return finish()
                '\r' -> {
                    skipLf = true
                    return finish()
                }
                else -> if (sb.length < maxLength) sb.append(c) else lastLineTooLong = true
            }
        }
    }

    private fun finish(): String {
        lineNumber++
        return if (lastLineTooLong) "" else sb.toString()
    }
}

/** Parses `key="value" key2='v' key3=bare ...,Title` attribute sections of #EXTINF / #EXTM3U lines. */
internal object M3uAttributeParser {

    class Parsed(val attributes: Map<String, String>, val title: String?)

    /** Parses from [start]; keys are lower-cased. Text after the first top-level comma is the title. */
    fun parse(s: String, start: Int): Parsed {
        val attrs = LinkedHashMap<String, String>()
        var title: String? = null
        val n = s.length
        var i = start
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) break
            if (s[i] == ',') {
                title = s.substring(i + 1).trim()
                break
            }
            val ks = i
            while (i < n && !s[i].isWhitespace() && s[i] != '=' && s[i] != ',') i++
            val key = s.substring(ks, i).trim().lowercase()
            // Tolerate `key = "value"`.
            var j = i
            while (j < n && s[j].isWhitespace()) j++
            if (j < n && s[j] == '=') {
                i = j + 1
                var k = i
                while (k < n && s[k].isWhitespace()) k++
                val value: String
                if (k < n && (s[k] == '"' || s[k] == '\'')) {
                    val q = s[k]
                    val close = s.indexOf(q, k + 1)
                    if (close < 0) {
                        // Unclosed quote: value runs to the next comma (title follows) or end of line.
                        val comma = s.indexOf(',', k + 1)
                        if (comma < 0) {
                            value = s.substring(k + 1)
                            i = n
                        } else {
                            value = s.substring(k + 1, comma)
                            i = comma
                        }
                    } else {
                        value = s.substring(k + 1, close)
                        i = close + 1
                    }
                } else {
                    // Unquoted: runs until a comma or until whitespace followed by another `key=`.
                    val vs = i
                    while (i < n && s[i] != ',') {
                        if (s[i].isWhitespace() && looksLikeAttributeStart(s, i)) break
                        i++
                    }
                    value = s.substring(vs, i)
                }
                if (key.isNotEmpty() && key !in attrs) attrs[key] = value.trim()
            }
            // A bare token without '=' is ignored.
        }
        return Parsed(attrs, title)
    }

    private fun looksLikeAttributeStart(s: String, from: Int): Boolean {
        var i = from
        val n = s.length
        while (i < n && s[i].isWhitespace()) i++
        val ks = i
        while (i < n && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
        return i > ks && i < n && s[i] == '='
    }
}
