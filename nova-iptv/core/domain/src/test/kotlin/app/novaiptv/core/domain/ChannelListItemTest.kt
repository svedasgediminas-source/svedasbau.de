package app.novaiptv.core.domain

import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ChannelGroup
import app.novaiptv.core.model.StreamSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelListItemTest {
    private fun ch(id: String, epg: String? = null, group: String? = null) =
        Channel(id, 1L, id.length, "Ch $id", group, null, epg, StreamSource("http://x/$id"))

    /** Implements only the abstract members, so the default implementations are exercised. */
    private class ListOnlyRepo(private val all: List<Channel>) : ChannelRepository {
        override fun observeGroups(playlistId: Long): Flow<List<ChannelGroup>> = flowOf(emptyList())
        override fun observeChannels(playlistId: Long, group: String?): Flow<List<Channel>> =
            flowOf(all.filter { it.playlistId == playlistId && (group == null || it.groupName == group) })
        override fun observeChannelsByIds(ids: List<String>): Flow<List<Channel>> = flowOf(all.filter { it.id in ids })
        override suspend fun getChannel(id: String): Channel? = all.firstOrNull { it.id == id }
        override suspend fun adjacentChannel(current: Channel, group: String?, offset: Int): Channel? = null
        override suspend fun findByNumber(playlistId: Long, number: Int): Channel? = null
        override suspend fun search(playlistId: Long, query: String, limit: Int): List<Channel> = emptyList()
    }

    @Test fun toListItemKeepsRowFields() {
        val c = Channel(
            "p:1", 7L, 42, "News", "Info", "http://logo", "news.de", StreamSource("http://x"),
            catchup = CatchupCapability.Supported(CatchupType.Xtream, 3),
            isAdult = true,
            extras = mapOf("k" to "v"),
        )
        assertEquals(
            ChannelListItem("p:1", 7L, 42, "News", "Info", "http://logo", "news.de", hasCatchup = true, catchupDays = 3, isAdult = true),
            c.toListItem(),
        )
        val plain = ch("a").toListItem()
        assertFalse(plain.hasCatchup)
        assertEquals(0, plain.catchupDays)
    }

    @Test fun defaultObserveChannelItemsAndFirst() = runTest {
        val repo = ListOnlyRepo(listOf(ch("a", group = "G"), ch("bb"), ch("ccc", group = "G")))
        assertEquals(listOf("a", "bb", "ccc"), repo.observeChannelItems(1L, null).first().map { it.id })
        assertEquals(listOf("a", "ccc"), repo.observeChannelItems(1L, "G").first().map { it.id })
        assertEquals(listOf("a", "bb"), repo.observeFirstChannels(1L, 2).first().map { it.id })
        assertTrue(repo.observeFirstChannels(1L, -1).first().isEmpty())
    }

    @Test fun defaultFindByEpgIdsKeepsPlaylistOrder() = runTest {
        val repo = ListOnlyRepo(listOf(ch("a", "x"), ch("bb"), ch("ccc", "y"), ch("dddd", "x")))
        assertEquals(listOf("a", "ccc", "dddd"), repo.findByEpgIds(1L, listOf("y", "x")).map { it.id })
        assertTrue(repo.findByEpgIds(1L, emptyList()).isEmpty())
        assertTrue(repo.findByEpgIds(2L, listOf("x")).isEmpty())
    }
}
