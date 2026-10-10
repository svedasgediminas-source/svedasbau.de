package app.novaiptv.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSuggestionRankerTest {

    /** Simplified stand-in for EpgMatcher.normalize: lowercase, punctuation -> space, drop quality tags. */
    private val normalize: (String) -> String = { s ->
        val tokens = s.lowercase().split(Regex("""[^\p{L}\p{Nd}]+""")).filter { it.isNotEmpty() }
        val kept = tokens.filter { it !in setOf("hd", "fhd", "uhd", "4k", "sd") }
        kept.ifEmpty { tokens }.joinToString(" ")
    }

    private fun s(id: String, count: Int = 10) = EpgIdSuggestion(id, "Title", count)

    @Test
    fun exactNormalizedMatchIgnoringCountrySuffixWins() {
        val ranked = EpgSuggestionRanker.rank(
            "LRT HD",
            listOf(s("LRTPlius.lt", 500), s("Other.lt", 900), s("LRT.lt", 20), s("LRTLituanica.lt")),
            normalize,
        )
        assertEquals("LRT.lt", ranked.first().epgId)
        assertEquals("Other.lt", ranked.last().epgId)
    }

    @Test
    fun prefixBeatsContainsBeatsTokenOverlap() {
        val q = "Sport"
        val prefix = EpgSuggestionRanker.score("Sport1.de", q, normalize)
        val contains = EpgSuggestionRanker.score("EuroSport.de", q, normalize)
        val unrelated = EpgSuggestionRanker.score("Kika.de", q, normalize)
        assertTrue("$prefix > $contains", prefix > contains)
        assertTrue(contains > unrelated)
        assertEquals(0, unrelated)
    }

    @Test
    fun tokenOverlapScoresMultiWordNames() {
        val score = EpgSuggestionRanker.score("discovery.channel.uk", "Channel Discovery", normalize)
        assertTrue(score in 100..999)
    }

    @Test
    fun compactMatchIgnoresSpacesAndPunctuation() {
        assertEquals(950, EpgSuggestionRanker.score("TV3Sport.lt", "TV3 Sport", normalize))
    }

    @Test
    fun tiesAreBrokenByProgrammeCountThenId() {
        val ranked = EpgSuggestionRanker.rank("", listOf(s("b", 5), s("a", 5), s("c", 50)), normalize)
        assertEquals(listOf("c", "a", "b"), ranked.map { it.epgId })
    }

    @Test
    fun duplicatesAndBlankIdsAreDroppedAndLimitApplies() {
        val ranked = EpgSuggestionRanker.rank("x", listOf(s("A"), s("A", 99), s(" "), s("B"), s("C")), normalize, limit = 2)
        assertEquals(2, ranked.size)
        assertEquals(1, ranked.count { it.epgId == "A" })
        assertTrue(ranked.none { it.epgId.isBlank() })
        assertTrue(EpgSuggestionRanker.rank("x", listOf(s("A")), normalize, limit = -1).isEmpty())
    }

    @Test
    fun unrelatedIdsStayAfterRelatedOnes() {
        val ranked = EpgSuggestionRanker.rank("BBC One", listOf(s("CNN.us", 999), s("BBCOne.uk", 1)), normalize)
        assertEquals(listOf("BBCOne.uk", "CNN.us"), ranked.map { it.epgId })
    }

    @Test
    fun searchTermsIncludeRawNormalizedAndLongTokens() {
        assertEquals(listOf("UK: BBC One HD", "uk bbc one", "bbc", "one", "uk"), EpgSuggestionRanker.searchTerms("UK: BBC One HD", normalize, max = 10))
        assertEquals(listOf("LRT"), EpgSuggestionRanker.searchTerms(" LRT ", normalize))
        assertTrue(EpgSuggestionRanker.searchTerms("   ", normalize).isEmpty())
        assertEquals(2, EpgSuggestionRanker.searchTerms("a bb ccc dddd", normalize, max = 2).size)
    }

    @Test
    fun blankQueryScoresZero() {
        assertEquals(0, EpgSuggestionRanker.score("LRT.lt", "  ", normalize))
    }
}
