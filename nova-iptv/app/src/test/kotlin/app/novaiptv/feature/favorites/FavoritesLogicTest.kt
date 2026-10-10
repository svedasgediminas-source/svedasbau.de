package app.novaiptv.feature.favorites

import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.Favorite
import app.novaiptv.core.model.WatchHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesLogicTest {
    private fun fav(type: ContentType, id: String, added: Long, order: Int = 0) = Favorite(type, id, 1, added, order)

    @Test fun idsOfFiltersAndOrders() {
        val favs = listOf(
            fav(ContentType.Channel, "a", 1), fav(ContentType.Movie, "m", 2), fav(ContentType.Channel, "b", 3),
            fav(ContentType.Channel, "c", 4, order = -1), fav(ContentType.Channel, "a", 0),
        )
        assertEquals(listOf("c", "b", "a"), FavoritesLogic.idsOf(favs, ContentType.Channel))
        assertEquals(listOf("m"), FavoritesLogic.idsOf(favs, ContentType.Movie))
    }

    @Test fun orderByIdsSkipsMissing() {
        val items = listOf("x" to 1, "y" to 2, "z" to 3)
        assertEquals(listOf("z", "x"), FavoritesLogic.orderByIds(items, listOf("z", "gone", "x", "z")) { it.first }.map { it.first })
    }

    @Test fun recentChannelIdsNewestFirst() {
        fun h(type: ContentType, id: String, at: Long) = WatchHistory(type, id, 1, id, null, 0, 0, at)
        val history = listOf(h(ContentType.Channel, "a", 1), h(ContentType.Movie, "m", 9), h(ContentType.Channel, "b", 5), h(ContentType.Channel, "a", 7))
        assertEquals(listOf("a", "b"), FavoritesLogic.recentChannelIds(history))
    }
}
