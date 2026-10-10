package app.novaiptv.feature.series

import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.Episode
import app.novaiptv.core.model.Season
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.model.WatchHistory
import app.novaiptv.feature.series.EpisodeLogic.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeLogicTest {
    private fun ep(s: Int, e: Int) = Episode("s${s}e$e", "show", s, e, "Ep $e", StreamSource("http://x/$s/$e"))

    private val seasons = listOf(
        Season("show", 2, "Season 2", listOf(ep(2, 2), ep(2, 1))),
        Season("show", 1, "Season 1", listOf(ep(1, 3), ep(1, 1), ep(1, 2))),
        Season("show", 3, "Season 3", emptyList()),
    )

    private fun watched(id: String, pos: Long, at: Long, dur: Long = 1_000_000) =
        id to WatchHistory(ContentType.Episode, id, 1, id, null, pos, dur, at, parentId = "show")

    @Test fun sortsSeasonsAndEpisodesAndDropsEmpty() {
        val sorted = EpisodeLogic.sortSeasons(seasons)
        assertEquals(listOf(1, 2), sorted.map { it.number })
        assertEquals(listOf("s1e1", "s1e2", "s1e3"), sorted[0].episodes.map { it.id })
        assertEquals(listOf("s2e1", "s2e2"), sorted[1].episodes.map { it.id })
    }

    @Test fun nothingWatchedStartsAtFirstEpisode() {
        assertEquals(EpisodeLogic.NextUp(ep(1, 1), Kind.Start), EpisodeLogic.nextUp(seasons, emptyMap()))
    }

    @Test fun unfinishedLastWatchedIsResumed() {
        val history = mapOf(watched("s1e1", 1_000_000, 10), watched("s1e2", 300_000, 20))
        assertEquals(EpisodeLogic.NextUp(ep(1, 2), Kind.Resume), EpisodeLogic.nextUp(seasons, history))
    }

    @Test fun finishedLastWatchedMovesToNextAcrossSeasons() {
        val history = mapOf(watched("s1e3", 990_000, 30))
        assertEquals(EpisodeLogic.NextUp(ep(2, 1), Kind.Next), EpisodeLogic.nextUp(seasons, history))
    }

    @Test fun skipsAlreadyFinishedFollowingEpisodes() {
        val history = mapOf(watched("s1e1", 1_000_000, 50), watched("s1e2", 1_000_000, 10), watched("s1e3", 1_000_000, 11))
        assertEquals(EpisodeLogic.NextUp(ep(2, 1), Kind.Next), EpisodeLogic.nextUp(seasons, history))
    }

    @Test fun wrapsToFirstUnfinishedWhenLastEpisodeDone() {
        val history = mapOf(watched("s2e2", 1_000_000, 50), watched("s1e1", 1_000_000, 10), watched("s1e2", 200_000, 5))
        assertEquals(EpisodeLogic.NextUp(ep(1, 2), Kind.Resume), EpisodeLogic.nextUp(seasons, history))
    }

    @Test fun everythingWatchedOffersRewatch() {
        val ids = listOf("s1e1", "s1e2", "s1e3", "s2e1", "s2e2")
        val history = ids.mapIndexed { i, id -> watched(id, 1_000_000, i.toLong()) }.toMap()
        assertEquals(EpisodeLogic.NextUp(ep(1, 1), Kind.Rewatch), EpisodeLogic.nextUp(seasons, history))
    }

    @Test fun ignoresHistoryOfOtherSeriesAndHandlesEmpty() {
        assertNull(EpisodeLogic.nextUp(emptyList(), emptyMap()))
        val history = mapOf(watched("other", 1, 99))
        assertEquals(Kind.Start, EpisodeLogic.nextUp(seasons, history)?.kind)
    }

    @Test fun labelsAndInitialSeason() {
        assertEquals("S1 E3 · Ep 3", EpisodeLogic.episodeLabel(ep(1, 3)))
        assertEquals("S2 E1", EpisodeLogic.episodeLabel(ep(2, 1).copy(title = " ")))
        val sorted = EpisodeLogic.sortSeasons(seasons)
        assertEquals(2, EpisodeLogic.initialSeason(sorted, EpisodeLogic.NextUp(ep(2, 1), Kind.Next)))
        assertEquals(1, EpisodeLogic.initialSeason(sorted, null))
        assertNull(EpisodeLogic.initialSeason(emptyList(), null))
    }
}
