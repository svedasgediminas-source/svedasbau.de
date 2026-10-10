package app.novaiptv.feature.search

import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test fun normalizesWhitespace() {
        assertEquals("bbc one", SearchQuery.normalize("  bbc   one \t"))
    }

    @Test fun foldsCaseAndAccents() {
        assertEquals("zalgiris kaunas", SearchQuery.fold(" Žalgiris  KAUNAS"))
        assertEquals("ubersee", SearchQuery.fold("Übersee"))
    }

    @Test fun minimumLength() {
        assertFalse(SearchQuery.isSearchable(" a "))
        assertTrue(SearchQuery.isSearchable("ab"))
    }
}

class SearchRankingTest {
    data class Item(val id: String, val name: String)

    @Test fun scoreOrder() {
        assertEquals(SearchRanking.EXACT, SearchRanking.score("Sport 1", "sport 1"))
        assertEquals(SearchRanking.PREFIX, SearchRanking.score("Sport 1 HD", "sport"))
        assertEquals(SearchRanking.WORD_PREFIX, SearchRanking.score("Eurosport News", "news"))
        assertEquals(SearchRanking.CONTAINS, SearchRanking.score("Eurosport", "sport"))
        assertEquals(SearchRanking.ALL_WORDS, SearchRanking.score("The Dark Knight", "knight dark"))
        assertEquals(SearchRanking.NONE, SearchRanking.score("Cartoon", "news"))
    }

    @Test fun rankSortsMergesAndKeepsStableOrder() {
        val items = listOf(
            Item("1", "Eurosport 2"), Item("2", "Sport Extra"), Item("3", "Sport"),
            Item("2", "Sport Extra duplicate"), Item("4", "Eurosport 1"), Item("5", "Motorsport TV"),
        )
        val ranked = SearchRanking.rank(items, "sport", { it.id }, { it.name })
        assertEquals(listOf("3", "2", "1", "4", "5"), ranked.map { it.id })
    }

    private fun channel(id: String, epg: String?) =
        Channel(id, 1, 1, "Ch $id", null, null, epg, StreamSource("http://x/$id"))

    private fun prog(epg: String, start: Long, end: Long, title: String = "News") = EpgProgram(epg, start, end, title)

    @Test fun programmeHitsLiveFirstThenUpcomingSkipsPastAndUnknown() {
        val index = SearchRanking.indexByEpgId(listOf(channel("a", "A"), channel("b", "B"), channel("a2", "A"), channel("c", null)))
        assertEquals("a", index["A"]?.id)
        val now = 1_000L
        val hits = SearchRanking.programmeHits(
            listOf(
                prog("B", 2_000, 3_000), prog("A", 500, 900), prog("A", 900, 1_500),
                prog("X", 900, 1_500), prog("B", 1_200, 1_800), prog("A", 900, 1_500, "dup"),
            ),
            index, now, limit = 10,
        )
        assertEquals(listOf(900L, 1_200L, 2_000L), hits.map { it.program.startMillis })
        assertEquals(listOf(true, false, false), hits.map { it.isLive })
        assertEquals(1f / 6f, hits[0].progress(now)!!, 0.001f)
        assertNull(hits[1].progress(now))
    }
}

class RecentSearchesTest {
    @Test fun addsNewestFirstDedupesAndCaps() {
        var list = emptyList<String>()
        list = RecentSearches.add(list, "news")
        list = RecentSearches.add(list, "x")
        list = RecentSearches.add(list, "  Sport ")
        list = RecentSearches.add(list, "NEWS")
        assertEquals(listOf("NEWS", "Sport"), list)
        repeat(10) { list = RecentSearches.add(list, "q$it", max = 3) }
        assertEquals(listOf("q9", "q8", "q7"), list)
    }

    @Test fun encodeRoundTrip() {
        val list = listOf("a b", "cd")
        assertEquals(list, RecentSearches.decode(RecentSearches.encode(list)))
        assertEquals(emptyList<String>(), RecentSearches.decode(""))
    }
}
