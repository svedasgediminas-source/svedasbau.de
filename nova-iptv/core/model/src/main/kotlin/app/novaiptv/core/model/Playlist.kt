package app.novaiptv.core.model

/** A user-added IPTV source. Credentials are never stored here; see [PlaylistSource.Xtream.credentialKey]. */
data class Playlist(
    val id: Long,
    val name: String,
    val source: PlaylistSource,
    val epgUrls: List<String> = emptyList(),
    val autoRefreshHours: Int = 24,
    val lastRefreshedAtMillis: Long? = null,
    val lastError: String? = null,
)

sealed interface PlaylistSource {
    /** Remote M3U/M3U8 playlist fetched over HTTP(S). */
    data class M3uUrl(val url: String) : PlaylistSource

    /** M3U imported from a local file (content:// or file:// URI). Not auto-refreshed. */
    data class M3uFile(val uri: String, val displayName: String) : PlaylistSource

    /**
     * Xtream Codes compatible portal. [credentialKey] references the username/password held
     * in encrypted storage (CredentialStore); they are never persisted in plain text.
     */
    data class Xtream(val serverUrl: String, val credentialKey: String) : PlaylistSource
}

data class XtreamCredentials(val serverUrl: String, val username: String, val password: String) {
    override fun toString(): String = "XtreamCredentials(serverUrl=$serverUrl, username=***, password=***)"
}
