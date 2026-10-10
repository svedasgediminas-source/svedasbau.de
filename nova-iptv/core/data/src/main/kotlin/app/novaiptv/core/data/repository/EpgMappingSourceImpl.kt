package app.novaiptv.core.data.repository

import app.novaiptv.core.data.db.EpgIdRow
import app.novaiptv.core.data.db.EpgMappingDao
import app.novaiptv.core.data.logic.SearchText
import app.novaiptv.core.domain.EpgIdSuggestion
import app.novaiptv.core.domain.EpgMappingSource
import app.novaiptv.core.domain.EpgSuggestionRanker
import app.novaiptv.data.epg.EpgMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Suggests XMLTV ids from epg_programs for manual mapping. Runs one LIKE query per search term
 * (raw query, normalised name, its longest tokens) so "UK: BBC One HD" still finds "BBCOne.uk",
 * then ranks the union with [EpgSuggestionRanker] using [EpgMatcher.normalize].
 */
class EpgMappingSourceImpl(private val dao: EpgMappingDao) : EpgMappingSource {

    override suspend fun searchEpgIds(query: String, limit: Int): List<EpgIdSuggestion> = withContext(Dispatchers.IO) {
        val wanted = limit.coerceIn(0, MAX_LIMIT)
        if (wanted == 0) return@withContext emptyList()
        val normalize: (String) -> String = EpgMatcher::normalize
        val terms = EpgSuggestionRanker.searchTerms(query, normalize)
        val candidates = ArrayList<EpgIdSuggestion>()
        if (terms.isEmpty()) {
            candidates += dao.searchEpgIds("%", PER_TERM_LIMIT).map { it.toSuggestion() }
        } else {
            for (term in terms) {
                val pattern = "%" + SearchText.escapeLike(term) + "%"
                candidates += dao.searchEpgIds(pattern, PER_TERM_LIMIT).map { it.toSuggestion() }
            }
        }
        EpgSuggestionRanker.rank(query, candidates, normalize, wanted)
    }

    private fun EpgIdRow.toSuggestion() =
        EpgIdSuggestion(epgId = epgId, sampleTitle = sampleTitle, programmeCount = programmeCount)

    private companion object {
        const val PER_TERM_LIMIT = 300
        const val MAX_LIMIT = 200
    }
}
