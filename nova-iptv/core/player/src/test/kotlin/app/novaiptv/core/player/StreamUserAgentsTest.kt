package app.novaiptv.core.player

import app.novaiptv.core.model.StreamUserAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUserAgentsTest {

    @Test
    fun resolvesEachMode() {
        assertEquals("App/1", StreamUserAgents.resolve(StreamUserAgent.AppDefault, null, "App/1", "Exo/1"))
        assertEquals("App/1", StreamUserAgents.resolve(StreamUserAgent.Auto, null, "App/1", "Exo/1"))
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", StreamUserAgents.resolve(StreamUserAgent.Vlc, null, "App/1", "Exo/1"))
        assertEquals("Exo/1", StreamUserAgents.resolve(StreamUserAgent.ExoPlayer, null, "App/1", "Exo/1"))
        assertEquals("Mine/2", StreamUserAgents.resolve(StreamUserAgent.Custom, "  Mine/2 ", "App/1", "Exo/1"))
        assertTrue(StreamUserAgents.EXOPLAYER.isNotBlank())
    }

    @Test
    fun badCustomFallsBackToApp() {
        assertEquals("App/1", StreamUserAgents.resolve(StreamUserAgent.Custom, "", "App/1"))
        assertEquals("App/1", StreamUserAgents.resolve(StreamUserAgent.Custom, "a\r\nX-Evil: 1", "App/1"))
        assertNull(StreamUserAgents.sanitizeCustom("x".repeat(300)))
        assertNull(StreamUserAgents.sanitizeCustom("Ünïcode"))
    }

    @Test
    fun autoTriesAppThenVlcThenExoPlayer() {
        assertEquals(
            listOf("App/1", StreamUserAgents.VLC, StreamUserAgents.EXOPLAYER),
            StreamUserAgents.candidates(StreamUserAgent.Auto, null, "App/1"),
        )
        assertEquals(listOf(StreamUserAgents.VLC), StreamUserAgents.candidates(StreamUserAgent.Vlc, null, "App/1"))
    }
}
