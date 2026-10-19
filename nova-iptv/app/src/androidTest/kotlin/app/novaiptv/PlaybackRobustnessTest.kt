package app.novaiptv

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.novaiptv.core.common.net.HttpClients
import app.novaiptv.core.model.PlaybackState
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.model.StreamUserAgent
import app.novaiptv.core.player.NovaPlayer
import app.novaiptv.core.player.StreamUserAgents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Real-world stream handling through [NovaPlayer] against a local MockWebServer:
 * extension-less HLS URLs (probe), User-Agent gated servers and servers that send nothing.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackRobustnessTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private var player: NovaPlayer? = null
    private val userAgents = Collections.synchronizedList(mutableListOf<String>())
    private val stallRequests = Collections.synchronizedList(mutableListOf<String>())

    /** Apple's master playlist with every URI made absolute (served from an extension-less path). */
    private val appleMaster: String by lazy { absolutize(fetch(TestData.PUBLIC_HLS), TestData.PUBLIC_HLS) }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val ua = request.headers["User-Agent"].orEmpty()
                userAgents += "$path $ua"
                return when {
                    path == "/live/u/p/101" -> MockResponse.Builder()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .body(appleMaster)
                        .build()
                    // Wrong Content-Type: the first bytes (#EXTM3U) must decide.
                    path == "/live/u/p/102" -> MockResponse.Builder()
                        .setHeader("Content-Type", "text/plain")
                        .body(appleMaster)
                        .build()
                    // Only "VLC" is served (like providers that whitelist known players).
                    path.startsWith("/ua/") -> if (ua == StreamUserAgents.VLC) {
                        MockResponse.Builder().code(302).setHeader("Location", TestData.PUBLIC_HLS).build()
                    } else {
                        MockResponse.Builder().code(403).body("forbidden").build()
                    }
                    // Accepts the connection, sends headers and then nothing for two minutes.
                    path.startsWith("/stall/") -> {
                        stallRequests += ua
                        MockResponse.Builder()
                            .setHeader("Content-Type", "video/mp2t")
                            .bodyDelay(120, TimeUnit.SECONDS)
                            .body("x")
                            .build()
                    }
                    else -> MockResponse.Builder().code(404).body("not found").build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player?.release() }
        server.close()
    }

    private fun start(
        url: String,
        isLive: Boolean,
        agents: List<String>,
        watchdog: NovaPlayer.Watchdog = NovaPlayer.Watchdog(),
    ): NovaPlayer {
        lateinit var created: NovaPlayer
        instrumentation.runOnMainSync {
            created = NovaPlayer(TestData.app, HttpClients.create(), watchdog = watchdog)
            created.setUserAgentCandidates(agents)
            created.play(StreamSource(url), isLive = isLive)
        }
        player = created
        return created
    }

    private fun awaitState(p: NovaPlayer, timeoutMs: Long, seen: MutableList<PlaybackState>, done: (PlaybackState) -> Boolean): PlaybackState? =
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                p.state.onEach { s -> if (seen.lastOrNull()?.javaClass != s.javaClass) seen += s }.first(done)
            }
        }

    private fun List<PlaybackState>.names() = joinToString(" > ") { it.javaClass.simpleName }

    private fun assertPlays(path: String, agents: List<String>, label: String) {
        assumeTrue("emulator has no internet access to ${TestData.PUBLIC_HLS}", TestData.reachable(TestData.PUBLIC_HLS))
        appleMaster // fetch before the player starts (not on the server thread under time pressure)
        val seen = Collections.synchronizedList(mutableListOf<PlaybackState>())
        val started = SystemClock.elapsedRealtime()
        val p = start(server.url(path).toString(), isLive = false, agents = agents)
        val final = awaitState(p, 45_000, seen) { it is PlaybackState.Playing || it is PlaybackState.Error }
        TestData.log("robustness $label: final=$final after ${SystemClock.elapsedRealtime() - started}ms states=${seen.names()}")
        assertTrue("$label did not reach Playing: final=$final states=${seen.names()}", final is PlaybackState.Playing)
    }

    @Test
    fun hlsBehindExtensionLessUrlPlays() =
        assertPlays("/live/u/p/101", listOf(HttpClients.USER_AGENT), "hls-content-type")

    @Test
    fun hlsDetectedFromFirstBytesPlays() =
        assertPlays("/live/u/p/102", listOf(HttpClients.USER_AGENT), "hls-first-bytes")

    @Test
    fun userAgentGatedServerPlaysWithVlcSetting() {
        assertPlays("/ua/u/p/201", StreamUserAgents.candidates(StreamUserAgent.Vlc, null), "ua-vlc")
        assertTrue("VLC agent not sent: $userAgents", userAgents.any { it.endsWith(StreamUserAgents.VLC) })
    }

    @Test
    fun userAgentGatedServerPlaysWithAutoSetting() {
        assertPlays("/ua/u/p/202", StreamUserAgents.candidates(StreamUserAgent.Auto, null), "ua-auto")
        TestData.log("robustness ua-auto: requests=$userAgents")
        assertTrue("app agent not tried first: $userAgents", userAgents.first().endsWith(HttpClients.USER_AGENT))
    }

    @Test
    fun serverSendingNothingEndsInErrorNotEndlessBuffering() {
        val seen = Collections.synchronizedList(mutableListOf<PlaybackState>())
        val started = SystemClock.elapsedRealtime()
        val p = start(
            server.url("/stall/u/p/301.ts").toString(),
            isLive = true,
            agents = StreamUserAgents.candidates(StreamUserAgent.Auto, null),
            watchdog = NovaPlayer.Watchdog(noDataMs = 3_000, startStallMs = 5_000, midStreamStallMs = 5_000),
        )
        val final = awaitState(p, 45_000, seen) { it is PlaybackState.Error || it is PlaybackState.Playing }
        val elapsed = SystemClock.elapsedRealtime() - started
        TestData.log("robustness stall: final=$final after ${elapsed}ms states=${seen.names()} agents=${stallRequests.distinct()}")
        assertTrue("no Error within 45s: states=${seen.names()}", final is PlaybackState.Error)
        val message = (final as PlaybackState.Error).message.orEmpty()
        assertTrue("hint should name the timeout: $message", message.contains("NO_DATA_TIMEOUT"))
        assertTrue("hint must not contain the URL: $message", !message.contains("127.0.0.1") && !message.contains("/stall/"))
        assertEquals(
            "every agent should have been tried",
            StreamUserAgents.candidates(StreamUserAgent.Auto, null).toSet(),
            stallRequests.toSet(),
        )

        // Bounded: no hidden retry loop after the final error.
        SystemClock.sleep(6_000)
        assertTrue("state changed after final error: ${p.state.value}", p.state.value is PlaybackState.Error)
    }

    private fun fetch(url: String): String {
        val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
    }

    private fun absolutize(playlist: String, baseUrl: String): String {
        val base = URI(baseUrl)
        val uriAttr = Regex("URI=\"([^\"]+)\"")
        return playlist.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttr.replace(line) { m -> "URI=\"${base.resolve(m.groupValues[1])}\"" }
                else -> base.resolve(line.trim()).toString()
            }
        }
    }
}
