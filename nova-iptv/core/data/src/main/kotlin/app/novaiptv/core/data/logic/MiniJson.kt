package app.novaiptv.core.data.logic

/**
 * Minimal JSON codec for flat `Map<String, String>` objects (channel extras, stream headers,
 * encrypted credential payloads). Kept dependency-free so it runs in plain JVM unit tests
 * (org.json is an Android stub there). Malformed input decodes to an empty map.
 */
object MiniJson {

    fun encode(map: Map<String, String>): String {
        val sb = StringBuilder(16 + map.size * 24)
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            writeString(sb, k)
            sb.append(':')
            writeString(sb, v)
        }
        sb.append('}')
        return sb.toString()
    }

    /** Returns null for an empty map so empty columns stay NULL in the database. */
    fun encodeOrNull(map: Map<String, String>): String? = if (map.isEmpty()) null else encode(map)

    fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            Reader(json).readObject()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        } catch (e: IndexOutOfBoundsException) {
            emptyMap()
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u")
                    sb.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun readObject(): Map<String, String> {
            skipWs()
            expect('{')
            val out = LinkedHashMap<String, String>()
            skipWs()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                val value = readString()
                out[key] = value
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun peek(): Char = s[i]

        private fun expect(c: Char) {
            if (s[i] != c) throw IllegalArgumentException("expected $c")
            i++
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
