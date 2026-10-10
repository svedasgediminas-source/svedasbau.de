package app.novaiptv.feature.guide

import app.novaiptv.core.domain.Reminder
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideGroupsTest {
    private fun ch(id: String, group: String?, adult: Boolean = false, epg: String? = "epg-$id") =
        Channel(id, 1L, 1, "Name $id", group, null, epg, StreamSource("http://x/$id"), isAdult = adult)

    private val channels = listOf(
        ch("1", "News"),
        ch("2", null),
        ch("3", "Sport"),
        ch("4", "News"),
        ch("5", "  "),
        ch("6", "XXX Adult"),
        ch("7", "Sport"),
    )

    @Test fun groupsInPlaylistOrderWithCountsAndOtherLast() {
        val groups = buildGuideGroups(channels, favoritesCount = 2, hideAdult = false)
        assertEquals(
            listOf(
                GuideGroup(GuideGroupKey.All, 7),
                GuideGroup(GuideGroupKey.Favorites, 2),
                GuideGroup(GuideGroupKey.Group("News"), 2),
                GuideGroup(GuideGroupKey.Group("Sport"), 2),
                GuideGroup(GuideGroupKey.Group("XXX Adult"), 1),
                GuideGroup(GuideGroupKey.Group(""), 2),
            ),
            groups,
        )
    }

    @Test fun adultGroupsHiddenWhenRequested() {
        val groups = buildGuideGroups(channels, favoritesCount = 0, hideAdult = true)
        assertEquals(
            listOf(GuideGroupKey.All, GuideGroupKey.Favorites, GuideGroupKey.Group("News"), GuideGroupKey.Group("Sport"), GuideGroupKey.Group("")),
            groups.map { it.key },
        )
        assertEquals(6, groups.first().count)
    }

    @Test fun emptyPlaylistStillHasAllAndFavourites() {
        assertEquals(
            listOf(GuideGroup(GuideGroupKey.All, 0), GuideGroup(GuideGroupKey.Favorites, 0)),
            buildGuideGroups(emptyList(), 0, hideAdult = true),
        )
    }

    @Test fun filterByGroupFavouritesAndAll() {
        assertEquals(channels, filterGuideChannels(channels, GuideGroupKey.All, emptyList()))
        assertEquals(listOf("1", "4"), filterGuideChannels(channels, GuideGroupKey.Group("News"), emptyList()).map { it.id })
        assertEquals(listOf("2", "5"), filterGuideChannels(channels, GuideGroupKey.Group(""), emptyList()).map { it.id })
        // Favourites keep the user's order; unknown and duplicate ids are ignored.
        assertEquals(
            listOf("7", "1"),
            filterGuideChannels(channels, GuideGroupKey.Favorites, listOf("7", "missing", "1", "7")).map { it.id },
        )
        assertEquals(emptyList<Channel>(), filterGuideChannels(channels, GuideGroupKey.Group("Movies"), emptyList()))
    }

    @Test fun missingGroupFallsBackToAll() {
        val groups = buildGuideGroups(channels, 0, hideAdult = true)
        assertEquals(GuideGroupKey.All, resolveGuideGroup(GuideGroupKey.Group("XXX Adult"), groups))
        assertEquals(GuideGroupKey.Group("News"), resolveGuideGroup(GuideGroupKey.Group("News"), groups))
        assertEquals(GuideGroupKey.Favorites, resolveGuideGroup(GuideGroupKey.Favorites, groups))
        assertEquals(GuideGroupKey.All, resolveGuideGroup(GuideGroupKey.Group("News"), emptyList()))
    }

    @Test fun groupTokenRoundTrip() {
        val keys = listOf(GuideGroupKey.All, GuideGroupKey.Favorites, GuideGroupKey.Group("News"), GuideGroupKey.Group(""), GuideGroupKey.Group("g:odd"))
        for (k in keys) assertEquals(k, decodeGuideGroup(encodeGuideGroup(k)))
        assertEquals(GuideGroupKey.All, decodeGuideGroup(null))
        assertEquals(GuideGroupKey.All, decodeGuideGroup("garbage"))
    }

    @Test fun reminderForUsesChannelAndProgramme() {
        val program = EpgProgram("xml.id", 1_000, 2_000, "Movie")
        assertEquals(
            Reminder("1", "epg-1", "Movie", 1_000, 2_000, "Name 1"),
            reminderFor(ch("1", "News"), program),
        )
        // Channel without an EPG id falls back to the programme's channel id.
        assertEquals("xml.id", reminderFor(ch("2", null, epg = null), program).epgId)
    }
}
