package app.novaiptv.data.playlist

import app.novaiptv.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections

class M3uStreamingParserTest {
    private val parser = M3uParser()

    private fun stream(text: String): InputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    private val fixtures = listOf(
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
        // Duplicates (ordinal suffixes), malformed entries, missing URL, orphan line.
        """
        #EXTM3U
        #EXTINF:-1 tvg-id="a",Same
        http://s.example.com/a.ts
        #EXTINF:-1 tvg-id="a",Same
        http://s.example.com/a.ts
        #EXTINF:-1 tvg-id="a",Same
        http://s.example.com/a.ts
        #EXTINF:-1,No url
        #EXTINF:-1,Bad url
        ftp://nope
        http://orphan.example.com/x.ts
        #EXTINF:-1 group-title="G",Ok
        http://s.example.com/ok.ts
        #EXTINF:-1,Tail without url
        """.trimIndent(),
        // Catch-up, EXTGRP, EXTVLCOPT, Kodi pipe headers, adult detection.
        """
        #EXTM3U catchup="shift" catchup-days="3"
        #EXTINF:-1 tvg-id="x" catchup="flussonic" catchup-days="7",Archive
        #EXTGRP:Archive group
        #EXTVLCOPT:http-user-agent=VLC/3
        #EXTVLCOPT:http-referrer=http://ref.example.com/
        http://s.example.com/arch.m3u8|User-Agent=Kodi&X-Token=abc%20def
        #EXTINF:-1 group-title="XXX",Night
        http://s.example.com/night.ts
        #EXTINF:-1 group-title="Adult Swim",Cartoons
        http://s.example.com/cartoons.ts
        """.trimIndent(),
        // No header.
        """
        #EXTINF:-1,One
        http://s.example.com/1.ts
        """.trimIndent(),
        // Not a playlist at all.
        "hello world\nfoo",
        "",
    )

    @Test
    fun streamingMatchesParseForFixtures() {
        for (batchSize in listOf(1, 2, 3, 500)) {
            for (text in fixtures) {
                val expected = parser.parse(stream(text), 7)
                val got = ArrayList<Channel>()
                val summary = parser.parseStreaming(stream(text), 7, batchSize) { got.addAll(it) }
                assertEquals(expected.channels, got)
                assertEquals(expected.channels.size, summary.channelCount)
                assertEquals(expected.channels.mapTo(HashSet()) { it.groupName }.size, summary.groupCount)
                assertEquals(expected.epgUrls, summary.epgUrls)
                assertEquals(expected.skipped, summary.skipped)
                assertEquals(expected.warnings, summary.warnings)
            }
        }
    }

    @Test
    fun duplicateIdsAreDisambiguatedAcrossBatches() {
        val got = ArrayList<Channel>()
        parser.parseStreaming(stream(fixtures[1]), 7, batchSize = 1) { got.addAll(it) }
        val ids = got.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids[1].endsWith("-1"))
        assertTrue(ids[2].endsWith("-2"))
        assertEquals(ids[0], ids[1].removeSuffix("-1"))
    }

    @Test
    fun batchesNeverExceedBatchSize() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(1234) { i -> append("#EXTINF:-1 group-title=\"G${i % 7}\",Ch $i\nhttp://s.example.com/$i.ts\n") }
        }
        val sizes = ArrayList<Int>()
        val summary = parser.parseStreaming(stream(text), 1, batchSize = 100) { sizes += it.size }
        assertTrue(sizes.all { it in 1..100 })
        assertTrue(sizes.dropLast(1).all { it == 100 })
        assertEquals(1234, sizes.sum())
        assertEquals(1234, summary.channelCount)
        assertEquals(7, summary.groupCount)
        assertEquals(13, sizes.size)
    }

    @Test
    fun emptyPlaylistEmitsNoBatch() {
        var calls = 0
        val summary = parser.parseStreaming(stream("#EXTM3U\n"), 1) { calls++ }
        assertEquals(0, calls)
        assertEquals(0, summary.channelCount)
        assertEquals(0, summary.groupCount)
    }

    @Test
    fun hundredThousandEntriesStreamWithBoundedBatches() {
        val total = 100_000
        val entries = object : java.util.Enumeration<InputStream> {
            var i = -1
            override fun hasMoreElements() = i < total
            override fun nextElement(): InputStream {
                val s = if (i < 0) "#EXTM3U\n" else "#EXTINF:-1 tvg-id=\"id$i\" group-title=\"G${i % 50}\",Channel $i\nhttp://s.example.com/live/$i.m3u8\n"
                i++
                return stream(s)
            }
        }
        var count = 0
        var maxBatch = 0
        var batches = 0
        var lastNumber = 0
        val summary = parser.parseStreaming(SequenceInputStream(entries), 3, batchSize = 500) { b ->
            batches++
            count += b.size
            maxBatch = maxOf(maxBatch, b.size)
            lastNumber = b.last().number
        }
        assertEquals(total, count)
        assertEquals(total, summary.channelCount)
        assertEquals(50, summary.groupCount)
        assertEquals(500, maxBatch)
        assertEquals(total / 500, batches)
        assertEquals(total, lastNumber)
    }

    @Test
    fun exceptionFromOnBatchAbortsParse() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(10) { i -> append("#EXTINF:-1,Ch $i\nhttp://s.example.com/$i.ts\n") }
        }
        var calls = 0
        try {
            parser.parseStreaming(stream(text), 1, batchSize = 2) {
                calls++
                if (calls == 2) throw IllegalStateException("stop")
            }
            throw AssertionError("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("stop", e.message)
        }
        assertEquals(2, calls)
    }

    @Test(expected = IOException::class)
    fun ioErrorsPropagate() {
        val failing = SequenceInputStream(
            Collections.enumeration(
                listOf(
                    stream("#EXTM3U\n#EXTINF:-1,A\nhttp://s.example.com/a.ts\n"),
                    object : InputStream() {
                        override fun read(): Int = throw IOException("truncated")
                    },
                ),
            ),
        )
        parser.parseStreaming(failing, 1) { }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveBatchSize() {
        parser.parseStreaming(stream("#EXTM3U\n"), 1, batchSize = 0) { }
    }
}
