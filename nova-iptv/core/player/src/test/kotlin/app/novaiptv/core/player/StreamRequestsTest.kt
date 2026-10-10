package app.novaiptv.core.player

import androidx.media3.common.MimeTypes
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class StreamRequestsTest {

    @Test
    fun defaultsWhenNothingDeclared() {
        val spec = StreamRequests.spec(StreamSource("http://h/1.ts"), "Default/1.0")
        assertEquals("Default/1.0", spec.userAgent)
        assertEquals(emptyMap<String, String>(), spec.headers)
    }

    @Test
    fun explicitUserAgentAndReferrer() {
        val spec = StreamRequests.spec(
            StreamSource("http://h/1.ts", userAgent = "VLC/3", referrer = "http://ref/", headers = mapOf("X-Token" to "a")),
            "Default/1.0",
        )
        assertEquals("VLC/3", spec.userAgent)
        assertEquals(mapOf("X-Token" to "a", "Referer" to "http://ref/"), spec.headers)
    }

    @Test
    fun userAgentFromHeadersIsUsedAndNotDuplicated() {
        val spec = StreamRequests.spec(StreamSource("u", headers = mapOf("user-agent" to "Kodi")), "D")
        assertEquals("Kodi", spec.userAgent)
        assertEquals(emptyMap<String, String>(), spec.headers)
    }

    @Test
    fun existingRefererHeaderWins() {
        val spec = StreamRequests.spec(StreamSource("u", referrer = "r1", headers = mapOf("referer" to "r2")), "D")
        assertEquals(mapOf("referer" to "r2"), spec.headers)
    }

    @Test
    fun headerInjectionIsDropped() {
        val spec = StreamRequests.spec(
            StreamSource("u", userAgent = "a\r\nX: y", headers = mapOf("A" to "1\n2", "" to "x", "B" to "ok")),
            "D",
        )
        assertEquals("D", spec.userAgent)
        assertEquals(mapOf("B" to "ok"), spec.headers)
    }

    @Test
    fun mimeTypes() {
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamRequests.mimeTypeFor(StreamSource("http://h/x", mimeTypeHint = StreamFormat.Hls)))
        assertEquals(MimeTypes.APPLICATION_MPD, StreamRequests.mimeTypeFor(StreamSource("http://h/x", mimeTypeHint = StreamFormat.Dash)))
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamRequests.mimeTypeFor(StreamSource("http://h/live.M3U8?token=1")))
        assertNull(StreamRequests.mimeTypeFor(StreamSource("http://h/live/1.ts", mimeTypeHint = StreamFormat.MpegTs)))
        assertNull(StreamRequests.mimeTypeFor(StreamSource("http://h/movie/1.mkv")))
    }

    @Test
    fun trackLabels() {
        assertEquals("Director commentary", TrackLabels.label("Director commentary", "en", 2, Locale.ENGLISH))
        assertEquals("English 5.1", TrackLabels.label(null, "en", 6, Locale.ENGLISH))
        assertEquals("German", TrackLabels.label(" ", "de", -1, Locale.ENGLISH))
        assertEquals("", TrackLabels.label(null, "und", -1, Locale.ENGLISH))
        assertEquals("7.1", TrackLabels.label(null, null, 8, Locale.ENGLISH))
    }

    @Test
    fun resumePolicy() {
        assertEquals(0L, ResumePolicy.resumePositionMillis(5_000, 100_000))
        assertEquals(5_001L, ResumePolicy.resumePositionMillis(5_001, 100_000))
        assertEquals(94_999L, ResumePolicy.resumePositionMillis(94_999, 100_000))
        assertEquals(0L, ResumePolicy.resumePositionMillis(95_000, 100_000))
        assertEquals(0L, ResumePolicy.resumePositionMillis(60_000, 0))
    }
}
