package app.novaiptv.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRulesTest {
    private val min = 60_000L

    private fun r(ch: String, start: Long, end: Long = start + 30 * min, title: String = "T $ch", epg: String? = "epg.$ch") =
        Reminder(ch, epg, title, start, end, "Channel $ch")

    @Test
    fun dueFromOneMinuteBeforeStartUntilEnd() {
        val rem = r("a", start = 100 * min, end = 130 * min)
        assertFalse(ReminderRules.isDue(rem, 98 * min))
        assertFalse(ReminderRules.isDue(rem, 99 * min - 1))
        assertTrue(ReminderRules.isDue(rem, 99 * min))
        assertTrue(ReminderRules.isDue(rem, 100 * min))
        assertTrue(ReminderRules.isDue(rem, 130 * min - 1))
        assertFalse(ReminderRules.isDue(rem, 130 * min))
    }

    @Test
    fun nextDuePicksEarliestStart() {
        val list = listOf(r("b", 105 * min), r("a", 100 * min), r("c", 500 * min))
        assertEquals("a", ReminderRules.nextDue(list, 104 * min)?.channelId)
        assertNull(ReminderRules.nextDue(list, 50 * min))
        assertEquals("b", ReminderRules.nextDue(list.filterNot { it.channelId == "a" }, 104 * min)?.channelId)
    }

    @Test
    fun pruneDropsExpiredInvalidAndDuplicatesAndSorts() {
        val now = 200 * min
        val list = listOf(
            r("late", 400 * min),
            r("old", 100 * min, end = 150 * min), // ended
            r("running", 190 * min, end = 220 * min), // still running: kept
            r("bad", 300 * min, end = 300 * min), // empty span
            r("late", 400 * min, title = "Updated"), // duplicate key: last wins
            Reminder("", null, "no channel", 300 * min, 310 * min),
        )
        val pruned = ReminderRules.prune(list, now)
        assertEquals(listOf("running", "late"), pruned.map { it.channelId })
        assertEquals("Updated", pruned.last().title)
    }

    @Test
    fun pruneCapsSize() {
        val list = (0 until ReminderRules.MAX_REMINDERS + 20).map { r("c$it", (1000L + it) * min) }
        val pruned = ReminderRules.prune(list, 0)
        assertEquals(ReminderRules.MAX_REMINDERS, pruned.size)
        assertEquals("c0", pruned.first().channelId)
    }

    @Test
    fun addAndRemove() {
        val now = 0L
        var list = ReminderRules.add(emptyList(), r("a", 100 * min), now)
        list = ReminderRules.add(list, r("b", 50 * min), now)
        list = ReminderRules.add(list, r("a", 100 * min, title = "again"), now)
        assertEquals(listOf("b", "a"), list.map { it.channelId })
        assertEquals("again", list.last().title)
        list = ReminderRules.remove(list, "b", 50 * min, now)
        assertEquals(listOf("a"), list.map { it.channelId })
        // Wrong start: nothing removed.
        assertEquals(1, ReminderRules.remove(list, "a", 1, now).size)
        // Adding an ended programme is a no-op.
        assertEquals(1, ReminderRules.add(list, r("z", 1, end = 2), 10).size)
    }

    @Test
    fun encodeDecodeRoundTripWithSpecialCharacters() {
        val list = listOf(
            Reminder("ch\t1", "e\\pg", "News\nat\r10 \\t", 100, 200, "Ch\tOne"),
            Reminder("ch2", null, "", 300, 400, ""),
            Reminder("ch3", "", "Ünïcødé – Кино", 500, 600, "ЛТВ"),
        )
        val decoded = ReminderRules.decode(ReminderRules.encode(list))
        assertEquals(list, decoded)
        assertEquals(emptyList<Reminder>(), ReminderRules.decode(ReminderRules.encode(emptyList())))
    }

    @Test
    fun decodeToleratesGarbage() {
        assertTrue(ReminderRules.decode(null).isEmpty())
        assertTrue(ReminderRules.decode("").isEmpty())
        assertTrue(ReminderRules.decode("v9\na\tb").isEmpty())
        val text = "v1\nonly\tthree\tfields\nch\t\t0\tTitle\tx\t2\tName\nch\tepg\t1\tTitle\t1\t2\tName\n\n"
        assertEquals(listOf(Reminder("ch", "epg", "Title", 1, 2, "Name")), ReminderRules.decode(text))
    }

    @Test
    fun keyAndDefaultIsReminded() = runTest {
        val rem = r("a", 100)
        assertEquals("a|100", rem.key)
        val repo = object : ReminderRepository {
            val state = MutableStateFlow(listOf(rem))
            override fun observeReminders(): Flow<List<Reminder>> = state
            override suspend fun add(reminder: Reminder) = Unit
            override suspend fun remove(channelId: String, startMillis: Long) = Unit
        }
        assertTrue(repo.isReminded("a", 100))
        assertFalse(repo.isReminded("a", 101))
        assertFalse(repo.isReminded("b", 100))
    }
}
