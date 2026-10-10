package app.novaiptv

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.novaiptv.core.data.DataGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.TimeUnit

/** Shared helpers for instrumented tests. */
object TestData {
    const val TAG = "NovaQA"

    /** Apple's public HLS sample (H.264 + AAC in MPEG-TS), used by the Media3 demo app. */
    const val PUBLIC_HLS = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"

    val app: NovaApp get() = ApplicationProvider.getApplicationContext()

    /** The process-wide graph the UI uses (created in [NovaApp.onCreate]). */
    val appGraph: DataGraph get() = app.graph

    /** Deletes every playlist (and with it channels, favourites, history and EPG) through the repositories. */
    fun clearAll(graph: DataGraph = appGraph) = runBlocking {
        for (p in graph.playlists.observePlaylists().first()) graph.playlists.delete(p.id)
        graph.history.clear()
        graph.preferences.setActivePlaylist(null)
    }

    fun log(message: String) {
        Log.i(TAG, message)
    }

    /** True when [url] answers over the network (the emulator normally has internet). */
    fun reachable(url: String): Boolean = try {
        val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        log("network check failed for $url: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

/** Clears app data before the test (outer rule, so it runs before the activity is launched). */
class CleanDataRule : TestWatcher() {
    override fun starting(description: Description) {
        TestData.clearAll()
    }
}
