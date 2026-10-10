package app.novaiptv.feature.player

/** What [PlayerScreen] should play. Built by the navigation layer from the Play* routes. */
sealed interface PlayerArgs {
    /** Live channel; [group] keeps channel up/down inside the list the user came from (null = all). */
    data class Channel(val channelId: String, val group: String?) : PlayerArgs

    /** Archived programme of [channelId] between [startMillis] and [endMillis] (epoch millis, UTC). */
    data class Catchup(val channelId: String, val startMillis: Long, val endMillis: Long) : PlayerArgs

    data class Movie(val movieId: String) : PlayerArgs

    data class Episode(val episodeId: String) : PlayerArgs
}
