package app.novaiptv.feature.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.EmptyState
import app.novaiptv.core.designsystem.LoadingState
import app.novaiptv.core.designsystem.NovaButton
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.domain.FavoritesRepository
import app.novaiptv.core.domain.HistoryRepository
import app.novaiptv.core.domain.VodRepository
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.Movie
import app.novaiptv.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface MovieDetailState {
    data object Loading : MovieDetailState
    data object NotFound : MovieDetailState
    data class Loaded(val movie: Movie) : MovieDetailState
}

internal class MovieDetailViewModel(
    private val movieId: String,
    private val vod: VodRepository,
    private val favorites: FavoritesRepository,
    private val history: HistoryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<MovieDetailState>(MovieDetailState.Loading)
    val state: StateFlow<MovieDetailState> = _state.asStateFlow()

    /** Resume position in ms, or null when the movie should start from the beginning. */
    private val _resumeMillis = MutableStateFlow<Long?>(null)
    val resumeMillis: StateFlow<Long?> = _resumeMillis.asStateFlow()

    val isFavorite: StateFlow<Boolean> = favorites.observeIsFavorite(ContentType.Movie, movieId)
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            _state.value = try {
                vod.getMovie(movieId)?.let { MovieDetailState.Loaded(it) } ?: MovieDetailState.NotFound
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MovieDetailState.NotFound
            }
        }
    }

    /** Called whenever the screen resumes (e.g. back from the player) to pick up the new position. */
    fun refreshHistory() {
        viewModelScope.launch {
            _resumeMillis.value = try {
                VodFormat.resumePositionMillis(history.get(ContentType.Movie, movieId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toggleFavorite() {
        val movie = (_state.value as? MovieDetailState.Loaded)?.movie ?: return
        viewModelScope.launch {
            try {
                favorites.toggle(ContentType.Movie, movie.id, movie.playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
            }
        }
    }

    /** Clears the saved position (the player resumes from history) and then starts playback. */
    fun startOver(play: () -> Unit) {
        viewModelScope.launch {
            try {
                history.remove(ContentType.Movie, movieId)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
            }
            _resumeMillis.value = null
            play()
        }
    }
}

@Composable
fun MovieDetailScreen(movieId: String, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "movie:$movieId") { MovieDetailViewModel(movieId, c.vod, c.favorites, c.history) }
    val state by vm.state.collectAsStateWithLifecycle()
    val resumeMillis by vm.resumeMillis.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshHistory() }

    when (val s = state) {
        MovieDetailState.Loading -> LoadingState(Modifier.fillMaxSize())
        MovieDetailState.NotFound -> EmptyState(
            title = stringResource(R.string.vod_not_found),
            modifier = Modifier.fillMaxSize(),
            actionLabel = stringResource(R.string.common_back),
            onAction = onBack,
        )
        is MovieDetailState.Loaded -> MovieDetailContent(
            movie = s.movie,
            resumeMillis = resumeMillis,
            isFavorite = isFavorite,
            onPlay = { onPlay(s.movie.id) },
            onStartOver = { vm.startOver { onPlay(s.movie.id) } },
            onToggleFavorite = vm::toggleFavorite,
        )
    }
}

@Composable
private fun MovieDetailContent(
    movie: Movie,
    resumeMillis: Long?,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onStartOver: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val primary = remember { FocusRequester() }
    LaunchedEffect(movie.id) {
        try {
            primary.requestFocus()
        } catch (ignored: IllegalStateException) {
        }
    }

    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(movie.posterUrl)
        Row(
            Modifier.fillMaxSize().padding(horizontal = NovaDimens.ScreenPadding * 1.5f, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosterImage(movie.posterUrl, Modifier.width(260.dp).aspectRatio(2f / 3f))
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = movie.name,
                    style = MaterialTheme.typography.displaySmall,
                    color = NovaColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = VodFormat.metaLine(movie.year?.toString(), durationText(movie.durationSeconds), VodFormat.rating(movie.rating))
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(text = meta, style = MaterialTheme.typography.titleMedium, color = NovaColors.TextSecondary)
                }
                if (!movie.genre.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = movie.genre.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NovaColors.Accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!movie.plot.isNullOrBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = movie.plot.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NovaColors.TextSecondary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.85f),
                    )
                }
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    NovaButton(
                        text = if (resumeMillis != null) {
                            stringResource(R.string.vod_resume_at, VodFormat.timestamp(resumeMillis))
                        } else {
                            stringResource(R.string.common_play)
                        },
                        onClick = onPlay,
                        icon = Icons.Filled.PlayArrow,
                        primary = true,
                        modifier = Modifier.focusRequester(primary),
                    )
                    if (resumeMillis != null) {
                        NovaButton(
                            text = stringResource(R.string.vod_start_over),
                            onClick = onStartOver,
                            icon = Icons.Filled.Refresh,
                        )
                    }
                    NovaButton(
                        text = stringResource(if (isFavorite) R.string.common_favorite_remove else R.string.common_favorite_add),
                        onClick = onToggleFavorite,
                        icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    )
                }
            }
        }
    }
}
