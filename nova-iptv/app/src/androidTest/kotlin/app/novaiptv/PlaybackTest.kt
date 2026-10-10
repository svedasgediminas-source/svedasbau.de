package app.novaiptv

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.novaiptv.core.common.net.HttpClients
import app.novaiptv.core.model.PlaybackErrorKind
import app.novaiptv.core.model.PlaybackState
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.player.NovaPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** Real playback through [NovaPlayer] (ExoPlayer + OkHttp) on the emulator. */
@RunWith(AndroidJUnit4::class)
class PlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var player: NovaPlayer? = null

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player?.release() }
    }

    private fun start(stream: StreamSource, isLive: Boolean): NovaPlayer {
        lateinit var created: NovaPlayer
        instrumentation.runOnMainSync {
            created = NovaPlayer(TestData.app, HttpClients.create())
            created.play(stream, isLive = isLive)
        }
        player = created
        return created
    }

    /** Waits until [done] matches a state; returns it (or null on timeout) and records every state seen. */
    private fun awaitState(
        p: NovaPlayer,
        timeoutMs: Long,
        seen: MutableList<PlaybackState>,
        done: (PlaybackState) -> Boolean,
    ): PlaybackState? = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            p.state.onEach { s -> if (seen.lastOrNull()?.javaClass != s.javaClass) seen += s }.first(done)
        }
    }

    private fun List<PlaybackState>.names() = joinToString(" > ") { it.javaClass.simpleName }

    @Test
    fun publicHlsStreamReachesPlaying() {
        assumeTrue("emulator has no internet access to ${TestData.PUBLIC_HLS}", TestData.reachable(TestData.PUBLIC_HLS))
        val seen = Collections.synchronizedList(mutableListOf<PlaybackState>())
        val started = SystemClock.elapsedRealtime()
        val p = start(StreamSource(TestData.PUBLIC_HLS, mimeTypeHint = StreamFormat.Hls), isLive = false)
        val final = awaitState(p, 30_000, seen) { it is PlaybackState.Playing || it is PlaybackState.Error }
        val elapsed = SystemClock.elapsedRealtime() - started
        TestData.log("playback HLS: final=$final after ${elapsed}ms, states=${seen.names()}")
        assertTrue("HLS did not reach Playing within 30s: final=$final states=${seen.names()}", final is PlaybackState.Playing)

        // It keeps playing: position advances.
        val first = (final as PlaybackState.Playing).positionMillis
        val later = awaitState(p, 10_000, seen) { it is PlaybackState.Playing && it.positionMillis >= first + 2_000 }
        TestData.log("playback HLS: position $first -> ${(later as? PlaybackState.Playing)?.positionMillis}")
        assertTrue("position did not advance: $later", later is PlaybackState.Playing)
    }

    @Test
    fun brokenLiveStreamEndsInErrorAfterBoundedRetries() {
        val seen = Collections.synchronizedList(mutableListOf<PlaybackState>())
        val retries = Collections.synchronizedList(mutableListOf<Int>())
        val started = SystemClock.elapsedRealtime()
        val p = start(StreamSource("http://127.0.0.1:1/none.m3u8", mimeTypeHint = StreamFormat.Hls), isLive = true)
        val final = runBlocking {
            withTimeoutOrNull(60_000) {
                p.state.onEach { s ->
                    if (seen.lastOrNull() != s) seen += s
                    if (s is PlaybackState.Retrying && retries.lastOrNull() != s.attempt) retries += s.attempt
                }.first { it is PlaybackState.Error }
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        TestData.log("playback broken: final=$final after ${elapsed}ms, retry attempts=$retries, states=${seen.names()}")
        assertTrue("no Error within 60s: states=${seen.names()}", final is PlaybackState.Error)
        val kind = (final as PlaybackState.Error).kind
        assertTrue("unexpected kind $kind", kind == PlaybackErrorKind.Network || kind == PlaybackErrorKind.SourceUnavailable)
        assertEquals("live stream should retry exactly maxAttempts times", listOf(1, 2, 3), retries.toList())

        // No hidden retry loop: the state stays Error.
        SystemClock.sleep(6_000)
        val after = p.state.value
        TestData.log("playback broken: state 6s after error = $after")
        assertTrue("state changed after final error: $after", after is PlaybackState.Error)
    }
}
