package app.novaiptv.data.xtream

import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.common.Limits
import app.novaiptv.core.common.Redact
import app.novaiptv.core.common.UrlValidator
import app.novaiptv.core.common.net.LimitedInputStream
import app.novaiptv.core.common.net.SizeLimitExceededException
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.Season
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.VodCategory
import app.novaiptv.core.model.XtreamCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException

/** Account information from player_api.php (user_info + server_info). Contains no credentials. */
data class XtreamAccount(
    val status: String?,
    val expiresAtMillis: Long?,
    val isTrial: Boolean,
    val activeConnections: Int?,
    val maxConnections: Int?,
    val allowedOutputFormats: List<String>,
    val serverTimezone: String?,
    /** Normalised portal URL as entered by the user (used as base for stream URLs). */
    val serverUrl: String,
)

/**
 * Xtream Codes player_api client.
 *
 * Request URLs carry the real credentials (URL-encoded via [HttpUrl.Builder]); they are never
 * included in errors, which only ever contain [Redact]ed URLs or exception class names.
 * Stream URLs in returned models use credential placeholders (see [XtreamUrls]).
 *
 * [liveStreams] calls [authenticate] first: this validates the account (AuthFailed /
 * AccountExpired surface before a catalogue import) and picks the live container: "m3u8"
 * when allowed_output_formats contains it, else "ts".
 */
class XtreamClient(private val client: OkHttpClient, private val json: Json = XtreamJson) {

    suspend fun authenticate(c: XtreamCredentials): AppResult<XtreamAccount> {
        val server = when (val r = UrlValidator.normalizeServerUrl(c.serverUrl)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> return r
        }
        val root = when (val r = fetch(server, c, null)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> return r
        }
        return parseAccount(root, server)
    }

    suspend fun liveStreams(c: XtreamCredentials, playlistId: Long): AppResult<List<Channel>> {
        val account = when (val r = authenticate(c)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> return r
        }
        val server = account.serverUrl
        val ext = if (account.allowedOutputFormats.any { it.equals("m3u8", ignoreCase = true) }) "m3u8" else "ts"
        val cats = when (val r = fetch(server, c, "get_live_categories")) {
            is AppResult.Ok -> XtreamMapper.categories(r.value, playlistId).associate { it.id to it.name }
            is AppResult.Err -> return r
        }
        return fetchItems(server, c, "get_live_streams") { index, el ->
            XtreamMapper.liveStream(index, el, playlistId, server, cats, ext)
        }
    }

    suspend fun movieCategories(c: XtreamCredentials, playlistId: Long): AppResult<List<VodCategory>> =
        withServer(c) { server -> fetch(server, c, "get_vod_categories").mapOk { XtreamMapper.categories(it, playlistId) } }

    suspend fun movies(c: XtreamCredentials, playlistId: Long): AppResult<List<Movie>> =
        withServer(c) { server -> fetchItems(server, c, "get_vod_streams") { _, el -> XtreamMapper.movie(el, playlistId, server) } }

    suspend fun seriesCategories(c: XtreamCredentials, playlistId: Long): AppResult<List<VodCategory>> =
        withServer(c) { server -> fetch(server, c, "get_series_categories").mapOk { XtreamMapper.categories(it, playlistId) } }

    suspend fun series(c: XtreamCredentials, playlistId: Long): AppResult<List<Series>> =
        withServer(c) { server -> fetchItems(server, c, "get_series") { _, el -> XtreamMapper.seriesItem(el, playlistId) } }

    /** [seriesId] is the app id "<playlistId>:s:<xtreamSeriesId>". */
    suspend fun seasons(c: XtreamCredentials, playlistId: Long, seriesId: String): AppResult<List<Season>> {
        val raw = seriesId.substringAfter(":s:", missingDelimiterValue = "")
        val xtreamId = XtreamMapper.safeId(kotlinx.serialization.json.JsonPrimitive(raw))
            ?: return AppResult.Err(AppError.InvalidInput("seriesId"))
        return withServer(c) { server ->
            fetch(server, c, "get_series_info", "series_id" to xtreamId)
                .mapOk { XtreamMapper.seasons(it, playlistId, seriesId, server) }
        }
    }

    /** XMLTV URL with real credentials: use only for the request, never store or log it. */
    fun xmltvUrl(c: XtreamCredentials): String {
        val server = (UrlValidator.normalizeServerUrl(c.serverUrl) as? AppResult.Ok)?.value ?: c.serverUrl.trim().trimEnd('/')
        val base = "$server/xmltv.php".toHttpUrlOrNull()
            ?: return "$server/xmltv.php?username=${enc(c.username)}&password=${enc(c.password)}"
        return base.newBuilder()
            .addQueryParameter("username", c.username)
            .addQueryParameter("password", c.password)
            .build()
            .toString()
    }

    // ---------------------------------------------------------------------------------------

    private inline fun <T> withServer(c: XtreamCredentials, block: (String) -> AppResult<T>): AppResult<T> =
        when (val r = UrlValidator.normalizeServerUrl(c.serverUrl)) {
            is AppResult.Ok -> block(r.value)
            is AppResult.Err -> r
        }

    private inline fun <T> AppResult<JsonElement?>.mapOk(f: (JsonElement?) -> T): AppResult<T> = when (this) {
        is AppResult.Ok -> try {
            AppResult.Ok(f(value))
        } catch (e: RuntimeException) {
            AppResult.Err(AppError.Parse("mapping: ${e.javaClass.simpleName}"))
        }
        is AppResult.Err -> this
    }

    private fun parseAccount(root: JsonElement?, server: String): AppResult<XtreamAccount> {
        // Bad credentials: empty body, "[]", or an object without user_info / with auth=0.
        val o = root as? JsonObject ?: return AppResult.Err(AppError.AuthFailed)
        val user = o.obj("user_info") ?: return AppResult.Err(AppError.AuthFailed)
        if (!user["auth"].bool()) return AppResult.Err(AppError.AuthFailed)
        val status = user["status"].str()
        if (status != null && status.lowercase() in BLOCKED_STATUSES) return AppResult.Err(AppError.AccountExpired)
        val serverInfo = o.obj("server_info")
        return AppResult.Ok(
            XtreamAccount(
                status = status,
                expiresAtMillis = XtreamMapper.secondsToMillis(user["exp_date"]),
                isTrial = user["is_trial"].bool(),
                activeConnections = user["active_cons"].int(),
                maxConnections = user["max_connections"].int(),
                allowedOutputFormats = user["allowed_output_formats"].items().mapNotNull { it.str()?.lowercase() },
                serverTimezone = serverInfo?.get("timezone").str(),
                serverUrl = server,
            ),
        )
    }

    /**
     * GET player_api.php. Returns the parsed JSON, or null for an empty body. HTML / non-JSON
     * bodies map to [AppError.Parse]; 401/403 to [AppError.AuthFailed]; I/O to [AppError.Network].
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetch(
        server: String,
        c: XtreamCredentials,
        action: String?,
        vararg extra: Pair<String, String>,
    ): AppResult<JsonElement?> = request(server, c, action, *extra) { stream ->
        json.decodeFromStream(JsonElement.serializer(), stream)
    }

    /**
     * Fetches a catalogue list and maps it element by element. Top-level arrays are decoded as a
     * stream, so a large catalogue never exists as one JSON tree in memory (TV boxes have small
     * heaps); object-shaped responses fall back to the tree. Unmappable elements are skipped.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T : Any> fetchItems(
        server: String,
        c: XtreamCredentials,
        action: String,
        map: (index: Int, element: JsonElement) -> T?,
    ): AppResult<List<T>> = request(server, c, action) { stream ->
        val out = ArrayList<T>()
        if (firstSignificantByte(stream) == '['.code) {
            val seq = json.decodeToSequence(stream, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
            for ((index, el) in seq.withIndex()) {
                if (out.size >= Limits.MAX_CHANNELS_PER_PLAYLIST) break
                map(index, el)?.let(out::add)
            }
        } else {
            for ((index, el) in json.decodeFromStream(JsonElement.serializer(), stream).items().withIndex()) {
                if (out.size >= Limits.MAX_CHANNELS_PER_PLAYLIST) break
                map(index, el)?.let(out::add)
            }
        }
        out
    }.let { r ->
        when (r) {
            is AppResult.Ok -> AppResult.Ok(r.value ?: emptyList())
            is AppResult.Err -> r
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> request(
        server: String,
        c: XtreamCredentials,
        action: String?,
        vararg extra: Pair<String, String>,
        read: (BufferedInputStream) -> T,
    ): AppResult<T?> = withContext(Dispatchers.IO) {
        val base = "$server/player_api.php".toHttpUrlOrNull()
            ?: return@withContext AppResult.Err(AppError.InvalidInput("url"))
        val url = base.newBuilder()
            .addQueryParameter("username", c.username)
            .addQueryParameter("password", c.password)
            .apply {
                if (action != null) addQueryParameter("action", action)
                extra.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()
        val safeUrl = Redact.url(url.toString())
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 401 || code == 403) return@withContext AppResult.Err(AppError.AuthFailed)
                if (!response.isSuccessful) return@withContext AppResult.Err(AppError.Http(code, safeUrl))
                val declared = response.body.contentLength()
                if (declared > Limits.MAX_XTREAM_RESPONSE_BYTES) {
                    return@withContext AppResult.Err(AppError.TooLarge(Limits.MAX_XTREAM_RESPONSE_BYTES))
                }
                val input = BufferedInputStream(LimitedInputStream(response.body.byteStream(), Limits.MAX_XTREAM_RESPONSE_BYTES))
                input.use { stream ->
                    when (firstSignificantByte(stream)) {
                        -1 -> AppResult.Ok(null)
                        '{'.code, '['.code -> AppResult.Ok(read(stream))
                        else -> AppResult.Err(AppError.Parse("not JSON ($safeUrl)"))
                    }
                }
            }
        } catch (e: SizeLimitExceededException) {
            AppResult.Err(AppError.TooLarge(e.limit))
        } catch (e: SerializationException) {
            AppResult.Err(AppError.Parse("invalid JSON ($safeUrl)"))
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization may surface malformed input as IllegalArgumentException.
            AppResult.Err(AppError.Parse("invalid JSON ($safeUrl)"))
        } catch (e: IOException) {
            AppResult.Err(AppError.Network(e.javaClass.simpleName))
        }
    }

    /** Peeks the first non-whitespace byte (skipping a UTF-8 BOM) without consuming it. */
    private fun firstSignificantByte(s: BufferedInputStream): Int {
        while (true) {
            s.mark(4)
            val b = s.read()
            if (b == -1) return -1
            if (b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code) continue
            if (b == 0xEF) {
                val b2 = s.read()
                val b3 = s.read()
                if (b2 == 0xBB && b3 == 0xBF) continue
            }
            s.reset()
            return b
        }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    private companion object {
        val BLOCKED_STATUSES = setOf("expired", "disabled", "banned")
    }
}
