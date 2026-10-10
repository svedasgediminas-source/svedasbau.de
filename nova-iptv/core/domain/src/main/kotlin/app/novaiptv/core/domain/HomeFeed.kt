package app.novaiptv.core.domain

import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.WatchHistory

/**
 * Local, history-based recommendation: no server, no AI. Picks a hero item from what the user
 * watches most, falling back to the newest catalogue item.
 */
object Recommendations {
    sealed interface Hero {
        data class ChannelHero(val channel: Channel) : Hero
        data class MovieHero(val movie: Movie) : Hero
        data class SeriesHero(val series: Series) : Hero
    }

    fun pickHero(
        history: List<WatchHistory>,
        favoriteChannels: List<Channel>,
        recentMovies: List<Movie>,
        recentSeries: List<Series>,
    ): Hero? {
        val unfinishedVod = history.firstOrNull { it.contentType != ContentType.Channel && !it.isFinished }
        if (unfinishedVod != null) {
            recentMovies.firstOrNull { it.id == unfinishedVod.contentId }?.let { return Hero.MovieHero(it) }
            recentSeries.firstOrNull { it.id == (unfinishedVod.parentId ?: unfinishedVod.contentId) }?.let { return Hero.SeriesHero(it) }
        }
        val mostWatchedChannelId = history.filter { it.contentType == ContentType.Channel }
            .groupingBy { it.contentId }.eachCount().maxByOrNull { it.value }?.key
        favoriteChannels.firstOrNull { it.id == mostWatchedChannelId }?.let { return Hero.ChannelHero(it) }
        recentMovies.firstOrNull { it.posterUrl != null }?.let { return Hero.MovieHero(it) }
        recentSeries.firstOrNull { it.posterUrl != null }?.let { return Hero.SeriesHero(it) }
        return favoriteChannels.firstOrNull()?.let { Hero.ChannelHero(it) }
    }
}
