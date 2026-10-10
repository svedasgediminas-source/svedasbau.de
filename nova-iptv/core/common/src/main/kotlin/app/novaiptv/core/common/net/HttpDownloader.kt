package app.novaiptv.core.common.net

import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.common.Limits
import app.novaiptv.core.common.Redact
import app.novaiptv.core.common.UrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Thrown inside the stream when the byte limit is exceeded; mapped to [AppError.TooLarge]. */
class SizeLimitExceededException(val limit: Long) : IOException("Size limit $limit exceeded")

/** Wraps a stream and fails once more than [limit] bytes were read. */
class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) check(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) check(n.toLong())
        return n
    }

    private fun check(n: Long) {
        count += n
        if (count > limit) throw SizeLimitExceededException(limit)
    }
}

object HttpClients {
    const val USER_AGENT = "NovaIPTV/0.1 (Android TV)"

    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Limits.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(Limits.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(
                if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", USER_AGENT).build() else req,
            )
        }
        .build()
}

/**
 * Downloads a URL and hands the (size-limited, transparently gunzipped) body to [block] on the
 * IO dispatcher. Errors never include credentials: URLs are passed through [Redact].
 */
class HttpDownloader(private val client: OkHttpClient) {

    suspend fun <T> download(url: String, maxBytes: Long, block: (InputStream) -> T): AppResult<T> =
        withContext(Dispatchers.IO) {
            val valid = UrlValidator.validateSourceUrl(url)
            if (valid is AppResult.Err) return@withContext valid
            val request = try {
                Request.Builder().url(url).build()
            } catch (e: IllegalArgumentException) {
                return@withContext AppResult.Err(AppError.InvalidInput("url"))
            }
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext AppResult.Err(
                            if (response.code == 401 || response.code == 403) AppError.AuthFailed
                            else AppError.Http(response.code, Redact.url(url)),
                        )
                    }
                    val declared = response.body.contentLength()
                    if (declared > maxBytes) return@withContext AppResult.Err(AppError.TooLarge(maxBytes))
                    response.body.byteStream().use { raw ->
                        AppResult.Ok(block(maybeGunzip(LimitedInputStream(raw, maxBytes), maxBytes)))
                    }
                }
            } catch (e: SizeLimitExceededException) {
                AppResult.Err(AppError.TooLarge(e.limit))
            } catch (e: IOException) {
                AppResult.Err(AppError.Network(e.javaClass.simpleName))
            }
        }

    companion object {
        /** Detects gzip by magic bytes (servers often send .gz without Content-Encoding). */
        fun maybeGunzip(input: InputStream, maxBytes: Long): InputStream {
            val buffered = if (input.markSupported()) input else BufferedInputStream(input)
            buffered.mark(2)
            val b1 = buffered.read()
            val b2 = buffered.read()
            buffered.reset()
            return if (b1 == 0x1f && b2 == 0x8b) {
                // Bound decompressed size too (gzip bombs); XMLTV typically compresses ~10x, so
                // 3x the plain limit still admits large real-world EPG files.
                BufferedInputStream(LimitedInputStream(GZIPInputStream(buffered), maxBytes * 3))
            } else {
                buffered
            }
        }
    }
}
