package app.novaiptv.core.common

import java.net.URI

/**
 * Validates user-entered and playlist-provided URLs. Only http/https (and, for streams,
 * rtmp/rtsp/udp which Media3 or the device may support) are accepted; javascript:, file:,
 * content: and other schemes are rejected for remote sources.
 */
object UrlValidator {
    private val sourceSchemes = setOf("http", "https")
    private val streamSchemes = setOf("http", "https", "rtmp", "rtsp", "udp", "rtp")

    fun validateSourceUrl(raw: String): AppResult<String> = validate(raw, sourceSchemes)

    fun validateStreamUrl(raw: String): AppResult<String> = validate(raw, streamSchemes)

    fun isValidStreamUrl(raw: String): Boolean = validateStreamUrl(raw) is AppResult.Ok

    /** Normalises a portal URL: trims, adds http:// when missing, strips trailing slashes. */
    fun normalizeServerUrl(raw: String): AppResult<String> {
        var s = raw.trim()
        if (s.isEmpty()) return AppResult.Err(AppError.InvalidInput("empty"))
        if (!s.contains("://")) s = "http://$s"
        s = s.trimEnd('/')
        return validate(s, sourceSchemes)
    }

    private fun validate(raw: String, schemes: Set<String>): AppResult<String> {
        val s = raw.trim()
        if (s.isEmpty() || s.length > Limits.MAX_URL_LENGTH) return AppResult.Err(AppError.InvalidInput("length"))
        if (s.any { it.isWhitespace() || it.code < 0x20 }) return AppResult.Err(AppError.InvalidInput("whitespace"))
        val uri = try {
            URI(s)
        } catch (e: Exception) {
            return AppResult.Err(AppError.InvalidInput("malformed"))
        }
        val scheme = uri.scheme?.lowercase() ?: return AppResult.Err(AppError.InvalidInput("scheme"))
        if (scheme !in schemes) return AppResult.Err(AppError.InvalidInput("scheme"))
        if (uri.host.isNullOrBlank()) return AppResult.Err(AppError.InvalidInput("host"))
        return AppResult.Ok(s)
    }
}
