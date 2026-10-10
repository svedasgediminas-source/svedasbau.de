package app.novaiptv.feature.home

import app.novaiptv.core.domain.ChannelListItem
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ChannelGroup
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.NowNext
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.model.WatchHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {
    private val now = 1_000_000_000L

    private fun hc(id: String, epg: String? = "epg-$id", adult: Boolean = false, group: String? = null) =
        HomeChannel(id, 1L, 1, "Channel $id", group, null, epg, adult)

    private fun item(id: String, group: String = "G", adult: Boolean = false) =
        ChannelListItem(id, 1L, 1, id, group, null, "epg-$id", hasCatchup = false, catchupDays = 0, isAdult = adult)

    private fun full(id: String, group: String? = null, adult: Boolean = false) =
        Channel(id, 1L, 1, id, group, null, "epg-$id", StreamSource("http://x/$id"), isAdult = adult)

    private fun airing(epg: String, start: Long = now - 600_000, end: Long = now + 600_000) =
        NowNext(EpgProgram(epg, start, end, "Show on $epg"), null)

    private fun watched(id: String, at: Long, type: ContentType = ContentType.Channel) =
        WatchHistory(type, id, 1L, id, null, 0, 0, at)

    // --- groups ---

    @Test fun selectGroupsTakesLargestKeepsPlaylistOrder() {
        val groups = listOf(
            ChannelGroup(1, "News", 3),
            ChannelGroup(1, "Sport", 10),
            ChannelGroup(1, "Kids", 1),
            ChannelGroup(1, "Movies", 7),
        )
        val picked = HomeLogic.selectGroups(groups, hideAdult = true, max = 2)
        assertEquals(listOf("Sport", "Movies"), picked.map { it.name })
    }

    @Test fun selectGroupsTiesUsePlaylistOrderAndCapAtSix() {
        val groups = (1..9).map { ChannelGroup(1, "G$it", 5) }
        val picked = HomeLogic.selectGroups(groups, hideAdult = false)
        assertEquals((1..6).map { "G$it" }, picked.map { it.name })
    }

    @Test fun selectGroupsDropsAdultEmptyAndDuplicateGroups() {
        val groups = listOf(
            ChannelGroup(1, "XXX Adult", 50),
            ChannelGroup(1, "Empty", 0),
            ChannelGroup(1, "News", 4),
            ChannelGroup(1, "News", 4),
            ChannelGroup(1, "", 2),
        )
        assertEquals(listOf("News", ""), HomeLogic.selectGroups(groups, hideAdult = true).map { it.name })
        assertEquals(listOf("XXX Adult", "News", ""), HomeLogic.selectGroups(groups, hideAdult = false).map { it.name })
    }

    @Test fun singleGroupGivesNoGroupRails() {
        assertTrue(HomeLogic.selectGroups(listOf(ChannelGroup(1, "All", 22)), hideAdult = true).isEmpty())
        assertTrue(HomeLogic.selectGroups(emptyList(), hideAdult = true).isEmpty())
    }

    @Test fun capGroupRailFiltersAdultAndLimits() {
        val items = listOf(item("a"), item("b", adult = true), item("c")) + (1..30).map { item("x$it") }
        val rail = HomeLogic.capGroupRail(items, hideAdult = true, limit = 20)
        assertEquals(20, rail.size)
        assertEquals(listOf("a", "c", "x1"), rail.take(3).map { it.id })
        assertEquals("b", HomeLogic.capGroupRail(items, hideAdult = false, limit = 2)[1].id)
    }

    @Test fun visibleChannelsHidesAdultByFlagOrGroupName() {
        val list = listOf(full("a"), full("b", adult = true), full("c", group = "18+ Night"), full("d"))
        assertEquals(listOf("a", "d"), HomeLogic.visibleChannels(list, hideAdult = true).map { it.id })
        assertEquals(listOf("a"), HomeLogic.visibleChannels(list, hideAdult = true, limit = 1).map { it.id })
        assertEquals(4, HomeLogic.visibleChannels(list, hideAdult = false).size)
    }

    // --- EPG ids ---

    @Test fun epgIdsAreDistinctNonBlankAndCapped() {
        val ids = HomeLogic.epgIds(
            listOf(
                listOf(hc("a"), hc("b", epg = null), hc("c", epg = " ")),
                listOf(hc("a"), hc("d")),
            ),
        )
        assertEquals(listOf("epg-a", "epg-d"), ids)
        val many = listOf((1..50).map { hc("n$it") })
        assertEquals(10, HomeLogic.epgIds(many, max = 10).size)
    }

    // --- programmes ---

    @Test fun currentProgramRequiresLiveProgramme() {
        assertEquals("Show on e", HomeLogic.currentProgram(airing("e"), now)?.title)
        assertNull(HomeLogic.currentProgram(airing("e", start = now - 2000, end = now - 1000), now))
        assertNull(HomeLogic.currentProgram(NowNext(null, null), now))
        assertNull(HomeLogic.currentProgram(null, now))
    }

    @Test fun progressIsClampedAndNullWithoutDuration() {
        val p = EpgProgram("e", now - 500, now + 500, "x")
        assertEquals(0.5f, HomeLogic.progress(p, now)!!, 0.001f)
        assertEquals(1f, HomeLogic.progress(p, now + 10_000)!!, 0.001f)
        assertNull(HomeLogic.progress(EpgProgram("e", now, now, "x"), now))
        assertNull(HomeLogic.progress(null, now))
    }

    @Test fun mostWatchedCountsThenRecency() {
        val history = listOf(
            watched("a", 10), watched("b", 50), watched("a", 20), watched("c", 40),
            watched("m", 100, ContentType.Movie),
        )
        assertEquals(listOf("a", "b", "c"), HomeLogic.mostWatchedChannelIds(history))
    }

    // --- live hero ---

    @Test fun liveHeroPrefersAiringFavourite() {
        val favs = listOf(hc("f1"), hc("f2"))
        val nn = mapOf("epg-f2" to airing("epg-f2"), "epg-o1" to airing("epg-o1"))
        val hero = HomeLogic.pickLiveHero(favs, emptyList(), emptyList(), listOf(hc("o1")), nn, now, hideAdult = true)
        assertEquals("f2", hero?.id)
    }

    @Test fun liveHeroThenMostWatchedThenOthers() {
        val recent = listOf(hc("r1"), hc("r2"))
        val history = listOf(watched("r2", 30), watched("r2", 20), watched("r1", 40))
        val others = listOf(hc("o1"), hc("r2"))
        val nn = mapOf("epg-r2" to airing("epg-r2"), "epg-o1" to airing("epg-o1"))
        assertEquals("r2", HomeLogic.pickLiveHero(emptyList(), recent, history, others, nn, now, true)?.id)
        // Most watched not airing -> first other channel that airs.
        val nn2 = mapOf("epg-o1" to airing("epg-o1"))
        assertEquals("o1", HomeLogic.pickLiveHero(emptyList(), recent, history, others, nn2, now, true)?.id)
    }

    @Test fun liveHeroFallsBackWithoutEpg() {
        val others = listOf(hc("o1"), hc("o2"))
        assertEquals("o1", HomeLogic.pickLiveHero(emptyList(), emptyList(), emptyList(), others, emptyMap(), now, true)?.id)
        assertEquals("f", HomeLogic.pickLiveHero(listOf(hc("f")), emptyList(), emptyList(), others, emptyMap(), now, true)?.id)
        assertNull(HomeLogic.pickLiveHero(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), now, true))
    }

    @Test fun liveHeroSkipsAdultWhenHidden() {
        val others = listOf(hc("x", adult = true), hc("o"))
        val nn = mapOf("epg-x" to airing("epg-x"))
        assertEquals("o", HomeLogic.pickLiveHero(emptyList(), emptyList(), emptyList(), others, nn, now, true)?.id)
        assertEquals("x", HomeLogic.pickLiveHero(emptyList(), emptyList(), emptyList(), others, nn, now, false)?.id)
    }

    @Test fun historyOfUnknownChannelIsIgnored() {
        val history = listOf(watched("gone", 99), watched("gone", 98))
        assertEquals("o", HomeLogic.pickLiveHero(emptyList(), emptyList(), history, listOf(hc("o")), emptyMap(), now, true)?.id)
    }

    @Test fun channelNowProgressUsesCurrentProgramme() {
        val cn = ChannelNow(hc("a"), EpgProgram("epg-a", now - 250, now + 750, "x"), null)
        assertEquals(0.25f, cn.progress(now)!!, 0.001f)
        assertNull(ChannelNow(hc("a"), null, null).progress(now))
    }

    @Test fun adultGroupNameMarksHomeChannelAdult() {
        assertTrue(item("a", group = "XXX").toHomeChannel().isAdult)
        assertTrue(full("b", group = "Adult").toHomeChannel().isAdult)
    }
}
