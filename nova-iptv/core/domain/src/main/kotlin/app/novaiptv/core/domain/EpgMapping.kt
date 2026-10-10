package app.novaiptv.core.domain

import java.util.Locale

/** One XMLTV channel id that has programmes in the local guide data. */
data class EpgIdSuggestion(val epgId: String, val sampleTitle: String?, val programmeCount: Int)

/**
 * Lists the XMLTV ids known locally (ids that have programmes stored), so the user can manually
 * map a channel whose guide data did not match automatically
 * (see [EpgRepository.setManualMapping]).
 */
interface EpgMappingSource {
    /** Ids similar to [query] (usually the channel name), best match first. Blank = any ids. */
    suspend fun searchEpgIds(query: String, limit: Int = 50): List<EpgIdSuggestion>
}

/**
 * Pure ranking of EPG id suggestions against a channel name or typed query. [normalize] is the
 * channel-name normaliser used for automatic matching (EpgMatcher.normalize in the data layer):
 * it lowercases, strips diacritics, country prefixes and quality tags and splits into
 * space-separated tokens.
 */
object EpgSuggestionRanker {
    private val ID_COUNTRY_SUFFIX = Regex("""\.[a-zA-Z]{2,3}$""")
    private val SPLIT = Regex("""[^\p{L}\p{Nd}]+""")

    /**
     * Search terms worth sending to a LIKE query for [query]: the trimmed raw query plus its
     * normalised tokens (longest first, at least 2 characters), de-duplicated, at most [max].
     */
    fun searchTerms(query: String, normalize: (String) -> String, max: Int = 4): List<String> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        // Keyed by lowercase: SQLite LIKE is case-insensitive, so "LRT" and "lrt" are one term.
        val out = LinkedHashMap<String, String>()
        out.putIfAbsent(raw.lowercase(Locale.ROOT), raw)
        val normalized = safeNormalize(raw, normalize)
        if (normalized.isNotEmpty()) out.putIfAbsent(normalized, normalized)
        normalized.split(' ')
            .filter { it.length >= 2 }
            .sortedByDescending { it.length }
            .forEach { out.putIfAbsent(it, it) }
        return out.values.take(max.coerceAtLeast(1))
    }

    /**
     * Similarity in 0..1000 between an XMLTV id and a channel name/query. Exact normalised match
     * (ignoring a ".lt"-style country suffix) scores highest, then prefix, containment and token
     * overlap. 0 means unrelated.
     */
    fun score(epgId: String, query: String, normalize: (String) -> String): Int {
        val q = safeNormalize(query, normalize)
        if (q.isEmpty()) return 0
        val idBase = epgId.trim().replace(ID_COUNTRY_SUFFIX, "")
        val idNorm = safeNormalize(idBase, normalize)
        val idFull = safeNormalize(epgId, normalize)
        val qCompact = q.replace(" ", "")
        val idCompact = idNorm.replace(" ", "")
        if (idNorm.isEmpty() && idFull.isEmpty()) return 0
        if (idNorm == q || idFull == q) return 1000
        if (idCompact.isNotEmpty() && idCompact == qCompact) return 950
        var best = 0
        if (idCompact.isNotEmpty() && qCompact.isNotEmpty()) {
            if (idCompact.startsWith(qCompact) || qCompact.startsWith(idCompact)) {
                best = maxOf(best, 700 + lengthBonus(idCompact, qCompact))
            } else if (idCompact.contains(qCompact) || qCompact.contains(idCompact)) {
                best = maxOf(best, 500 + lengthBonus(idCompact, qCompact))
            }
        }
        val qTokens = tokens(q)
        val idTokens = tokens(idFull) + tokens(idNorm)
        if (qTokens.isNotEmpty() && idTokens.isNotEmpty()) {
            val common = qTokens.count { it in idTokens }
            if (common > 0) {
                val union = (qTokens + idTokens).size
                best = maxOf(best, 100 + (300 * common) / union)
            }
        }
        if (best == 0 && epgId.lowercase(Locale.ROOT).contains(query.trim().lowercase(Locale.ROOT)) && query.isNotBlank()) {
            best = 50
        }
        return best.coerceAtMost(999)
    }

    /**
     * Orders [candidates] (distinct by id; first wins) by [score] against [query], then by
     * programme count (more data first), then by id. With a blank query the order is by programme
     * count. Unrelated ids are kept after related ones so a typed query never hides every result.
     */
    fun rank(
        query: String,
        candidates: List<EpgIdSuggestion>,
        normalize: (String) -> String,
        limit: Int = 50,
    ): List<EpgIdSuggestion> {
        val unique = LinkedHashMap<String, EpgIdSuggestion>()
        for (c in candidates) if (c.epgId.isNotBlank()) unique.putIfAbsent(c.epgId, c)
        val scored = unique.values.map { it to score(it.epgId, query, normalize) }
        return scored
            .sortedWith(
                compareByDescending<Pair<EpgIdSuggestion, Int>> { it.second }
                    .thenByDescending { it.first.programmeCount }
                    .thenBy { it.first.epgId.lowercase(Locale.ROOT) },
            )
            .take(limit.coerceAtLeast(0))
            .map { it.first }
    }

    private fun lengthBonus(a: String, b: String): Int {
        val longer = maxOf(a.length, b.length)
        val shorter = minOf(a.length, b.length)
        return if (longer == 0) 0 else (shorter * 200) / longer
    }

    private fun tokens(s: String): Set<String> = s.split(SPLIT).filter { it.isNotEmpty() }.toSet()

    private fun safeNormalize(s: String, normalize: (String) -> String): String =
        normalize(s).trim().lowercase(Locale.ROOT)
}
