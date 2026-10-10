package app.novaiptv.data.epg

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * XMLTV timestamp parsing. XMLTV times carry an explicit numeric offset per value, so the
 * result is DST-correct without consulting any time-zone database: "20241027020000 +0300"
 * and "20241027020000 +0200" are simply different instants.
 */
object XmltvTime {
    // yyyyMMddHHmm[ss][.fraction] [ +HHMM | +HH:MM | Z | UTC | GMT ]
    private val PATTERN = Regex(
        """^\s*(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?(?:[.,]\d+)?\s*(?:(?:([+-])(\d{2}):?(\d{2}))|(Z|UTC|GMT))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses "yyyyMMddHHmmss Z" variants: "20240101120000 +0200", "20240101120000+0200",
     * "20240101120000" (UTC assumed), "202401011200", with optional fractional seconds and extra
     * spaces. Returns epoch millis or null.
     */
    fun parse(value: String?): Long? {
        if (value.isNullOrBlank() || value.length > 64) return null
        val m = PATTERN.matchEntire(value) ?: return null
        val g = m.groupValues
        return try {
            val offsetSeconds = if (g[7].isNotEmpty()) {
                val h = g[8].toInt()
                val min = g[9].toInt()
                if (h > 18 || min > 59) return null
                val s = h * 3600 + min * 60
                if (g[7] == "-") -s else s
            } else {
                0
            }
            val ldt = LocalDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(),
                if (g[6].isEmpty()) 0 else g[6].toInt(),
            )
            ldt.toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
        } catch (e: DateTimeException) {
            null
        }
    }
}
