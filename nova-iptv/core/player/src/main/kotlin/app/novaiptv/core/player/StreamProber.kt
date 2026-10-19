package app.novaiptv.core.player

import android.util.Log
import app.novaiptv.core.model.StreamFormat
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Network side of [StreamProbe]: a small ranged GET with the same User-Agent / headers as
 * playback, following redirects (also http <-> https), with short timeouts. Cancellable
 * (cancelling the coroutine cancels the HTTP call). Never throws: any failure yields
 * [StreamFormat.Unknown] and playback falls back to ExoPlayer's own sniffing plus the
 * [PlaybackRecovery] ladder. A ranged request refused with 403 / 416 is repeated once without
 * the Range header (some panels reject ranges on live streams).
 *
 * Only the MIME type is taken from the probe. Playback always replays the ORIGINAL url, not the
 * redirect target: some panels redirect to single-use tokenised URLs, which the probe has
 * already consumed; the original URL issues a fresh token.
 *
 * Successful decisions are cached in memory per URL (process lifetime, bounded LRU), so channel
 * zapping does not probe the same stream twice. URLs may hold credentials: the cache never
 * leaves memory and URLs are never logged.
 */
class StreamProber(baseClient: OkHttpClient) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun cached(url: String): StreamFormat? = synchronized(cache) { cache[url] }

    fun remember(url: String, format: StreamFormat) {
        if (format == StreamFormat.Unknown) return
        synchronized(cache) { cache[url] = format }
    }

    suspend fun probe(url: String, userAgent: String, headers: Map<String, String>): StreamFormat {
        cached(url)?.let { return it }
        val ranged = request(url, userAgent, headers, ranged = true) ?: return StreamFormat.Unknown
        val format = execute(ranged)
            ?: execute(request(url, userAgent, headers, ranged = false) ?: return StreamFormat.Unknown)
            ?: StreamFormat.Unknown
        remember(url, format)
        return format
    }

    private fun request(url: String, userAgent: String, headers: Map<String, String>, ranged: Boolean): Request? = try {
        Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("User-Agent", userAgent)
            if (ranged) header("Range", "bytes=0-${StreamProbe.PROBE_BYTES - 1}")
        }.get().build()
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Detected format; null when a ranged request was refused and should be repeated without Range. */
    private suspend fun execute(request: Request): StreamFormat? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(TAG, "probe: failed ${e.javaClass.simpleName}")
                if (cont.isActive) cont.resume(StreamFormat.Unknown)
            }

            override fun onResponse(call: Call, response: Response) {
                val format = try {
                    response.use { decide(it) }
                } catch (e: Exception) {
                    StreamFormat.Unknown
                }
                if (cont.isActive) cont.resume(format)
            }
        })
    }

    private fun decide(response: Response): StreamFormat? {
        val finalUrl = response.request.url.toString()
        val ranged = response.request.header("Range") != null
        Log.i(TAG, "probe: HTTP ${response.code} type=${response.header("Content-Type")} ranged=$ranged")
        if (ranged && (response.code == 403 || response.code == 416)) return null
        if (!response.isSuccessful) {
            // Error pages say nothing about the stream; only the redirect target's extension counts.
            return StreamProbe.decide(finalUrl, null, ByteArray(0))
        }
        val head = readHead(response)
        return StreamProbe.decide(finalUrl, response.header("Content-Type"), head).also {
            Log.i(TAG, "probe: ${head.size} bytes -> ${StreamProbe.label(it)}")
        }
    }

    private fun readHead(response: Response): ByteArray {
        val buffer = ByteArray(StreamProbe.PROBE_BYTES)
        var read = 0
        response.body.byteStream().use { input ->
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
        }
        return buffer.copyOf(read)
    }

    private companion object {
        const val TAG = "NovaPlayer"
        const val TIMEOUT_SECONDS = 8L
        const val MAX_CACHE = 500

        val cache = object : LinkedHashMap<String, StreamFormat>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamFormat>?): Boolean = size > MAX_CACHE
        }
    }
}
