package app.novaiptv.core.common

import app.novaiptv.core.model.XtreamCredentials
import java.net.URI
import java.net.URLDecoder

/**
 * Recognises M3U/EPG links that embed an Xtream Codes login, e.g.
 * `http://host:8080/get.php?username=U&password=P&type=m3u_plus&output=ts`
 * (also `player_api.php` and `xmltv.php`). Storing such a link as a plain M3U playlist would keep
 * the password in the database, so the add flow offers to convert it into an Xtream account.
 *
 * The returned [XtreamCredentials] hides username and password in its toString.
 */
object XtreamUrlDetector {
    private val endpoints = setOf("get.php", "player_api.php", "xmltv.php", "panel_api.php")

    /** Returns the embedded credentials, or null when [url] is not an Xtream login link. */
    fun detect(url: String): XtreamCredentials? {
        val valid = UrlValidator.validateSourceUrl(url.trim()).getOrNull() ?: return null
        val uri = try {
            URI(valid)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val rawPath = uri.rawPath.orEmpty()
        val lastSegment = rawPath.substringAfterLast('/')
        if (lastSegment.lowercase() !in endpoints) return null
        val prefix = rawPath.substringBeforeLast('/').trimEnd('/')

        val params = parseQuery(uri.rawQuery ?: return null)
        val username = params["username"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val password = params["password"]?.takeIf { it.isNotEmpty() } ?: return null

        val port = if (uri.port != -1) ":${uri.port}" else ""
        return XtreamCredentials(serverUrl = "$scheme://$host$port$prefix", username = username, password = password)
    }

    /** First value per (case-insensitive) key. A literal '+' is kept, since pasted passwords often contain one. */
    private fun parseQuery(rawQuery: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('=')) ?: continue
            val value = if ('=' in pair) decode(pair.substringAfter('=')) ?: continue else ""
            out.putIfAbsent(key.lowercase(), value)
        }
        return out
    }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }
}
