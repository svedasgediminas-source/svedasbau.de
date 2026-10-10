package app.novaiptv.feature.livetv

import app.novaiptv.core.domain.ChannelListItem
import app.novaiptv.core.domain.toListItem
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ChannelGroup
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.Favorite
import app.novaiptv.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvLogicTest {
    private fun full(id: String, number: Int = 1, name: String = id, group: String? = null, adult: Boolean = false) =
        Channel(id, 1L, number, name, group, null, null, StreamSource("http://x/$id"), isAdult = adult)

    private fun ch(id: String, number: Int = 1, name: String = id, group: String? = null, adult: Boolean = false) =
        ChannelListItem(id, 1L, number, name, group, null, null, hasCatchup = false, catchupDays = 0, isAdult = adult)

    @Test fun adultGroupDetection() {
        assertTrue(isAdultGroupName("XXX Adult"))
        assertTrue(isAdultGroupName("18+ Movies"))
        assertTrue(isAdultGroupName("Для взрослых"))
        assertFalse(isAdultGroupName("Sports"))
        assertFalse(isAdultGroupName(null))
    }

    @Test fun categoriesHideAdultAndCount() {
        val groups = listOf(ChannelGroup(1, "News", 10), ChannelGroup(1, "Adult", 5), ChannelGroup(1, "Sport", 7))
        val hidden = buildCategories(groups, hideAdult = true, favoritesCount = 2)
        assertEquals(
            listOf(CategoryKey.All, CategoryKey.Favorites, CategoryKey.Group("News"), CategoryKey.Group("Sport")),
            hidden.map { it.key },
        )
        assertEquals(17, hidden[0].count)
        assertEquals(2, hidden[1].count)
        assertEquals(5, buildCategories(groups, hideAdult = false, favoritesCount = 0).size)
    }

    @Test fun filterByQueryNumberAndAdult() {
        val list = listOf(ch("a", 1, "BBC One"), ch("b", 2, "CNN"), ch("c", 3, "Hot", adult = true), ch("d", 12, "Sport 1", group = "XXX"))
        assertEquals(listOf("a", "b"), filterChannels(list, "", hideAdult = true).map { it.id })
        assertEquals(4, filterChannels(list, "", hideAdult = false).size)
        assertEquals(listOf("a"), filterChannels(list, " bbc ", hideAdult = true).map { it.id })
        assertEquals(listOf("b"), filterChannels(list, "2", hideAdult = true).map { it.id })
        assertEquals(listOf("d"), filterChannels(list, "12", hideAdult = false).map { it.id })
    }

    @Test fun fullChannelFilterMatchesSlimFilter() {
        val full = listOf(full("a", 1, "BBC One"), full("c", 3, "Hot", adult = true), full("d", 12, "Sport 1", group = "XXX"))
        assertEquals(listOf("a"), filterChannels(full, "", hideAdult = true).map { it.id })
        assertEquals(listOf("d"), filterChannels(full, "12", hideAdult = false).map { it.id })
        assertTrue(isAdultChannel(full[1]))
        assertTrue(isAdultChannel(full[2].toListItem()))
        assertFalse(isAdultChannel(full[0].toListItem()))
    }

    @Test fun listItemFromChannelKeepsRowFields() {
        val c = Channel(
            "p:1", 7L, 42, "News", "Info", "http://logo", "news.de", StreamSource("http://x"),
            catchup = CatchupCapability.Supported(CatchupType.Default, 3),
        )
        assertEquals(
            ChannelListItem("p:1", 7L, 42, "News", "Info", "http://logo", "news.de", hasCatchup = true, catchupDays = 3, isAdult = false),
            c.toListItem(),
        )
        assertFalse(full("x").toListItem().hasCatchup)
    }

    @Test fun favoritesOrderAndPlaylistScope() {
        val favs = listOf(
            Favorite(ContentType.Channel, "x", 1, addedAtMillis = 30, sortOrder = 1),
            Favorite(ContentType.Channel, "y", 1, addedAtMillis = 10, sortOrder = 0),
            Favorite(ContentType.Channel, "z", 2, addedAtMillis = 5, sortOrder = 0),
            Favorite(ContentType.Channel, "w", 1, addedAtMillis = 5, sortOrder = 1),
        )
        val ids = favoriteChannelIds(favs, 1)
        assertEquals(listOf("y", "w", "x"), ids)
        assertEquals(listOf("y", "x"), orderByIds(listOf(ch("x"), ch("y")), ids).map { it.id })
    }

    @Test fun activePlaylistResolution() {
        assertEquals(5L, resolveActivePlaylistId(5L, listOf(3L, 5L)))
        assertEquals(3L, resolveActivePlaylistId(9L, listOf(3L, 5L)))
        assertEquals(3L, resolveActivePlaylistId(null, listOf(3L, 5L)))
        assertNull(resolveActivePlaylistId(null, emptyList()))
    }

    @Test fun progress() {
        val p = EpgProgram("e", 1_000, 2_000, "t")
        assertEquals(0.5f, programProgress(p, 1_500)!!, 0.0001f)
        assertEquals(0f, programProgress(p, 1_000)!!, 0.0001f)
        assertNull(programProgress(p, 2_000))
        assertNull(programProgress(p, 999))
        assertNull(programProgress(null, 1_500))
    }

    @Test fun numberEntry() {
        assertEquals("1", appendDigit("", 1))
        assertEquals("12", appendDigit("1", 2))
        assertEquals("", appendDigit("", 0))
        assertEquals("7", appendDigit("12345", 7))
        assertEquals("", formatChannelNumber(0))
        assertEquals("101", formatChannelNumber(101))
    }

    @Test fun focusHelpers() {
        val list = listOf(ch("a"), ch("b"), ch("c"))
        assertEquals(1, initialFocusIndex(list, "b"))
        assertEquals(0, initialFocusIndex(list, "zzz"))
        assertEquals(0, initialFocusIndex(list, null))
        assertEquals("c", neighbourAfterRemoval(list, 1))
        assertEquals("b", neighbourAfterRemoval(list, 2))
        assertNull(neighbourAfterRemoval(listOf(ch("a")), 0))
    }
}
