package app.novaiptv.data.epg

import app.novaiptv.core.common.net.HttpDownloader
import app.novaiptv.core.model.EpgChannel
import app.novaiptv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

class XmltvParserTest {
    private val parser = XmltvParser { KXmlParser().apply { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) } }

    private class Result(val stats: XmltvStats, val channels: List<EpgChannel>, val programs: List<EpgProgram>)

    private fun parse(xml: String, shift: Int = 0): Result = parse(ByteArrayInputStream(xml.toByteArray()), shift)

    private fun parse(input: InputStream, shift: Int = 0): Result {
        val ch = ArrayList<EpgChannel>()
        val pr = ArrayList<EpgProgram>()
        val stats = parser.parse(input, { ch += it }, { pr += it }, shift)
        return Result(stats, ch, pr)
    }

    private fun utc(s: String) = Instant.parse(s).toEpochMilli()

    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="test">
  <channel id="LRT.lt">
    <display-name lang="lt">LRT Televizija</display-name>
    <display-name lang="en">LRT TV</display-name>
    <icon src="http://img/lrt.png"/>
  </channel>
  <channel id="bbc1.uk">
    <display-name>BBC One</display-name>
  </channel>
  <programme start="20240101120000 +0200" stop="20240101130000 +0200" channel="LRT.lt">
    <title lang="lt">Žinios &amp; orai</title>
    <title lang="en">News &amp; weather</title>
    <desc lang="lt">Dienos naujienos</desc>
    <category lang="lt">Naujienos</category>
    <icon src="http://img/news.png"/>
    <episode-num system="xmltv_ns">0.4.0/1</episode-num>
  </programme>
  <programme start="20240101130000+0200" stop="20240101140000+0200" channel="LRT.lt">
    <title>Filmas</title>
    <episode-num system="xmltv_ns">1.2.</episode-num>
    <episode-num system="onscreen">S2 E3</episode-num>
  </programme>
  <programme start="20240101070000 -0500" stop="20240101080000 -0500" channel="bbc1.uk">
    <title>Breakfast</title>
    <unknown><nested>x</nested></unknown>
  </programme>
  <programme start="20240101130000" stop="20240101140000" channel="bbc1.uk">
    <title>Lunch</title>
  </programme>
</tv>
"""

    @Test fun parsesSample() {
        val r = parse(sample)
        assertNull(r.stats.error)
        assertEquals(XmltvStats(channels = 2, programs = 4, skipped = 0), r.stats)
        val lrt = r.channels[0]
        assertEquals("LRT.lt", lrt.id)
        assertEquals(listOf("LRT Televizija", "LRT TV"), lrt.displayNames)
        assertEquals("http://img/lrt.png", lrt.iconUrl)
        assertNull(r.channels[1].iconUrl)

        val p0 = r.programs[0]
        assertEquals("LRT.lt", p0.channelEpgId)
        assertEquals("Žinios & orai", p0.title)
        assertEquals("Dienos naujienos", p0.description)
        assertEquals("Naujienos", p0.category)
        assertEquals("http://img/news.png", p0.iconUrl)
        assertEquals("S01E05", p0.episodeNumber)
        assertEquals(utc("2024-01-01T10:00:00Z"), p0.startMillis)
        assertEquals(utc("2024-01-01T11:00:00Z"), p0.endMillis)

        assertEquals("S2 E3", r.programs[1].episodeNumber) // onscreen preferred
        assertEquals(utc("2024-01-01T11:00:00Z"), r.programs[1].startMillis)
        assertEquals(utc("2024-01-01T12:00:00Z"), r.programs[2].startMillis) // -0500
        assertEquals(utc("2024-01-01T13:00:00Z"), r.programs[3].startMillis) // no offset = UTC
        assertNull(r.programs[3].description)
    }

    @Test fun dstBoundaryProgrammes() {
        val xml = """<tv>
<programme start="20240331020000 +0200" stop="20240331050000 +0300" channel="a"><title>Over spring DST</title></programme>
<programme start="20241027030000 +0300" stop="20241027030000 +0200" channel="a"><title>Over autumn DST</title></programme>
</tv>"""
        val r = parse(xml)
        assertEquals(2, r.stats.programs)
        assertEquals(utc("2024-03-31T00:00:00Z"), r.programs[0].startMillis)
        assertEquals(2 * 3_600_000L, r.programs[0].durationMillis) // 02:00 -> 05:00 local is only 2h
        assertEquals(utc("2024-10-27T00:00:00Z"), r.programs[1].startMillis)
        assertEquals(3_600_000L, r.programs[1].durationMillis) // 03:00 -> 03:00 local is 1h
    }

    @Test fun malformedProgrammesAreSkippedAndCounted() {
        val xml = """<tv>
<programme start="garbage" stop="20240101130000 +0000" channel="a"><title>Bad start</title></programme>
<programme start="20240101120000 +0000" stop="2024-01-01" channel="a"><title>Bad stop</title></programme>
<programme start="20240101130000 +0000" stop="20240101120000 +0000" channel="a"><title>End before start</title></programme>
<programme start="20240101120000 +0000" stop="20240101120000 +0000" channel="a"><title>Zero length</title></programme>
<programme start="20240101120000 +0000" stop="20240101130000 +0000"><title>No channel</title></programme>
<programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a"><desc>No title</desc></programme>
<programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a"><title>   </title></programme>
<channel><display-name>No id</display-name></channel>
<programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a"><title>Good</title></programme>
</tv>"""
        val r = parse(xml)
        assertNull(r.stats.error)
        assertEquals(0, r.stats.channels)
        assertEquals(1, r.stats.programs)
        assertEquals(7, r.stats.skipped)
        assertEquals("Good", r.programs.single().title)
    }

    @Test fun missingStopIsInferredFromNextStartPerChannelOrSkipped() {
        val xml = """<tv>
<programme start="20240101120000 +0000" channel="a"><title>A1 no stop</title></programme>
<programme start="20240101120000 +0000" channel="b"><title>B1 no stop</title></programme>
<programme start="20240101123000 +0000" stop="20240101130000 +0000" channel="a"><title>A2</title></programme>
<programme start="20240101130000 +0000" channel="a"><title>A3 last no stop</title></programme>
<programme start="20240105000000 +0000" stop="20240105010000 +0000" channel="b"><title>B2 far later</title></programme>
</tv>"""
        val r = parse(xml)
        assertNull(r.stats.error)
        val byTitle = r.programs.associateBy { it.title }
        assertEquals(setOf("A1 no stop", "A2", "B2 far later"), byTitle.keys)
        assertEquals(utc("2024-01-01T12:30:00Z"), byTitle.getValue("A1 no stop").endMillis)
        // B1 gap > 12h -> skipped; A3 never got a successor -> skipped.
        assertEquals(3, r.stats.programs)
        assertEquals(2, r.stats.skipped)
    }

    @Test fun timeShiftAppliesToBothTimes() {
        val xml = """<tv><programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="a"><title>T</title></programme></tv>"""
        val plus = parse(xml, shift = 90).programs.single()
        assertEquals(utc("2024-01-01T13:30:00Z"), plus.startMillis)
        assertEquals(utc("2024-01-01T14:30:00Z"), plus.endMillis)
        val minus = parse(xml, shift = -60).programs.single()
        assertEquals(utc("2024-01-01T11:00:00Z"), minus.startMillis)
        assertEquals(utc("2024-01-01T12:00:00Z"), minus.endMillis)
    }

    @Test fun clipsLongText() {
        val longTitle = "T".repeat(2000)
        val longDesc = "D".repeat(10_000)
        val xml = """<tv><programme start="20240101120000" stop="20240101130000" channel="a"><title>$longTitle</title><desc>$longDesc</desc></programme></tv>"""
        val p = parse(xml).programs.single()
        assertEquals(512, p.title.length)
        assertEquals(4096, p.description!!.length)
    }

    @Test fun truncatedDocumentKeepsParsedDataAndReportsError() {
        val full = sample
        val cut = full.substring(0, full.indexOf("<programme start=\"20240101070000"))
            .plus("<programme start=\"20240101070000 -0500\" stop=\"20240101080000 -0500\" channel=\"bbc1.uk\"><title>Brea")
        val r = parse(cut)
        assertNotNull(r.stats.error)
        assertEquals(2, r.stats.channels)
        assertEquals(2, r.stats.programs)
        assertEquals(2, r.programs.size)
    }

    @Test fun truncatedBetweenElements() {
        val cut = sample.substring(0, sample.indexOf("</tv>"))
        val r = parse(cut)
        assertNotNull(r.stats.error)
        assertEquals(4, r.stats.programs)
    }

    @Test fun garbageInputDoesNotThrow() {
        val r = parse("this is not xml at all")
        assertNotNull(r.stats.error)
        assertEquals(0, r.stats.programs)
        val empty = parse("")
        assertNotNull(empty.stats.error)
    }

    @Test fun gzipInputViaMaybeGunzip() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(sample.toByteArray()) }
        val input = HttpDownloader.maybeGunzip(ByteArrayInputStream(bos.toByteArray()), 10_000_000)
        val r = parse(input)
        assertNull(r.stats.error)
        assertEquals(2, r.stats.channels)
        assertEquals(4, r.stats.programs)
        // Plain input passes through unchanged.
        val plain = parse(HttpDownloader.maybeGunzip(ByteArrayInputStream(sample.toByteArray()), 10_000_000))
        assertEquals(4, plain.stats.programs)
    }

    @Test fun xxePayloadIsNotExpanded() {
        val secret = File.createTempFile("xxe-secret", ".txt").apply {
            writeText("TOP-SECRET-CONTENT")
            deleteOnExit()
        }
        val xml = """<?xml version="1.0"?>
<!DOCTYPE tv [
  <!ENTITY xxe SYSTEM "${secret.toURI()}">
  <!ENTITY lol "lol">
  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
]>
<tv>
  <channel id="a"><display-name>&xxe;</display-name></channel>
  <programme start="20240101120000" stop="20240101130000" channel="a"><title>Hello &xxe;</title><desc>&lol2;</desc></programme>
  <programme start="20240101130000" stop="20240101140000" channel="a"><title>After</title></programme>
</tv>"""
        val r = parse(xml)
        val all = r.channels.toString() + r.programs.toString() + r.stats.toString()
        assertFalse(all, all.contains("TOP-SECRET-CONTENT"))
        // Internal entity expansion is not performed either (no billion-laughs amplification).
        assertFalse(all.contains("lollollol"))
        // The surrounding document is still processed.
        assertTrue(r.programs.any { it.title == "After" })
    }

    @Test fun unknownHtmlEntityDoesNotKillFeed() {
        val xml = """<tv><programme start="20240101120000" stop="20240101130000" channel="a"><title>A&nbsp;B</title></programme>
<programme start="20240101130000" stop="20240101140000" channel="a"><title>Next</title></programme></tv>"""
        val r = parse(xml)
        assertNull(r.stats.error)
        assertEquals(2, r.stats.programs)
        assertTrue(r.programs[0].title.startsWith("A"))
    }

    @Test fun largeGeneratedFileStreams() {
        val channels = 500
        val perChannel = 400 // 200k programmes
        val file = File.createTempFile("epg-large", ".xml").apply { deleteOnExit() }
        file.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            for (c in 0 until channels) w.write("<channel id=\"ch$c\"><display-name>Channel $c HD</display-name></channel>\n")
            val base = Instant.parse("2024-01-01T00:00:00Z").epochSecond
            val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC)
            for (i in 0 until perChannel) {
                val start = fmt.format(Instant.ofEpochSecond(base + i * 1800L))
                val stop = fmt.format(Instant.ofEpochSecond(base + (i + 1) * 1800L))
                for (c in 0 until channels) {
                    w.write("<programme start=\"$start +0000\" stop=\"$stop +0000\" channel=\"ch$c\"><title>Show $i &amp; more</title><desc>Description for show $i on channel $c</desc><category>Cat</category></programme>\n")
                }
            }
            w.write("</tv>\n")
        }
        var count = 0
        var lastTitle: String? = null
        val t0 = System.nanoTime()
        val stats = file.inputStream().buffered().use { input ->
            parser.parse(input, {}, { count++; lastTitle = it.title })
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("Parsed ${stats.programs} programmes from ${file.length() / 1024 / 1024} MB in $ms ms")
        assertNull(stats.error)
        assertEquals(channels, stats.channels)
        assertEquals(channels * perChannel, stats.programs)
        assertEquals(channels * perChannel, count)
        assertEquals("Show 399 & more", lastTitle)
        assertTrue("too slow: $ms ms", ms < 60_000)
        file.delete()
    }
}
