package app.novaiptv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class XmltvTimeTest {
    private fun utc(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun offsets() {
        assertEquals(utc("2024-01-01T10:00:00Z"), XmltvTime.parse("20240101120000 +0200"))
        assertEquals(utc("2024-01-01T10:00:00Z"), XmltvTime.parse("20240101120000+0200"))
        assertEquals(utc("2024-01-01T17:00:00Z"), XmltvTime.parse("20240101120000 -0500"))
        assertEquals(utc("2024-01-01T10:00:00Z"), XmltvTime.parse("20240101120000 +02:00"))
        assertEquals(utc("2024-01-01T06:30:00Z"), XmltvTime.parse("20240101120000 +0530"))
    }

    @Test fun noOffsetIsUtcAndShortForms() {
        assertEquals(utc("2024-01-01T12:00:00Z"), XmltvTime.parse("20240101120000"))
        assertEquals(utc("2024-01-01T12:00:00Z"), XmltvTime.parse("202401011200"))
        assertEquals(utc("2024-01-01T12:00:00Z"), XmltvTime.parse("20240101120000 Z"))
        assertEquals(utc("2024-01-01T12:00:00Z"), XmltvTime.parse("  20240101120000   +0000  "))
        assertEquals(utc("2024-01-01T12:00:00Z"), XmltvTime.parse("20240101120000.000 +0000"))
        assertEquals(utc("2024-01-01T10:00:00Z"), XmltvTime.parse("202401011200 +0200"))
    }

    @Test fun malformed() {
        assertNull(XmltvTime.parse(null))
        assertNull(XmltvTime.parse(""))
        assertNull(XmltvTime.parse("garbage"))
        assertNull(XmltvTime.parse("20241301120000 +0000")) // month 13
        assertNull(XmltvTime.parse("20240230120000 +0000")) // Feb 30
        assertNull(XmltvTime.parse("20240101250000 +0000")) // hour 25
        assertNull(XmltvTime.parse("2024010112 +0000"))
        assertNull(XmltvTime.parse("20240101120000 +2500"))
        assertNull(XmltvTime.parse("20240101120000 +02"))
    }

    @Test fun dstBoundariesVilnius() {
        val vilnius = ZoneId.of("Europe/Vilnius")
        fun local(s: String) = LocalDateTime.parse(s).atZone(vilnius).toInstant().toEpochMilli()
        // Spring forward 2024-03-31 03:00 EET -> 04:00 EEST.
        assertEquals(utc("2024-03-31T00:00:00Z"), XmltvTime.parse("20240331020000 +0200"))
        assertEquals(local("2024-03-31T02:00"), XmltvTime.parse("20240331020000 +0200"))
        assertEquals(utc("2024-03-31T02:00:00Z"), XmltvTime.parse("20240331050000 +0300"))
        assertEquals(local("2024-03-31T05:00"), XmltvTime.parse("20240331050000 +0300"))
        // Fall back 2024-10-27 04:00 EEST -> 03:00 EET: 03:00 local occurs twice.
        val firstThree = XmltvTime.parse("20241027030000 +0300")!!
        val secondThree = XmltvTime.parse("20241027030000 +0200")!!
        assertEquals(utc("2024-10-27T00:00:00Z"), firstThree)
        assertEquals(utc("2024-10-27T01:00:00Z"), secondThree)
        assertEquals(3_600_000L, secondThree - firstThree)
    }
}
