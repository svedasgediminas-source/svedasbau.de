package app.novaiptv.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.novaiptv.core.data.db.ChannelEntity
import app.novaiptv.core.data.db.NovaDatabase
import app.novaiptv.core.data.db.PlaylistEntity
import app.novaiptv.core.data.db.PlaylistTypes
import app.novaiptv.core.data.db.ProgramEntity
import app.novaiptv.core.data.db.StreamEmbedded
import app.novaiptv.core.data.db.WatchHistoryEntity
import app.novaiptv.core.data.logic.SearchText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumented DAO tests (in-memory database). Requires androidx.test runner/core/ext-junit. */
@RunWith(AndroidJUnit4::class)
class NovaDatabaseTest {
    private lateinit var db: NovaDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NovaDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun playlist(): Long =
        db.playlistDao().insert(PlaylistEntity(name = "P", type = PlaylistTypes.M3U_URL, url = "http://x/p.m3u"))

    private fun channel(pid: Long, id: String, name: String, group: String, index: Int) = ChannelEntity(
        id = "$pid:$id",
        playlistId = pid,
        number = index + 1,
        name = name,
        nameNormalized = SearchText.normalize(name),
        groupName = group,
        logoUrl = null,
        epgId = "$id.tv",
        stream = StreamEmbedded("http://s/$id.m3u8"),
        sortIndex = index,
    )

    @Test
    fun replaceKeepsManualMappingAndGroupsFollowFirstSortIndex() = runBlocking {
        val pid = playlist()
        val dao = db.channelDao()
        dao.replaceForPlaylist(pid, listOf(channel(pid, "a", "A", "News", 0), channel(pid, "b", "B", "Sport", 1), channel(pid, "c", "C", "News", 2)))
        dao.setManualEpgId("$pid:a", "manual.tv")
        dao.replaceForPlaylist(pid, listOf(channel(pid, "b", "B", "Sport", 0), channel(pid, "a", "A", "News", 1)))
        assertEquals("manual.tv", dao.get("$pid:a")!!.manualEpgId)
        assertNull(dao.get("$pid:c"))
        val groups = dao.observeGroups(pid).first()
        assertEquals(listOf("Sport", "News"), groups.map { it.name })
    }

    @Test
    fun searchEscapesWildcards() = runBlocking {
        val pid = playlist()
        val dao = db.channelDao()
        dao.replaceForPlaylist(pid, listOf(channel(pid, "a", "100% Hits", "", 0), channel(pid, "b", "1000 Hits", "", 1), channel(pid, "c", "Sport_1", "", 2), channel(pid, "d", "SportX1", "", 3)))
        val pct = dao.search(pid, SearchText.containsPattern("100%")!!, SearchText.prefixPattern("100%"), 10)
        assertEquals(listOf("$pid:a"), pct.map { it.id })
        val us = dao.search(pid, SearchText.containsPattern("sport_1")!!, SearchText.prefixPattern("sport_1"), 10)
        assertEquals(listOf("$pid:c"), us.map { it.id })
    }

    @Test
    fun deleteFullyRemovesDependentRows() = runBlocking {
        val pid = playlist()
        db.channelDao().replaceForPlaylist(pid, listOf(channel(pid, "a", "A", "", 0)))
        db.favoriteDao().toggle("Channel", "$pid:a", pid, 1)
        db.historyDao().upsert(WatchHistoryEntity("Movie", "m", pid, "M", null, 1, 10, 1, null))
        db.playlistDao().deleteFully(pid)
        assertNull(db.channelDao().get("$pid:a"))
        assertTrue(db.favoriteDao().observeAll().first().isEmpty())
        assertNull(db.historyDao().get("Movie", "m"))
    }

    @Test
    fun favoritesToggleAndMove() = runBlocking {
        val pid = playlist()
        val dao = db.favoriteDao()
        assertTrue(dao.toggle("Channel", "a", pid, 1))
        assertTrue(dao.toggle("Channel", "b", pid, 2))
        assertTrue(dao.toggle("Channel", "c", pid, 3))
        dao.move("Channel", "c", 0)
        assertEquals(listOf("c", "a", "b"), dao.observeByType("Channel").first().map { it.contentId })
        assertFalse(dao.toggle("Channel", "a", pid, 4))
        assertFalse(dao.observeExists("Channel", "a").first())
    }

    @Test
    fun programBatchReplacesOnlyClearedIds() = runBlocking {
        val dao = db.programDao()
        dao.replaceBatchBlocking(listOf("x"), listOf(ProgramEntity(epgId = "x", startMillis = 0, endMillis = 10, title = "old")))
        dao.replaceBatchBlocking(listOf("y"), listOf(ProgramEntity(epgId = "y", startMillis = 0, endMillis = 10, title = "y")))
        dao.replaceBatchBlocking(listOf("x"), listOf(ProgramEntity(epgId = "x", startMillis = 0, endMillis = 10, title = "new")))
        val rows = dao.range(listOf("x", "y"), 0, 100)
        assertEquals(listOf("new", "y"), rows.map { it.title })
    }
}
