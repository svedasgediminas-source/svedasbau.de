package app.novaiptv.feature.movies

import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.VodCategory
import app.novaiptv.core.model.WatchHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultContentFilterTest {
    private fun cat(id: String, name: String) = VodCategory(1, id, name)

    @Test fun detectsAdultMarkers() {
        listOf("XXX Movies", "Adult", "FOR ADULTS", "18+ Only", "Erotic", "Для взрослых").forEach {
            assertTrue(it, AdultContentFilter.isAdultCategory(it))
        }
        listOf("Action", "Kids", "Documentary 2018", "Comedy").forEach {
            assertFalse(it, AdultContentFilter.isAdultCategory(it))
        }
    }

    @Test fun splitHidesOnlyWhenRequested() {
        val cats = listOf(cat("1", "Action"), cat("2", "XXX"), cat("1", "Action duplicate"))
        val shown = AdultContentFilter.split(cats, hideAdult = false)
        assertEquals(listOf("1", "2"), shown.visible.map { it.id })
        assertTrue(shown.hiddenIds.isEmpty())

        val hidden = AdultContentFilter.split(cats, hideAdult = true)
        assertEquals(listOf("1"), hidden.visible.map { it.id })
        assertEquals(setOf("2"), hidden.hiddenIds)
    }

    @Test fun filterItemsDropsHiddenCategoriesKeepsUncategorised() {
        val items = listOf("a" to "1", "b" to "2", "c" to null)
        val result = AdultContentFilter.filterItems(items, setOf("2")) { it.second }
        assertEquals(listOf("a", "c"), result.map { it.first })
    }
}

class ActivePlaylistTest {
    @Test fun prefersStoredThenFirst() {
        assertEquals(5L, ActivePlaylist.resolve(5L, listOf(3L, 5L)))
        assertEquals(3L, ActivePlaylist.resolve(9L, listOf(3L, 5L)))
        assertEquals(3L, ActivePlaylist.resolve(null, listOf(3L, 5L)))
        assertNull(ActivePlaylist.resolve(5L, emptyList()))
    }
}

class VodFormatTest {
    @Test fun durationParts() {
        assertNull(VodFormat.durationParts(null))
        assertNull(VodFormat.durationParts(0))
        assertEquals(VodFormat.DurationParts(0, 1), VodFormat.durationParts(20))
        assertEquals(VodFormat.DurationParts(0, 48), VodFormat.durationParts(48 * 60 + 10))
        assertEquals(VodFormat.DurationParts(1, 52), VodFormat.durationParts(6720))
        assertEquals(VodFormat.DurationParts(2, 0), VodFormat.durationParts(7199))
    }

    @Test fun timestamp() {
        assertEquals("00:00", VodFormat.timestamp(-5))
        assertEquals("00:09", VodFormat.timestamp(9_999))
        assertEquals("12:34", VodFormat.timestamp((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", VodFormat.timestamp((3600 + 2 * 60 + 3) * 1000L))
    }

    @Test fun rating() {
        assertNull(VodFormat.rating(null))
        assertNull(VodFormat.rating(0.0))
        assertEquals("★ 7.4", VodFormat.rating(7.36))
        assertEquals("★ 8", VodFormat.rating(8.0))
    }

    @Test fun metaLineSkipsBlanks() {
        assertEquals("2021 · ★ 7.4 · Drama", VodFormat.metaLine("2021", null, "★ 7.4", " ", "Drama"))
        assertEquals("", VodFormat.metaLine(null, ""))
    }

    private fun h(pos: Long, dur: Long) = WatchHistory(ContentType.Movie, "m", 1, "T", null, pos, dur, 0)

    @Test fun resumePosition() {
        assertNull(VodFormat.resumePositionMillis(null))
        assertNull(VodFormat.resumePositionMillis(h(5_000, 100_000)))
        assertNull(VodFormat.resumePositionMillis(h(96_000, 100_000)))
        assertEquals(40_000L, VodFormat.resumePositionMillis(h(40_000, 100_000)))
    }
}
