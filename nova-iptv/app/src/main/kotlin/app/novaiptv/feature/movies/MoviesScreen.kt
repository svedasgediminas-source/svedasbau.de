package app.novaiptv.feature.movies

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
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.VodCategory
import app.novaiptv.core.model.WatchHistory
import app.novaiptv.di.LocalAppContainer
import kotlinx.coroutines.flow.Flow

private class MovieCatalogSource(private val vod: VodRepository) : CatalogSource<Movie> {
    override fun categories(playlistId: Long): Flow<List<VodCategory>> = vod.observeMovieCategories(playlistId)
    override fun items(playlistId: Long, categoryId: String?): Flow<List<Movie>> = vod.observeMovies(playlistId, categoryId)
    override fun idOf(item: Movie): String = item.id
    override fun categoryOf(item: Movie): String? = item.categoryId
    override fun continueWatching(history: List<WatchHistory>, playlistId: Long): List<WatchHistory> =
        history.filter { it.contentType == ContentType.Movie && it.playlistId == playlistId && !it.isFinished }
}

internal class MoviesViewModel(
    vod: VodRepository,
    prefs: PreferencesRepository,
    playlists: PlaylistRepository,
    history: HistoryRepository,
) : VodCatalogViewModel<Movie>(MovieCatalogSource(vod), prefs, playlists, history)

@Composable
fun MoviesScreen(onOpenMovie: (String) -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel { MoviesViewModel(c.vod, c.preferences, c.playlists, c.history) }
    val state by vm.state.collectAsStateWithLifecycle()

    VodCatalogContent(
        state = state,
        screenTitle = stringResource(R.string.vod_movies_title),
        emptyTitle = stringResource(R.string.vod_no_movies_title),
        emptyMessage = stringResource(R.string.vod_no_vod_message),
        itemKey = { it.id },
        itemTitle = { it.name },
        itemImage = { it.posterUrl },
        itemSubtitle = { it.year?.toString() },
        itemInfo = { movie ->
            FocusedInfo(
                title = movie.name,
                meta = VodFormat.metaLine(movie.year?.toString(), VodFormat.rating(movie.rating), movie.genre),
                plot = movie.plot,
            )
        },
        onSelectCategory = vm::selectCategory,
        onOpenItem = { onOpenMovie(it.id) },
        onOpenContinue = { onOpenMovie(it.contentId) },
    )
}
