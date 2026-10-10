package app.novaiptv.core.data

import app.novaiptv.core.data.db.ChannelListRow
import app.novaiptv.core.data.mapper.toListItem
import app.novaiptv.core.domain.ChannelListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChannelListRowMapperTest {
    private fun row(
        id: String = "1:a",
        group: String = "News",
        epg: String? = "tvg",
        resolved: String? = null,
        manual: String? = null,
        catchupType: String? = null,
        days: Int = 0,
    ) = ChannelListRow(id, 1L, 5, "Name", group, "http://logo", epg, resolved, manual, catchupType, days, false)

    @Test fun mapsRowFieldsAndEffectiveEpgId() {
        assertEquals(
            ChannelListItem("1:a", 1L, 5, "Name", "News", "http://logo", "m", hasCatchup = true, catchupDays = 2, isAdult = false),
            row(resolved = "r", manual = "m", catchupType = "Xtream", days = 2).toListItem(),
        )
        assertEquals("r", row(resolved = "r", manual = " ").toListItem().epgId)
        assertNull(row(epg = "").toListItem().epgId)
    }

    @Test fun emptyGroupIsNullAndUnknownCatchupTypeIsNoCatchup() {
        assertNull(row(group = "").toListItem().groupName)
        assertFalse(row(catchupType = "bogus", days = 3).toListItem().hasCatchup)
        assertFalse(row().toListItem().hasCatchup)
    }

    @Test fun groupPoolSharesInstances() {
        val pool = HashMap<String, String>()
        val a = row(id = "a", group = StringBuilder("Sp").append("ort").toString()).toListItem(pool)
        val b = row(id = "b", group = StringBuilder("Spo").append("rt").toString()).toListItem(pool)
        assertSame(a.groupName, b.groupName)
    }
}
