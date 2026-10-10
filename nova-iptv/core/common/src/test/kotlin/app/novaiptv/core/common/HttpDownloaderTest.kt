package app.novaiptv.core.common

import app.novaiptv.core.common.net.HttpDownloader
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class HttpDownloaderTest {
    private val server = MockWebServer()
    private val downloader = HttpDownloader(OkHttpClient())

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    @Test fun downloadsPlainBody() = runTest {
        server.enqueue(MockResponse.Builder().body("#EXTM3U\n").build())
        val r = downloader.download(server.url("/a.m3u").toString(), 1024) { it.readBytes().decodeToString() }
        assertEquals(AppResult.Ok("#EXTM3U\n"), r)
    }

    @Test fun gunzipsByMagicBytes() = runTest {
        val bytes = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write("hello".toByteArray()) } }.toByteArray()
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
        val r = downloader.download(server.url("/e.xml.gz").toString(), 1024) { it.readBytes().decodeToString() }
        assertEquals(AppResult.Ok("hello"), r)
    }

    @Test fun enforcesSizeLimit() = runTest {
        server.enqueue(MockResponse.Builder().body("x".repeat(5000)).build())
        val r = downloader.download(server.url("/big").toString(), 100) { it.readBytes() }
        assertTrue(r is AppResult.Err && r.error is AppError.TooLarge)
    }

    @Test fun mapsHttpErrors() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(401).build())
        val r1 = downloader.download(server.url("/missing").toString(), 100) { it.readBytes() }
        val r2 = downloader.download(server.url("/auth").toString(), 100) { it.readBytes() }
        assertTrue((r1 as AppResult.Err).error is AppError.Http)
        assertEquals(AppError.AuthFailed, (r2 as AppResult.Err).error)
    }

    @Test fun rejectsInvalidUrlWithoutNetwork() = runTest {
        val r = downloader.download("file:///etc/passwd", 100) { it.readBytes() }
        assertTrue((r as AppResult.Err).error is AppError.InvalidInput)
    }
}
