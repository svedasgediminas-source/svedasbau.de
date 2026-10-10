package app.novaiptv.feature.playlists

/** Values of [app.novaiptv.navigation.Route.AddPlaylist.type]. */
object PlaylistType {
    const val M3U_URL = "m3u_url"
    const val M3U_FILE = "m3u_file"
    const val XTREAM = "xtream"

    val all: List<String> = listOf(M3U_URL, M3U_FILE, XTREAM)
}
