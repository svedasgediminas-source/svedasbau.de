package app.novaiptv.feature.guide

import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class GuideLogicTest {
    private val vilnius = ZoneId.of("Europe/Vilnius")
    private val utc = ZoneOffset.UTC

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = utc): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun prog(start: Long, end: Long, title: String = "P") = EpgProgram("ch", start, end, title)

    private fun channel(id: String, catchup: CatchupCapability = CatchupCapability.None) =
        Channel(id, 1L, 1, id, null, null, "epg-$id", StreamSource("http://x/$id"), catchup)

    @Test fun normalDayIs24Hours() {
        val day = guideDay(LocalDate.of(2026, 10, 10), vilnius)
        assertEquals(DAY_MILLIS, day.lengthMillis)
        assertEquals(at(2026, 10, 9, 21), day.startMillis) // UTC+3 in summer
    }

    @Test fun springForwardDayIs23Hours() {
        // EU DST starts last Sunday of March 2026 = 29 March.
        val day = guideDay(LocalDate.of(2026, 3, 29), vilnius)
        assertEquals(23 * 60 * MINUTE_MILLIS, day.lengthMillis)
    }

    @Test fun fallBackDayIs25Hours() {
        // EU DST ends last Sunday of October 2026 = 25 October.
        val day = guideDay(LocalDate.of(2026, 10, 25), vilnius)
        assertEquals(25 * 60 * MINUTE_MILLIS, day.lengthMillis)
        val cells = buildRowCells(emptyList(), day)
        assertEquals(50, cells.size) // 25 h of 30-minute gap slots
        assertEquals(day.startMillis, cells.first().start)
        assertEquals(day.endMillis, cells.last().end)
    }

    @Test fun consecutiveDaysAreContiguousAcrossDst() {
        val days = buildGuideDays(at(2026, 10, 24, 12), vilnius, pastDays = 2, futureDays = 3)
        assertEquals(6, days.size)
        days.zipWithNext().forEach { (a, b) -> assertEquals(a.endMillis, b.startMillis) }
        assertEquals(LocalDate.of(2026, 10, 22), days.first().date)
        assertEquals(LocalDate.of(2026, 10, 27), days.last().date)
    }

    @Test fun dayListUsesMaxCatchupDaysCapped() {
        val chs = listOf(
            channel("a"),
            channel("b", CatchupCapability.Supported(CatchupType.Default, 3)),
            channel("c", CatchupCapability.Supported(CatchupType.Xtream, 5)),
        )
        assertEquals(5, maxCatchupDays(chs))
        assertEquals(0, maxCatchupDays(listOf(channel("a"))))
        assertEquals(MAX_PAST_DAYS, maxCatchupDays(listOf(channel("x", CatchupCapability.Supported(CatchupType.Default, 365)))))
        assertEquals(1 + 5 + FUTURE_DAYS, buildGuideDays(at(2026, 10, 10, 12), utc, 5).size)
    }

    @Test fun sameLocalTimeOnOtherDay() {
        val t = at(2026, 10, 10, 20, 15, vilnius)
        val moved = sameLocalTimeOn(t, LocalDate.of(2026, 10, 12), vilnius)
        assertEquals(at(2026, 10, 12, 20, 15, vilnius), moved)
    }

    @Test fun rowCellsFillGapsAndClipToDay() {
        val day = guideDay(LocalDate.of(2026, 10, 10), utc)
        val p1 = prog(at(2026, 10, 9, 23), at(2026, 10, 10, 1)) // crosses midnight
        val p2 = prog(at(2026, 10, 10, 2, 10), at(2026, 10, 10, 3))
        val p3 = prog(at(2026, 10, 10, 23), at(2026, 10, 11, 2))
        val cells = buildRowCells(listOf(p3, p2, p1), day)
        assertEquals(day.startMillis, cells.first().start)
        assertEquals(day.endMillis, cells.last().end)
        cells.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        val first = cells.first() as GuideCell.Program
        assertEquals(p1, first.program)
        assertEquals(day.startMillis, first.start)
        // gap 01:00-02:10 split into 01:00-01:30, 01:30-02:00, 02:00-02:10
        assertEquals(GuideCell.Gap(at(2026, 10, 10, 1), at(2026, 10, 10, 1, 30)), cells[1])
        assertEquals(GuideCell.Gap(at(2026, 10, 10, 2), at(2026, 10, 10, 2, 10)), cells[3])
        assertTrue(cells[4] is GuideCell.Program)
        assertEquals(day.endMillis, (cells.last() as GuideCell.Program).end)
    }

    @Test fun overlappingProgramsAreTrimmed() {
        val day = guideDay(LocalDate.of(2026, 10, 10), utc)
        val a = prog(at(2026, 10, 10, 10), at(2026, 10, 10, 11), "A")
        val b = prog(at(2026, 10, 10, 10, 30), at(2026, 10, 10, 11, 30), "B")
        val c = prog(at(2026, 10, 10, 10, 40), at(2026, 10, 10, 11), "C") // fully covered -> dropped
        val cells = buildRowCells(listOf(a, b, c), day).filterIsInstance<GuideCell.Program>()
        assertEquals(listOf("A", "B"), cells.map { it.program.title })
        assertEquals(at(2026, 10, 10, 11), cells[1].start)
    }

    @Test fun cellIndexLookup() {
        val day = guideDay(LocalDate.of(2026, 10, 10), utc)
        val cells = buildRowCells(listOf(prog(at(2026, 10, 10, 10), at(2026, 10, 10, 12))), day)
        val i = cellIndexAt(cells, at(2026, 10, 10, 11))
        assertTrue(cells[i] is GuideCell.Program)
        assertEquals(0, cellIndexAt(cells, day.startMillis - 1))
        assertEquals(cells.lastIndex, cellIndexAt(cells, day.endMillis + 5))
        assertEquals(-1, cellIndexAt(emptyList(), 0))
    }

    @Test fun placementClipsToWindow() {
        val vs = at(2026, 10, 10, 10)
        val ve = at(2026, 10, 10, 12)
        val p = placeCell(at(2026, 10, 10, 9), at(2026, 10, 10, 10, 30), vs, ve, 5f)!!
        assertEquals(0f, p.offsetDp, 0.001f)
        assertEquals(150f, p.widthDp, 0.001f)
        val q = placeCell(at(2026, 10, 10, 11, 30), at(2026, 10, 10, 13), vs, ve, 5f)!!
        assertEquals(450f, q.offsetDp, 0.001f)
        assertEquals(150f, q.widthDp, 0.001f)
        assertNull(placeCell(at(2026, 10, 10, 12), at(2026, 10, 10, 13), vs, ve, 5f))
    }

    @Test fun slotHelpers() {
        val day = guideDay(LocalDate.of(2026, 10, 10), utc)
        assertEquals(at(2026, 10, 10, 10, 30), floorToSlot(at(2026, 10, 10, 10, 47), day.startMillis))
        val ticks = slotTicks(day, at(2026, 10, 10, 10, 10), at(2026, 10, 10, 11, 31))
        assertEquals(listOf(at(2026, 10, 10, 10, 30), at(2026, 10, 10, 11), at(2026, 10, 10, 11, 30)), ticks)
    }

    @Test fun horizontalNavigationScrollsWindow() {
        val day = guideDay(LocalDate.of(2026, 10, 10), utc)
        val span = 2 * 60 * MINUTE_MILLIS
        val programs = (0 until 24).map { h -> prog(at(2026, 10, 10, h), at(2026, 10, 10, h) + 60 * MINUTE_MILLIS, "H$h") }
        val cells = buildRowCells(programs, day)
        var cur = GuideCursor(row = 0, focusMillis = at(2026, 10, 10, 10, 15), viewStart = at(2026, 10, 10, 10))
        cur = moveHorizontal(cells, cur, +1, span, day)!! // 11:00, visible
        assertEquals(at(2026, 10, 10, 11), cur.focusMillis)
        assertEquals(at(2026, 10, 10, 10), cur.viewStart)
        cur = moveHorizontal(cells, cur, +1, span, day)!! // 12:00 is the window end -> scroll
        assertEquals(at(2026, 10, 10, 12), cur.focusMillis)
        assertTrue(cur.viewStart <= cur.focusMillis && cur.focusMillis < cur.viewStart + span)
        assertEquals(at(2026, 10, 10, 11, 30), cur.viewStart)
        // back left twice
        cur = moveHorizontal(cells, cur, -1, span, day)!!
        cur = moveHorizontal(cells, cur, -1, span, day)!!
        assertEquals(at(2026, 10, 10, 10), cur.focusMillis)
        assertEquals(at(2026, 10, 10, 10), cur.viewStart)
        // edges
        val start = GuideCursor(0, day.startMillis, day.startMillis)
        assertNull(moveHorizontal(cells, start, -1, span, day))
        val end = GuideCursor(0, day.endMillis - 1, day.endMillis - span)
        assertNull(moveHorizontal(cells, end, +1, span, day))
    }

    @Test fun longProgramStartingBeforeWindowKeepsAnchorVisible() {
        val day = guideDay(LocalDate.of(2026, 10, 10), utc)
        val span = 2 * 60 * MINUTE_MILLIS
        val cells = buildRowCells(
            listOf(prog(at(2026, 10, 10, 6), at(2026, 10, 10, 12)), prog(at(2026, 10, 10, 12), at(2026, 10, 10, 13))),
            day,
        )
        val cur = GuideCursor(0, at(2026, 10, 10, 12), at(2026, 10, 10, 11))
        val left = moveHorizontal(cells, cur, -1, span, day)!!
        assertEquals(at(2026, 10, 10, 6), left.viewStart)
        assertEquals(at(2026, 10, 10, 6), left.focusMillis)
    }

    @Test fun verticalNavigationKeepsAnchor() {
        val c = GuideCursor(3, 1234L, 1000L)
        assertEquals(GuideCursor(4, 1234L, 1000L), moveVertical(c, +1, 10))
        assertNull(moveVertical(GuideCursor(0, 1, 1), -1, 10))
        assertNull(moveVertical(GuideCursor(9, 1, 1), +1, 10))
    }

    @Test fun viewStartClamped() {
        val day = guideDay(LocalDate.of(2026, 10, 10), utc)
        val span = 2 * 60 * MINUTE_MILLIS
        assertEquals(day.startMillis, initialViewStart(day.startMillis + 5 * MINUTE_MILLIS, span, day))
        assertEquals(day.endMillis - span, initialViewStart(day.endMillis - 1, span, day))
        assertEquals(at(2026, 10, 10, 13, 30), initialViewStart(at(2026, 10, 10, 14, 20), span, day))
    }

    @Test fun catchupDecisions() {
        val now = at(2026, 10, 10, 20)
        val archive = CatchupCapability.Supported(CatchupType.Default, days = 3)
        val live = decideGuideAction(archive, at(2026, 10, 10, 19, 30), at(2026, 10, 10, 20, 30), now)
        assertEquals(GuideAction.PlayLive, live)
        assertEquals(GuideAction.Upcoming, decideGuideAction(archive, at(2026, 10, 10, 21), at(2026, 10, 10, 22), now))
        assertEquals(GuideAction.PlayCatchup, decideGuideAction(archive, at(2026, 10, 8, 21), at(2026, 10, 8, 22), now))
        assertEquals(GuideAction.ArchiveExpired, decideGuideAction(archive, at(2026, 10, 6, 21), at(2026, 10, 6, 22), now))
        assertEquals(GuideAction.ArchiveUnavailable, decideGuideAction(CatchupCapability.None, at(2026, 10, 10, 18), at(2026, 10, 10, 19), now))
        assertEquals(
            GuideAction.ArchiveUnavailable,
            decideGuideAction(CatchupCapability.Supported(CatchupType.Default, 0), at(2026, 10, 10, 18), at(2026, 10, 10, 19), now),
        )
        // boundary: a programme that ended exactly now is past
        assertTrue(isInArchive(archive, at(2026, 10, 10, 19), now, now))
        assertFalse(isInArchive(archive, at(2026, 10, 10, 19), now + 1, now))
    }

    @Test fun clockFormatting() {
        val t = at(2026, 10, 10, 18, 5)
        assertEquals("18:05", ClockFormat(Locale.GERMANY, true, utc).time(t))
        assertEquals("21:05", ClockFormat(Locale.GERMANY, true, vilnius).time(t))
        assertTrue(ClockFormat(Locale.US, false, utc).time(t).startsWith("6:05"))
        assertEquals("18:05 – 19:00", ClockFormat(Locale.UK, true, utc).range(t, at(2026, 10, 10, 19)))
    }

    @Test fun relativeDays() {
        val now = at(2026, 10, 10, 23, 30, vilnius)
        assertEquals(0, relativeDayOffset(LocalDate.of(2026, 10, 10), now, vilnius))
        assertEquals(-1, relativeDayOffset(LocalDate.of(2026, 10, 9), now, vilnius))
        assertEquals(1, relativeDayOffset(LocalDate.of(2026, 10, 11), now, vilnius))
        assertNull(relativeDayOffset(LocalDate.of(2026, 10, 14), now, vilnius))
    }
}
