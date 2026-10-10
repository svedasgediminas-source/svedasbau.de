package app.novaiptv.data.playlist

import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.common.Limits
import app.novaiptv.core.common.net.HttpDownloader
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class PlaylistDownloadTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val downloader = HttpDownloader(client)

    private fun playlist(n: Int) = buildString {
        append("#EXTM3U\n")
        repeat(n) { append("#EXTINF:-1 tvg-id=\"c$it\" group-title=\"G\",Channel $it\nhttp://s.example.com/live/$it.m3u8\n") }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun completeDownloadParses() = runTest {
        server.enqueue(MockResponse.Builder().body(playlist(100)).build())
        val result = downloader.download(server.url("/list.m3u").toString(), Limits.MAX_PLAYLIST_BYTES) {
            M3uParser().parse(it, 3)
        }
        result as AppResult.Ok
        assertEquals(100, result.value.channels.size)
    }

    @Test
    fun gzippedDownloadParses() = runTest {
        val gz = Buffer()
        GZIPOutputStream(gz.outputStream()).use { it.write(playlist(20).toByteArray()) }
        server.enqueue(MockResponse.Builder().body(gz).build())
        val result = downloader.download(server.url("/list.m3u.gz").toString(), Limits.MAX_PLAYLIST_BYTES) {
            M3uParser().parse(it, 3)
        }
        assertEquals(20, (result as AppResult.Ok).value.channels.size)
    }

    @Test
    fun truncatedResponseIsNetworkError() = runTest {
        val body = playlist(500)
        // Declares more bytes than are sent, then closes the socket mid-body.
        server.enqueue(
            MockResponse.Builder()
                .body(body)
                .setHeader("Content-Length", body.length * 2)
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build(),
        )
        val result = downloader.download(server.url("/list.m3u").toString(), Limits.MAX_PLAYLIST_BYTES) {
            M3uParser().parse(it, 3)
        }
        assertNetworkError(result)
    }

    @Test
    fun connectionDroppedDuringBodyIsNetworkError() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body(playlist(2000))
                .throttleBody(4096, 1, TimeUnit.MILLISECONDS)
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build(),
        )
        val result = downloader.download(server.url("/list.m3u").toString(), Limits.MAX_PLAYLIST_BYTES) {
            M3uParser().parse(it, 3)
        }
        assertNetworkError(result)
    }

    @Test
    fun oversizedBodyIsTooLarge() = runTest {
        server.enqueue(MockResponse.Builder().body(playlist(200)).build())
        val result = downloader.download(server.url("/list.m3u").toString(), 1024) { M3uParser().parse(it, 3) }
        assertTrue(result.toString(), result is AppResult.Err && result.error is AppError.TooLarge)
    }

    private fun assertNetworkError(result: AppResult<M3uParseResult>) {
        when (result) {
            is AppResult.Err -> assertTrue("Expected Network, got ${result.error}", result.error is AppError.Network)
            is AppResult.Ok -> fail("Truncated download must not be reported as success (${result.value.channels.size} channels)")
        }
    }
}
