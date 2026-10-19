package app.novaiptv.core.player

import androidx.media3.common.PlaybackException
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProbeTest {

    private fun ts(packets: Int = 2) = ByteArray(188 * packets) { i -> if (i % 188 == 0) 0x47 else 0x11 }

    @Test
    fun extensionLessUrlsNeedAProbe() {
        assertTrue(StreamProbe.needsProbe(StreamSource("http://host:8080/user/pass/12345")))
        assertTrue(StreamProbe.needsProbe(StreamSource("http://host/live/u/p/123")))
        assertTrue(StreamProbe.needsProbe(StreamSource("https://host/play.php?id=5&token=x")))
        assertFalse(StreamProbe.needsProbe(StreamSource("http://host/live/u/p/123.m3u8")))
        assertFalse(StreamProbe.needsProbe(StreamSource("http://host/live/u/p/123.ts?token=1")))
        assertFalse(StreamProbe.needsProbe(StreamSource("http://host/x", mimeTypeHint = StreamFormat.MpegTs)))
        assertFalse(StreamProbe.needsProbe(StreamSource("rtmp://host/live/1")))
    }

    @Test
    fun knownFormatUsesHintThenExtension() {
        assertEquals(StreamFormat.Hls, StreamProbe.knownFormat(StreamSource("http://h/a.M3U8?x=1")))
        assertEquals(StreamFormat.Dash, StreamProbe.knownFormat(StreamSource("http://h/a/manifest.mpd")))
        assertEquals(StreamFormat.MpegTs, StreamProbe.knownFormat(StreamSource("http://h/u/p/1.ts")))
        assertEquals(StreamFormat.Progressive, StreamProbe.knownFormat(StreamSource("http://h/movie/u/p/1.mkv")))
        assertEquals(StreamFormat.Unknown, StreamProbe.knownFormat(StreamSource("http://h.example.com/u/p/1")))
        assertEquals(StreamFormat.Hls, StreamProbe.knownFormat(StreamSource("http://h/u/p/1", mimeTypeHint = StreamFormat.Hls)))
    }

    @Test
    fun firstBytesWin() {
        assertEquals(StreamFormat.Hls, StreamProbe.decide("http://h/u/p/1", "video/mp2t", "#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals(StreamFormat.Hls, StreamProbe.decide(null, null, "﻿  \n#EXTM3U\n".toByteArray()))
        assertEquals(StreamFormat.Dash, StreamProbe.decide(null, null, "<MPD xmlns=\"urn:mpeg:dash\">".toByteArray()))
        assertEquals(StreamFormat.Dash, StreamProbe.decide(null, null, "<?xml version=\"1.0\"?>\n<MPD>".toByteArray()))
        assertEquals(StreamFormat.MpegTs, StreamProbe.decide(null, "application/octet-stream", ts()))
        assertEquals(StreamFormat.MpegTs, StreamProbe.decide(null, null, byteArrayOf(0x47, 0x40, 0x00)))
        val mp4 = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()
        assertEquals(StreamFormat.Progressive, StreamProbe.decide(null, null, mp4))
    }

    @Test
    fun notTsWhenSecondSyncByteMissing() {
        val bytes = ts()
        bytes[188] = 0x00
        assertEquals(StreamFormat.Unknown, StreamProbe.fromBytes(bytes))
    }

    @Test
    fun contentTypeThenFinalUrl() {
        val html = "<html>nope</html>".toByteArray()
        assertEquals(StreamFormat.Hls, StreamProbe.decide("http://h/x", "application/vnd.apple.mpegurl", html))
        assertEquals(StreamFormat.Hls, StreamProbe.decide("http://h/x", "application/x-mpegURL; charset=utf-8", html))
        assertEquals(StreamFormat.Hls, StreamProbe.decide("http://h/x", "audio/mpegurl", ByteArray(0)))
        assertEquals(StreamFormat.Dash, StreamProbe.decide("http://h/x", "application/dash+xml", ByteArray(0)))
        assertEquals(StreamFormat.MpegTs, StreamProbe.decide("http://h/x", "video/MP2T", ByteArray(0)))
        // Redirected to a tokenised .m3u8: the final URL decides.
        assertEquals(StreamFormat.Hls, StreamProbe.decide("http://cdn/hls/abc/index.m3u8?token=1", "text/plain", html))
        assertEquals(StreamFormat.Unknown, StreamProbe.decide("http://h/x", "text/html", html))
    }

    @Test
    fun alternateFormatAndFormatErrors() {
        assertEquals(StreamFormat.Hls, StreamProbe.alternateFormat(StreamFormat.MpegTs))
        assertEquals(StreamFormat.Hls, StreamProbe.alternateFormat(StreamFormat.Unknown))
        assertEquals(StreamFormat.MpegTs, StreamProbe.alternateFormat(StreamFormat.Hls))
        assertTrue(StreamProbe.isFormatError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertTrue(StreamProbe.isFormatError(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED))
        assertFalse(StreamProbe.isFormatError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(StreamProbe.isFormatError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
    }
}
