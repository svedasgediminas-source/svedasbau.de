package app.novaiptv.data.epg

import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgChannel
import java.text.Normalizer
import java.util.Locale

/**
 * Matches playlist channels to XMLTV channel ids.
 *
 * Order: exact epgId (tvg-id) -> case-insensitive epgId -> normalized epgId against display names
 * -> normalized channel name / tvg-name against display names.
 */
object EpgMatcher {
    private val QUALITY_TOKENS = setOf(
        "hd", "fhd", "uhd", "4k", "8k", "sd", "hq", "lq", "hevc", "h265", "h264", "x265", "x264",
        "fullhd", "1080p", "1080i", "720p", "576p", "2160p", "50fps", "60fps", "hdr",
    )

    // "uk:", "UK |", "lt - ", "[DE]", "|LT|" at the very start. A bare hyphen needs surrounding
    // spaces so names like "ZDF-neo" keep their first word.
    private val COUNTRY_PREFIX = Regex(
        """^\s*(?:[\[(|]\s*[a-z]{2,3}\s*[\])|]\s*(?:[:|]|\s-\s)?|[a-z]{2,3}\s*(?:[:|]|\s+-\s+))\s*""",
    )
    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val TIMESHIFT = Regex("""\+\s*(\d{1,2})(?!\d)""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{Nd}]+""")

    /**
     * Lowercases, removes diacritics, a leading country prefix ("uk:", "de |", "lt -", "[pl]"),
     * punctuation and quality tags (hd, fhd, uhd, 4k, sd, hevc, h265, ...). Timeshift suffixes such
     * as "+1" are kept (as "plus1") because "Sky One +1" is a different schedule than "Sky One";
     * "Canal+" becomes "canal plus".
     */
    fun normalize(name: String): String {
        var s = name.trim().lowercase(Locale.ROOT)
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(DIACRITICS, "")
        s = COUNTRY_PREFIX.replaceFirst(s, "")
        s = TIMESHIFT.replace(s) { " plus${it.groupValues[1]} " }
        s = s.replace("+", " plus ").replace("&", " and ")
        val tokens = s.split(NON_ALNUM).filter { it.isNotEmpty() }
        val kept = tokens.filter { it !in QUALITY_TOKENS }
        return (kept.ifEmpty { tokens }).joinToString(" ")
    }

    /**
     * Picks the XMLTV channel id for a channel: exact epgId match (case-insensitive), then
     * normalized tvg-name/display-name match. Returns null if nothing matches.
     */
    fun match(channel: Channel, byId: Map<String, EpgChannel>, byNormalizedName: Map<String, String>): String? {
        val epgId = channel.epgId?.trim()?.takeIf { it.isNotEmpty() }
        if (epgId != null) {
            byId[epgId]?.let { return it.id }
            lowerIndex(byId)[epgId.lowercase(Locale.ROOT)]?.let { return it }
            byNormalizedName[normalize(epgId)]?.let { return it }
        }
        val candidates = listOfNotNull(channel.extras["tvg-name"], channel.name)
        for (candidate in candidates) {
            val key = normalize(candidate)
            if (key.isNotEmpty()) byNormalizedName[key]?.let { return it }
        }
        return null
    }

    /**
     * normalized display-name -> XMLTV channel id. The first channel claiming a name wins. Ids are
     * added afterwards with a trailing ".cc" country suffix removed ("LRT.lt" -> "lrt"), without
     * overriding display names.
     */
    fun buildNameIndex(channels: Collection<EpgChannel>): Map<String, String> {
        val index = HashMap<String, String>(channels.size * 2)
        for (ch in channels) {
            for (n in ch.displayNames) {
                val key = normalize(n)
                if (key.isNotEmpty()) index.putIfAbsent(key, ch.id)
            }
        }
        for (ch in channels) {
            val key = normalize(ch.id.replace(ID_COUNTRY_SUFFIX, ""))
            if (key.isNotEmpty()) index.putIfAbsent(key, ch.id)
        }
        return index
    }

    private val ID_COUNTRY_SUFFIX = Regex("""\.[a-zA-Z]{2,3}$""")

    // Case-insensitive id lookup without scanning the map for every channel: cache a lowercase
    // index for the most recently used byId map (callers match many channels against one map).
    private class LowerIndex(val source: Map<String, EpgChannel>, val sourceSize: Int, val index: Map<String, String>)

    @Volatile
    private var lastLowerIndex: LowerIndex? = null

    private fun lowerIndex(byId: Map<String, EpgChannel>): Map<String, String> {
        lastLowerIndex?.let { if (it.source === byId && it.sourceSize == byId.size) return it.index }
        val index = HashMap<String, String>(byId.size * 2)
        for ((key, ch) in byId) index.putIfAbsent(key.lowercase(Locale.ROOT), ch.id)
        lastLowerIndex = LowerIndex(byId, byId.size, index)
        return index
    }
}
