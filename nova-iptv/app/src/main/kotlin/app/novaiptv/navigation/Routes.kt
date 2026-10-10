package app.novaiptv.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation Compose destinations. Top-level tabs are shown in the top navigation bar;
 * the rest are pushed on top. String ids (channel/movie/series ids) contain ':' and are passed
 * as-is; Navigation handles encoding.
 */
sealed interface Route {
    @Serializable data object Home : Route
    @Serializable data object LiveTv : Route
    @Serializable data object Guide : Route
    @Serializable data object Movies : Route
    @Serializable data object Series : Route
    @Serializable data object Favorites : Route
    @Serializable data object Search : Route
    @Serializable data object Settings : Route

    @Serializable data object Playlists : Route
    @Serializable data class AddPlaylist(val type: String) : Route // "m3u_url" | "m3u_file" | "xtream"
    @Serializable data class MovieDetail(val movieId: String) : Route
    @Serializable data class SeriesDetail(val seriesId: String) : Route

    /** Live channel; [group] keeps channel up/down inside the list the user came from. */
    @Serializable data class PlayChannel(val channelId: String, val group: String? = null) : Route
    @Serializable data class PlayCatchup(val channelId: String, val startMillis: Long, val endMillis: Long) : Route
    @Serializable data class PlayMovie(val movieId: String) : Route
    @Serializable data class PlayEpisode(val episodeId: String) : Route
}

val TopLevelRoutes: List<Route> = listOf(
    Route.Home, Route.LiveTv, Route.Guide, Route.Movies, Route.Series, Route.Favorites, Route.Search, Route.Settings,
)
