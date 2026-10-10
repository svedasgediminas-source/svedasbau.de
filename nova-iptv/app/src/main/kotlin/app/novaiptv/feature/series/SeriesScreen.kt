package app.novaiptv.feature.series

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.novaiptv.R
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.PlaylistRepository
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.VodCategory
import app.novaiptv.core.model.WatchHistory
import app.novaiptv.di.LocalAppContainer
import app.novaiptv.feature.movies.CatalogSource
import app.novaiptv.feature.movies.FocusedInfo
import app.novaiptv.feature.movies.VodCatalogContent
import app.novaiptv.feature.movies.VodCatalogViewModel
import app.novaiptv.feature.movies.VodFormat
import kotlinx.coroutines.flow.Flow

private class SeriesCatalogSource(private val vod: VodRepository) : CatalogSource<Series> {
    override fun categories(playlistId: Long): Flow<List<VodCategory>> = vod.observeSeriesCategories(playlistId)
    override fun items(playlistId: Long, categoryId: String?): Flow<List<Series>> = vod.observeSeries(playlistId, categoryId)
    override fun idOf(item: Series): String = item.id
    override fun categoryOf(item: Series): String? = item.categoryId

    /** Unfinished episodes, one card per series (the most recent episode), opening the series. */
    override fun continueWatching(history: List<WatchHistory>, playlistId: Long): List<WatchHistory> =
        history.filter {
            it.contentType == ContentType.Episode && it.playlistId == playlistId && !it.isFinished && !it.parentId.isNullOrBlank()
        }
            .sortedByDescending { it.lastWatchedAtMillis }
            .distinctBy { it.parentId }
}

internal class SeriesViewModel(
    vod: VodRepository,
    prefs: PreferencesRepository,
    playlists: PlaylistRepository,
    history: HistoryRepository,
) : VodCatalogViewModel<Series>(SeriesCatalogSource(vod), prefs, playlists, history)

@Composable
fun SeriesScreen(onOpenSeries: (String) -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel { SeriesViewModel(c.vod, c.preferences, c.playlists, c.history) }
    val state by vm.state.collectAsStateWithLifecycle()

    VodCatalogContent(
        state = state,
        screenTitle = stringResource(R.string.vod_series_title),
        emptyTitle = stringResource(R.string.vod_no_series_title),
        emptyMessage = stringResource(R.string.vod_no_vod_message),
        itemKey = { it.id },
        itemTitle = { it.name },
        itemImage = { it.posterUrl },
        itemSubtitle = { it.year?.toString() },
        itemInfo = { series ->
            FocusedInfo(
                title = series.name,
                meta = VodFormat.metaLine(series.year?.toString(), VodFormat.rating(series.rating), series.genre),
                plot = series.plot,
            )
        },
        onSelectCategory = vm::selectCategory,
        onOpenItem = { onOpenSeries(it.id) },
        onOpenContinue = { entry -> onOpenSeries(entry.parentId ?: entry.contentId) },
    )
}
