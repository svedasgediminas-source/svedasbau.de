package app.novaiptv.feature.favorites

import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.Favorite
import app.novaiptv.core.model.WatchHistory

/** Pure helpers for resolving favourites and history into ordered lists. */
object FavoritesLogic {
    /** Ids of [type] favourites in user order (sortOrder, then newest first), unique. */
    fun idsOf(favorites: List<Favorite>, type: ContentType): List<String> =
        favorites.filter { it.contentType == type }
            .sortedWith(compareBy<Favorite> { it.sortOrder }.thenByDescending { it.addedAtMillis })
            .map { it.contentId }
            .distinct()

    /** Reorders [items] to follow [ids]; ids without an item are skipped, duplicates dropped. */
    fun <T> orderByIds(items: List<T>, ids: List<String>, idOf: (T) -> String): List<T> {
        val byId = HashMap<String, T>(items.size * 2)
        for (item in items) byId.putIfAbsent(idOf(item), item)
        return ids.distinct().mapNotNull { byId[it] }
    }

    /** Recently watched channel ids, newest first, unique. */
    fun recentChannelIds(history: List<WatchHistory>): List<String> =
        history.filter { it.contentType == ContentType.Channel }
            .sortedByDescending { it.lastWatchedAtMillis }
            .map { it.contentId }
            .distinct()
}
