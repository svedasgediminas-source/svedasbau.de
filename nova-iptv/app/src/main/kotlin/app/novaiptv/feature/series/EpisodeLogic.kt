package app.novaiptv.feature.series

import app.novaiptv.core.model.Episode
import app.novaiptv.core.model.Season
import app.novaiptv.core.model.WatchHistory

/** Episode ordering and "what to watch next" selection. Pure, no Android. */
object EpisodeLogic {
    enum class Kind { Start, Resume, Next, Rewatch }

    data class NextUp(val episode: Episode, val kind: Kind)

    /** Seasons by number (duplicates and empty seasons dropped), episodes by number, ids unique. */
    fun sortSeasons(seasons: List<Season>): List<Season> {
        val seen = HashSet<String>()
        return seasons
            .sortedBy { it.number }
            .distinctBy { it.number }
            .map { season ->
                season.copy(
                    episodes = season.episodes
                        .sortedWith(compareBy<Episode>({ it.seasonNumber }, { it.episodeNumber }))
                        .filter { seen.add(it.id) },
                )
            }
            .filter { it.episodes.isNotEmpty() }
    }

    fun episodeCode(season: Int, episode: Int): String = "S$season E$episode"

    fun episodeLabel(episode: Episode): String {
        val code = episodeCode(episode.seasonNumber, episode.episodeNumber)
        return if (episode.title.isBlank()) code else "$code · ${episode.title}"
    }

    /**
     * Picks the episode for the primary button:
     * - nothing watched -> first episode ([Kind.Start]);
     * - last watched episode unfinished -> resume it;
     * - last watched finished -> next unfinished after it, else the first unfinished overall;
     * - everything finished -> first episode again ([Kind.Rewatch]).
     */
    fun nextUp(seasons: List<Season>, history: Map<String, WatchHistory>): NextUp? {
        val ordered = sortSeasons(seasons).flatMap { it.episodes }
        if (ordered.isEmpty()) return null
        val indexById = ordered.withIndex().associate { it.value.id to it.index }
        val last = history.values
            .filter { it.contentId in indexById }
            .maxByOrNull { it.lastWatchedAtMillis }
            ?: return NextUp(ordered.first(), Kind.Start)
        val lastIndex = indexById.getValue(last.contentId)
        if (!last.isFinished) return NextUp(ordered[lastIndex], Kind.Resume)

        fun unfinished(e: Episode) = history[e.id]?.isFinished != true
        val candidate = ordered.drop(lastIndex + 1).firstOrNull(::unfinished)
            ?: ordered.firstOrNull(::unfinished)
            ?: return NextUp(ordered.first(), Kind.Rewatch)
        val h = history[candidate.id]
        val kind = if (h != null && !h.isFinished && h.positionMillis > 0) Kind.Resume else Kind.Next
        return NextUp(candidate, kind)
    }

    /** Season number to preselect: the season of [nextUp], else the first one. */
    fun initialSeason(seasons: List<Season>, nextUp: NextUp?): Int? {
        val id = nextUp?.episode?.id
        return seasons.firstOrNull { s -> id != null && s.episodes.any { it.id == id } }?.number
            ?: seasons.firstOrNull()?.number
    }
}
