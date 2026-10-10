package app.novaiptv.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.ChannelCard
import app.novaiptv.core.designsystem.ContentRail
import app.novaiptv.core.designsystem.EmptyState
import app.novaiptv.core.designsystem.LoadingState
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.designsystem.PosterCard
import app.novaiptv.core.model.Channel
import app.novaiptv.di.LocalAppContainer

@Composable
fun FavoritesScreen(
    onPlayChannel: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val c = LocalAppContainer.current
    val vm = viewModel { FavoritesViewModel(c.favorites, c.channels, c.vod, c.history, c.epg, c.clock) }
    val state by vm.state.collectAsStateWithLifecycle()

    when {
        state.isLoading -> LoadingState(Modifier.fillMaxSize())
        state.isEmpty -> EmptyState(
            title = stringResource(R.string.favorites_empty_title),
            message = stringResource(R.string.favorites_empty_message),
            modifier = Modifier.fillMaxSize(),
        )
        else -> FavoritesContent(
            state = state,
            onPlayChannel = onPlayChannel,
            onOpenMovie = onOpenMovie,
            onOpenSeries = onOpenSeries,
        )
    }
}

@Composable
private fun FavoritesContent(
    state: FavoritesUiState,
    onPlayChannel: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val favoriteIds = state.favoriteChannelIds
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(NovaDimens.RailSpacing),
    ) {
        if (!state.hasFavorites) {
            item(key = "hint", contentType = "hint") {
                Text(
                    text = stringResource(R.string.favorites_empty_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NovaColors.TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NovaDimens.ScreenPadding),
                )
            }
        }
        if (state.channels.isNotEmpty()) {
            item(key = "channels", contentType = "rail") {
                ChannelRail(
                    title = stringResource(R.string.favorites_channels),
                    channels = state.channels,
                    nowTitles = state.nowTitles,
                    favoriteIds = favoriteIds,
                    onPlayChannel = onPlayChannel,
                )
            }
        }
        if (state.movies.isNotEmpty()) {
            item(key = "movies", contentType = "rail") {
                ContentRail(title = stringResource(R.string.favorites_movies)) {
                    items(state.movies, key = { it.id }) { movie ->
                        PosterCard(
                            title = movie.name,
                            imageUrl = movie.posterUrl,
                            onClick = { onOpenMovie(movie.id) },
                            subtitle = movie.year?.toString(),
                        )
                    }
                }
            }
        }
        if (state.series.isNotEmpty()) {
            item(key = "series", contentType = "rail") {
                ContentRail(title = stringResource(R.string.favorites_series)) {
                    items(state.series, key = { it.id }) { series ->
                        PosterCard(
                            title = series.name,
                            imageUrl = series.posterUrl,
                            onClick = { onOpenSeries(series.id) },
                            subtitle = series.year?.toString(),
                        )
                    }
                }
            }
        }
        if (state.recentChannels.isNotEmpty()) {
            item(key = "recent", contentType = "rail") {
                ChannelRail(
                    title = stringResource(R.string.favorites_recent),
                    channels = state.recentChannels,
                    nowTitles = state.nowTitles,
                    favoriteIds = favoriteIds,
                    onPlayChannel = onPlayChannel,
                )
            }
        }
    }
}

@Composable
private fun ChannelRail(
    title: String,
    channels: List<Channel>,
    nowTitles: Map<String, String>,
    favoriteIds: Set<String>,
    onPlayChannel: (String) -> Unit,
) {
    ContentRail(title = title) {
        items(channels, key = { it.id }) { channel ->
            ChannelCard(
                name = channel.name,
                logoUrl = channel.logoUrl,
                number = channel.number,
                nowTitle = nowTitles[channel.id],
                onClick = { onPlayChannel(channel.id) },
                isFavorite = channel.id in favoriteIds,
            )
        }
    }
}
