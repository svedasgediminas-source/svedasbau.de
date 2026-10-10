package app.novaiptv.data.playlist

import app.novaiptv.core.common.Limits
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class M3uParserTest {
    private val parser = M3uParser()

    private fun parse(text: String, playlistId: Long = 7) =
        parser.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), playlistId)

    @Test
    fun parsesValidPlaylist() {
        val r = parse(
            """
            #EXTM3U url-tvg="http://epg.example.com/a.xml.gz,https://epg2.example.com/b.xml" x-tvg-url="http://epg.example.com/c.xml"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://logo.example.com/bbc1.png" tvg-chno="101" group-title="UK",BBC One HD
            http://stream.example.com/live/bbc1.m3u8
            #EXTINF:-1 tvg-id="cnn" group-title="News",CNN
            https://stream.example.com/cnn.mpd
            #EXTINF:0,Plain
            http://stream.example.com/plain.ts
            #EXTINF:-1,Movie
            http://stream.example.com/movie.mkv?token=1
            """.trimIndent(),
        )
        assertEquals(4, r.channels.size)
        assertEquals(0, r.skipped)
        assertEquals(
            listOf("http://epg.example.com/a.xml.gz", "https://epg2.example.com/b.xml", "http://epg.example.com/c.xml"),
            r.epgUrls,
        )
        val bbc = r.channels[0]
        assertEquals("BBC One HD", bbc.name)
        assertEquals("bbc1.uk", bbc.epgId)
        assertEquals("UK", bbc.groupName)
        assertEquals("http://logo.example.com/bbc1.png", bbc.logoUrl)
        assertEquals(101, bbc.number)
        assertEquals(7L, bbc.playlistId)
        assertTrue(bbc.id.startsWith("7:"))
        assertEquals(2 + 16, bbc.id.length)
        assertEquals(StreamFormat.Hls, bbc.stream.mimeTypeHint)
        assertEquals("BBC One", bbc.extras["tvg-name"])
        assertEquals(CatchupCapability.None, bbc.catchup)

        assertEquals(2, r.channels[1].number) // sequential fallback
        assertEquals(StreamFormat.Dash, r.channels[1].stream.mimeTypeHint)
        assertEquals(StreamFormat.MpegTs, r.channels[2].stream.mimeTypeHint)
        assertNull(r.channels[2].groupName)
        assertNull(r.channels[2].epgId)
        assertEquals(StreamFormat.Progressive, r.channels[3].stream.mimeTypeHint)
    }

    @Test
    fun idsAreDeterministicAcrossImportsAndUnique() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",A
            http://s.example.com/a
            #EXTINF:-1 tvg-id="a",A
            http://s.example.com/a
            #EXTINF:-1,B
            http://s.example.com/b
        """.trimIndent()
        val r1 = parse(text)
        val r2 = parse(text)
        assertEquals(r1.channels.map { it.id }, r2.channels.map { it.id })
        assertEquals(3, r1.channels.map { it.id }.toSet().size)
        assertNotEquals(parse(text, 8).channels[0].id, r1.channels[0].id)
    }

    @Test
    fun handlesBomCrlfAndBlankLines() {
        val text = "﻿#EXTM3U\r\n\r\n#EXTINF:-1 tvg-id=\"x\",X\r\n\r\nhttp://s.example.com/x.m3u8\r\n\r\n#EXTINF:-1,Y\rhttp://s.example.com/y\r"
        val r = parse(text)
        assertEquals(listOf("X", "Y"), r.channels.map { it.name })
        assertEquals("http://s.example.com/x.m3u8", r.channels[0].stream.url)
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    @Test
    fun oddQuotingAndCommas() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name='Single Quoted' group-title='Sport, Live',Sky Sports, Main Event
            http://s.example.com/1
            #EXTINF:-1 tvg-id=noquotes group-title=Movies Premium tvg-logo=http://l.example.com/x.png,Bare Values
            http://s.example.com/2
            #EXTINF:-1 tvg-id="unclosed group-title="G",Name After
            http://s.example.com/3
            #EXTINF:-1 tvg-id = "spaced" custom-attr="hello world",Spaced
            http://s.example.com/4
            #EXTINF:-1 tvg-name="Only Tvg Name"
            http://s.example.com/5
            #EXTINF:-1 TVG-ID="Upper",Upper
            http://s.example.com/6
            """.trimIndent(),
        )
        assertEquals(6, r.channels.size)
        val c1 = r.channels[0]
        assertEquals("Sky Sports, Main Event", c1.name)
        assertEquals("Sport, Live", c1.groupName)
        assertEquals("Single Quoted", c1.extras["tvg-name"])

        val c2 = r.channels[1]
        assertEquals("noquotes", c2.epgId)
        assertEquals("Movies Premium", c2.groupName)
        assertEquals("http://l.example.com/x.png", c2.logoUrl)
        assertEquals("Bare Values", c2.name)

        // Unclosed quote: still produces a usable channel.
        assertEquals("Name After", r.channels[2].name)

        val c4 = r.channels[3]
        assertEquals("spaced", c4.epgId)
        assertEquals("hello world", c4.extras["custom-attr"])
        assertEquals("Spaced", c4.name)

        assertEquals("Only Tvg Name", r.channels[4].name)
        assertEquals("Upper", r.channels[5].epgId)
    }

    @Test
    fun kodiHeaderSuffixAndVlcOptsAndAttributes() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1,Kodi
            http://s.example.com/k.m3u8|User-Agent=Mozilla%2F5.0%20(TV)&Referer=http://ref.example.com/&X-Forwarded-For=1.2.3.4
            #EXTINF:-1,Vlc
            #EXTVLCOPT:http-user-agent=VLC/3.0
            #EXTVLCOPT:http-referrer=http://vlcref.example.com/
            #EXTVLCOPT:http-origin=http://origin.example.com
            #KODIPROP:inputstream.adaptive.manifest_type=hls
            http://s.example.com/v.ts
            #EXTINF:-1 user-agent="AttrUA" http-referrer="http://attr.example.com/",Attr
            http://s.example.com/a
            #EXTINF:-1,Clean
            http://s.example.com/clean
            """.trimIndent(),
        )
        assertEquals(4, r.channels.size)
        val k = r.channels[0].stream
        assertEquals("http://s.example.com/k.m3u8", k.url)
        assertEquals("Mozilla/5.0 (TV)", k.userAgent)
        assertEquals("http://ref.example.com/", k.referrer)
        assertEquals(mapOf("X-Forwarded-For" to "1.2.3.4"), k.headers)
        assertEquals(StreamFormat.Hls, k.mimeTypeHint)

        val v = r.channels[1].stream
        assertEquals("VLC/3.0", v.userAgent)
        assertEquals("http://vlcref.example.com/", v.referrer)
        assertEquals("http://origin.example.com", v.headers["Origin"])

        val a = r.channels[2].stream
        assertEquals("AttrUA", a.userAgent)
        assertEquals("http://attr.example.com/", a.referrer)

        // VLC options do not leak to the following entry.
        val c = r.channels[3].stream
        assertNull(c.userAgent)
        assertNull(c.referrer)
        assertTrue(c.headers.isEmpty())
    }

    @Test
    fun extGrpIsGroupFallback() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1,One
            #EXTGRP:Music
            http://s.example.com/1
            #EXTINF:-1 group-title="Explicit",Two
            #EXTGRP:Ignored
            http://s.example.com/2
            #EXTINF:-1,Three
            http://s.example.com/3
            """.trimIndent(),
        )
        assertEquals(listOf("Music", "Explicit", null), r.channels.map { it.groupName })
    }

    @Test
    fun invalidUrlsAreSkippedWithRedactedWarnings() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1,JS
            javascript:alert(1)
            #EXTINF:-1,File
            file:///etc/passwd
            #EXTINF:-1,Content
            content://com.evil/x
            #EXTINF:-1,Creds
            http://host.example.com/x y?username=alice&password=secret
            #EXTINF:-1,Good
            http://s.example.com/good
            #EXTINF:-1,Rtmp
            rtmp://s.example.com/live/x
            """.trimIndent(),
        )
        assertEquals(listOf("Good", "Rtmp"), r.channels.map { it.name })
        assertEquals(4, r.skipped)
        assertTrue(r.warnings.size >= 4)
        assertFalse(r.warnings.joinToString().contains("secret"))
        assertFalse(r.warnings.joinToString().contains("alice"))
    }

    @Test
    fun malformedEntriesAreTolerated() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1,Missing URL
            #EXTINF:-1,Has URL
            http://s.example.com/ok
            http://s.example.com/orphan-without-extinf
            #EXTINF
            http://s.example.com/no-colon
            #EXTINF:garbage tvg-id="z" ,
            http://s.example.com/empty-name
            #EXT-X-VERSION:3
            # just a comment
            #EXTINF:-1,Dangling at end
            """.trimIndent(),
        )
        assertEquals(3, r.channels.size)
        assertEquals("Has URL", r.channels[0].name)
        assertEquals("Channel 2", r.channels[1].name)
        assertEquals("Channel 3", r.channels[2].name)
        assertEquals("z", r.channels[2].epgId)
        assertEquals(2, r.skipped)
        assertTrue(r.warnings.any { it.contains("without a preceding #EXTINF") })
    }

    @Test
    fun emptyFileReturnsWarning() {
        val r = parse("")
        assertTrue(r.channels.isEmpty())
        assertEquals(0, r.skipped)
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun headerOnlyReturnsWarning() {
        val r = parse("#EXTM3U\n\n")
        assertTrue(r.channels.isEmpty())
        assertTrue(r.warnings.any { it.contains("no channels") })
    }

    @Test
    fun nonM3uFileReturnsWarning() {
        val r = parse("<html><body>Not found</body></html>\n")
        assertTrue(r.channels.isEmpty())
        assertTrue(r.warnings.any { it.contains("Not an M3U") })
    }

    @Test
    fun missingHeaderStillParsesExtinf() {
        val r = parse("#EXTINF:-1,A\nhttp://s.example.com/a\n")
        assertEquals(1, r.channels.size)
        assertTrue(r.warnings.any { it.contains("#EXTM3U") })
    }

    @Test
    fun adultDetection() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="XXX Movies",Some Channel
            http://s.example.com/1
            #EXTINF:-1 group-title="General",Hot 18+ TV
            http://s.example.com/2
            #EXTINF:-1 group-title="For ADULTS only",X
            http://s.example.com/3
            #EXTINF:-1 group-title="Kids",Adult Swim
            http://s.example.com/4
            #EXTINF:-1 group-title="News",CNN
            http://s.example.com/5
            """.trimIndent(),
        )
        assertEquals(listOf(true, true, true, false, false), r.channels.map { it.isAdult })
    }

    @Test
    fun unknownAttributesGoToExtras() {
        val r = parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" tvg-country=\"LT\" tvg-language=\"lt\" radio=\"true\",A\nhttp://s.example.com/a\n")
        val extras = r.channels[0].extras
        assertEquals("LT", extras["tvg-country"])
        assertEquals("lt", extras["tvg-language"])
        assertEquals("true", extras["radio"])
        assertFalse(extras.containsKey("tvg-id"))
    }

    @Test
    fun invalidChnoFallsBackToIndex() {
        val r = parse("#EXTM3U\n#EXTINF:-1 tvg-chno=\"abc\",A\nhttp://s.example.com/a\n#EXTINF:-1 tvg-chno=\"-5\",B\nhttp://s.example.com/b\n")
        assertEquals(listOf(1, 2), r.channels.map { it.number })
    }

    @Test
    fun longNamesAreClippedAndLongLinesIgnored() {
        val longName = "N".repeat(Limits.MAX_NAME_LENGTH + 100)
        val hugeLine = "#EXTINF:-1 tvg-id=\"" + "x".repeat(Limits.MAX_LINE_LENGTH + 10) + "\",Huge"
        val r = parse("#EXTM3U\n#EXTINF:-1,$longName\nhttp://s.example.com/a\n$hugeLine\nhttp://s.example.com/b\n#EXTINF:-1,After\nhttp://s.example.com/c\n")
        assertEquals(Limits.MAX_NAME_LENGTH, r.channels[0].name.length)
        assertEquals(listOf("After"), r.channels.drop(1).map { it.name })
        assertTrue(r.warnings.any { it.contains("longer than") })
    }

    @Test
    fun invalidLogoAndEpgUrlsDropped() {
        val r = parse("#EXTM3U url-tvg=\"javascript:x,http://ok.example.com/e.xml\"\n#EXTINF:-1 tvg-logo=\"file:///x.png\",A\nhttp://s.example.com/a\n")
        assertEquals(listOf("http://ok.example.com/e.xml"), r.epgUrls)
        assertNull(r.channels[0].logoUrl)
    }

    // ---- catch-up declarations ----

    @Test
    fun catchupOnlyWhenDeclared() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup-days="7",DaysOnly
            http://s.example.com/1
            #EXTINF:-1 timeshift="3",TimeshiftOnly
            http://s.example.com/2
            #EXTINF:-1 catchup="default" catchup-days="5" catchup-source="http://a.example.com/x?s={utc}",Default
            http://s.example.com/3
            #EXTINF:-1 catchup="append" catchup-source="?utc={utc}&lutc={lutc}",Append
            http://s.example.com/4
            #EXTINF:-1 catchup="shift" timeshift="4",Shift
            http://s.example.com/5
            #EXTINF:-1 catchup="timeshift" tvg-rec="2",Shift2
            http://s.example.com/6
            #EXTINF:-1 catchup="flussonic" catchup-days="1",Fl
            http://s.example.com/ch/index.m3u8
            #EXTINF:-1 catchup="fs",Fs
            http://s.example.com/ch/mono.m3u8
            #EXTINF:-1 catchup="xc" catchup-days="2",Xc
            http://s.example.com/live/u/p/1.ts
            #EXTINF:-1 catchup="disabled" catchup-days="2",Disabled
            http://s.example.com/10
            #EXTINF:-1 catchup="weird",Unknown
            http://s.example.com/11
            #EXTINF:-1 catchup-source="http://a.example.com/{utc}" catchup-days="3",SourceOnly
            http://s.example.com/12
            """.trimIndent(),
        )
        val caps = r.channels.associate { it.name to it.catchup }
        assertEquals(CatchupCapability.None, caps["DaysOnly"])
        assertEquals(CatchupCapability.None, caps["TimeshiftOnly"])
        assertEquals(CatchupCapability.Supported(CatchupType.Default, 5, "http://a.example.com/x?s={utc}"), caps["Default"])
        assertEquals(CatchupCapability.Supported(CatchupType.Append, 0, "?utc={utc}&lutc={lutc}"), caps["Append"])
        assertEquals(CatchupCapability.Supported(CatchupType.Shift, 4, null), caps["Shift"])
        assertEquals(CatchupCapability.Supported(CatchupType.Shift, 2, null), caps["Shift2"])
        assertEquals(CatchupCapability.Supported(CatchupType.Flussonic, 1, null), caps["Fl"])
        assertEquals(CatchupCapability.Supported(CatchupType.Flussonic, 0, null), caps["Fs"])
        assertEquals(CatchupCapability.Supported(CatchupType.Xtream, 2, null), caps["Xc"])
        assertEquals(CatchupCapability.None, caps["Disabled"])
        assertEquals(CatchupCapability.None, caps["Unknown"])
        assertEquals(CatchupCapability.Supported(CatchupType.Default, 3, "http://a.example.com/{utc}"), caps["SourceOnly"])
    }

    @Test
    fun headerCatchupDefaultsApplyToAllChannels() {
        val r = parse(
            """
            #EXTM3U catchup="append" catchup-days="7" catchup-source="?utc={utc}&lutc={lutc}"
            #EXTINF:-1,Inherits
            http://s.example.com/1
            #EXTINF:-1 catchup-days="2",OverridesDays
            http://s.example.com/2
            #EXTINF:-1 catchup="shift",OverridesType
            http://s.example.com/3
            #EXTINF:-1 catchup="disabled",OptsOut
            http://s.example.com/4
            """.trimIndent(),
        )
        val caps = r.channels.map { it.catchup }
        assertEquals(CatchupCapability.Supported(CatchupType.Append, 7, "?utc={utc}&lutc={lutc}"), caps[0])
        assertEquals(CatchupCapability.Supported(CatchupType.Append, 2, "?utc={utc}&lutc={lutc}"), caps[1])
        assertEquals(CatchupType.Shift, (caps[2] as CatchupCapability.Supported).type)
        assertEquals(7, (caps[2] as CatchupCapability.Supported).days)
        assertEquals(CatchupCapability.None, caps[3])
    }

    // ---- scale ----

    /** Generates a playlist on the fly so the test itself never holds the whole file either. */
    private class GeneratedPlaylist(private val count: Int) : InputStream() {
        private var index = -1
        private var current: ByteArray = "#EXTM3U\n".toByteArray()
        private var pos = 0

        override fun read(): Int {
            if (pos >= current.size && !advance()) return -1
            return current[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= current.size && !advance()) return -1
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }

        private fun advance(): Boolean {
            index++
            if (index >= count) return false
            current = (
                "#EXTINF:-1 tvg-id=\"ch$index.example\" tvg-name=\"Channel $index\" tvg-logo=\"http://logo.example.com/$index.png\" " +
                    "group-title=\"Group ${index % 50}\" catchup=\"shift\" catchup-days=\"3\",Channel $index HD\n" +
                    "http://stream.example.com:8080/live/user/pass/$index.ts\n"
                ).toByteArray()
            pos = 0
            return true
        }
    }

    @Test
    fun hugeGeneratedPlaylistStreams() {
        val count = 50_000
        var progressCalls = 0
        var lastProgress = 0
        val started = System.nanoTime()
        val r = parser.parse(GeneratedPlaylist(count), 1) { n ->
            progressCalls++
            lastProgress = n
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(count, r.channels.size)
        assertEquals(0, r.skipped)
        assertEquals(count, lastProgress)
        assertTrue(progressCalls >= count / 1000)
        assertEquals(count, r.channels.map { it.id }.toSet().size)
        assertEquals("Channel 49999 HD", r.channels.last().name)
        assertEquals(StreamFormat.MpegTs, r.channels.last().stream.mimeTypeHint)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 15_000)
    }
}
