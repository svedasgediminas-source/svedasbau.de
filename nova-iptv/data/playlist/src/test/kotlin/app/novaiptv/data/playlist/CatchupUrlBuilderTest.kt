package app.novaiptv.data.playlist

import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CatchupUrlBuilderTest {
    // 2026-10-01T12:00:00Z
    private val start = 1_790_856_000_000L
    private val end = start + 3_600_000L
    private val now = start + 2 * 3_600_000L
    private val startS = start / 1000
    private val endS = end / 1000
    private val nowS = now / 1000
    private val program = EpgProgram("ch", start, end, "Show")

    private fun channel(url: String, cap: CatchupCapability) = Channel(
        id = "1:x", playlistId = 1, number = 1, name = "Ch", groupName = null, logoUrl = null, epgId = "ch",
        stream = StreamSource(url), catchup = cap,
    )

    private fun sup(type: CatchupType, days: Int = 7, template: String? = null) = CatchupCapability.Supported(type, days, template)

    @Test
    fun noneReturnsNull() {
        assertNull(CatchupUrlBuilder.build(channel("http://s.example.com/a", CatchupCapability.None), program, now))
    }

    @Test
    fun defaultReplacesWholeUrl() {
        val c = channel("http://s.example.com/live.m3u8", sup(CatchupType.Default, template = "http://arc.example.com/ch/{utc}/{utcend}/{lutc}/{duration}/{Y}{m}{d}-{H}{M}{S}.m3u8"))
        assertEquals(
            "http://arc.example.com/ch/$startS/$endS/$nowS/3600/20261001-120000.m3u8",
            CatchupUrlBuilder.build(c, program, now),
        )
    }

    @Test
    fun defaultWithoutTemplateIsNull() {
        assertNull(CatchupUrlBuilder.build(channel("http://s.example.com/a", sup(CatchupType.Default)), program, now))
    }

    @Test
    fun dollarPlaceholdersDivisorsAndFormats() {
        val c = channel(
            "http://s.example.com/a",
            sup(
                CatchupType.Default,
                template = "http://arc.example.com/x?s=\${start}&e=\${end}&n=\${now}&t=\${timestamp}&d=\${duration}&o=\${offset}&dm={duration:60}&om={offset:60}&f={utc:Y-m-d:H-M}&g=\${start:YmdHMS}&off={offset}",
            ),
        )
        assertEquals(
            "http://arc.example.com/x?s=$startS&e=$endS&n=$nowS&t=$nowS&d=3600&o=7200&dm=60&om=120&f=2026-10-01:12-00&g=20261001120000&off=7200",
            CatchupUrlBuilder.build(c, program, now),
        )
    }

    @Test
    fun unresolvedPlaceholderYieldsNull() {
        val c = channel("http://s.example.com/a", sup(CatchupType.Default, template = "http://arc.example.com/{catchup-id}?u={utc}"))
        assertNull(CatchupUrlBuilder.build(c, program, now))
    }

    @Test
    fun relativeDefaultTemplateIsAppended() {
        val c = channel("http://s.example.com/a.m3u8", sup(CatchupType.Default, template = "?utc={utc}"))
        assertEquals("http://s.example.com/a.m3u8?utc=$startS", CatchupUrlBuilder.build(c, program, now))
    }

    @Test
    fun appendAddsTemplateToStreamUrl() {
        val c = channel("http://s.example.com/a.m3u8?token=1", sup(CatchupType.Append, template = "&utc={utc}&lutc={lutc}"))
        assertEquals("http://s.example.com/a.m3u8?token=1&utc=$startS&lutc=$nowS", CatchupUrlBuilder.build(c, program, now))
        assertNull(CatchupUrlBuilder.build(channel("http://s.example.com/a", sup(CatchupType.Append)), program, now))
    }

    @Test
    fun shiftAddsUtcAndLutc() {
        assertEquals(
            "http://s.example.com/a.m3u8?utc=$startS&lutc=$nowS",
            CatchupUrlBuilder.build(channel("http://s.example.com/a.m3u8", sup(CatchupType.Shift)), program, now),
        )
        assertEquals(
            "http://s.example.com/a.m3u8?token=x&utc=$startS&lutc=$nowS",
            CatchupUrlBuilder.build(channel("http://s.example.com/a.m3u8?token=x", sup(CatchupType.Shift)), program, now),
        )
    }

    @Test
    fun flussonicRewrites() {
        val cap = sup(CatchupType.Flussonic)
        assertEquals(
            "http://fl.example.com/ch1/index-$startS-3600.m3u8?token=abc",
            CatchupUrlBuilder.build(channel("http://fl.example.com/ch1/index.m3u8?token=abc", cap), program, now),
        )
        assertEquals(
            "http://fl.example.com/ch1/mono-$startS-3600.m3u8",
            CatchupUrlBuilder.build(channel("http://fl.example.com/ch1/mono.m3u8", cap), program, now),
        )
        assertEquals(
            "http://fl.example.com/ch1/timeshift_abs-$startS.ts",
            CatchupUrlBuilder.build(channel("http://fl.example.com/ch1/mpegts", cap), program, now),
        )
        assertEquals(
            "http://fl.example.com/ch1/timeshift_abs-$startS.ts?token=1",
            CatchupUrlBuilder.build(channel("http://fl.example.com/ch1/live.ts?token=1", cap), program, now),
        )
        assertNull(CatchupUrlBuilder.build(channel("http://fl.example.com/ch1/stream", cap), program, now))
        // Absolute template wins.
        assertEquals(
            "http://t.example.com/$startS",
            CatchupUrlBuilder.build(channel("http://fl.example.com/ch1/index.m3u8", sup(CatchupType.Flussonic, template = "http://t.example.com/{utc}")), program, now),
        )
    }

    @Test
    fun xtreamDerivedFromStreamUrlOrTemplate() {
        assertEquals(
            "http://xc.example.com:8080/timeshift/user/pass/60/2026-10-01:12-00/123.ts",
            CatchupUrlBuilder.build(channel("http://xc.example.com:8080/live/user/pass/123.ts", sup(CatchupType.Xtream)), program, now),
        )
        assertEquals(
            "http://xc.example.com/timeshift/user/pass/60/2026-10-01:12-00/123.ts",
            CatchupUrlBuilder.build(channel("http://xc.example.com/user/pass/123", sup(CatchupType.Xtream)), program, now),
        )
        assertEquals(
            "http://xc.example.com/arc/$startS",
            CatchupUrlBuilder.build(channel("http://xc.example.com/live/u/p/1.ts", sup(CatchupType.Xtream, template = "http://xc.example.com/arc/{utc}")), program, now),
        )
        assertNull(CatchupUrlBuilder.build(channel("http://xc.example.com/hls/stream.m3u8", sup(CatchupType.Xtream)), program, now))
    }

    @Test
    fun archiveWindowBounds() {
        val c = channel("http://s.example.com/a", sup(CatchupType.Shift, days = 1))
        val day = 86_400_000L
        // Future programme.
        assertNull(CatchupUrlBuilder.build(c, EpgProgram("ch", now + 1000, now + 3_600_000, "F"), now))
        // Starts exactly now -> not yet started.
        assertNull(CatchupUrlBuilder.build(c, EpgProgram("ch", now, now + 3_600_000, "F"), now))
        // Currently running (started in the past) -> allowed (restart).
        assertNotNull(CatchupUrlBuilder.build(c, EpgProgram("ch", now - 1000, now + 3_600_000, "L"), now))
        // Exactly at the window edge -> allowed.
        assertNotNull(CatchupUrlBuilder.build(c, EpgProgram("ch", now - day, now - day + 3_600_000, "E"), now))
        // Older than the window.
        assertNull(CatchupUrlBuilder.build(c, EpgProgram("ch", now - day - 1, now - day + 3_600_000, "O"), now))
        // Unknown window (days = 0) -> no window check.
        val unknown = channel("http://s.example.com/a", sup(CatchupType.Shift, days = 0))
        assertNotNull(CatchupUrlBuilder.build(unknown, EpgProgram("ch", now - 30 * day, now - 30 * day + 3_600_000, "Old"), now))
        // Invalid programme (end before start).
        assertNull(CatchupUrlBuilder.build(c, EpgProgram("ch", now - 5000, now - 6000, "Bad"), now))
    }

    @Test
    fun dangerousTemplateRejected() {
        val c = channel("http://s.example.com/a", sup(CatchupType.Default, template = "javascript:alert({utc})"))
        assertNull(CatchupUrlBuilder.build(c, program, now))
        val f = channel("http://s.example.com/a", sup(CatchupType.Default, template = "file:///etc/{utc}"))
        assertNull(CatchupUrlBuilder.build(f, program, now))
    }

    @Test
    fun parserAndBuilderEndToEnd() {
        val text = """
            #EXTM3U catchup="default" catchup-days="3" catchup-source="http://arc.example.com/tv?utc={utc}"
            #EXTINF:-1 tvg-id="a",A
            http://s.example.com/a.m3u8
        """.trimIndent()
        val ch = M3uParser().parse(text.byteInputStream(), 1).channels.single()
        assertEquals("http://arc.example.com/tv?utc=$startS", CatchupUrlBuilder.build(ch, program, now))
    }
}
