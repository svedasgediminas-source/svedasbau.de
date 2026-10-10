package app.novaiptv.core.common

/**
 * Removes credentials from URLs before they reach logs or error messages. Xtream URLs carry
 * username/password in the query string or path (/live/user/pass/123.ts).
 */
object Redact {
    private val queryCreds = Regex("""(?i)([?&](username|password|user|pass|token)=)[^&#]*""")
    private val userInfo = Regex("""(?i)(://)[^/@\s]+@""")
    private val xtreamPath = Regex("""(?i)(/(live|movie|series|timeshift)/)[^/\s]+/[^/\s]+/""")

    fun url(raw: String): String = raw
        .replace(queryCreds, "$1***")
        .replace(userInfo, "$1***@")
        .replace(xtreamPath, "$1***/***/")
}
