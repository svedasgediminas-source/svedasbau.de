package app.novaiptv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.ChannelCard
import app.novaiptv.core.designsystem.ChannelLogo
import app.novaiptv.core.designsystem.ContentRail
import app.novaiptv.core.designsystem.EmptyState
import app.novaiptv.core.designsystem.ErrorState
import app.novaiptv.core.designsystem.LandscapeCard
import app.novaiptv.core.designsystem.LoadingState
import app.novaiptv.core.designsystem.NovaButton
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.designsystem.PosterCard
import app.novaiptv.core.domain.Recommendations
import app.novaiptv.core.model.ContentType
import app.novaiptv.di.LocalAppContainer
import coil3.compose.AsyncImage

@Composable
fun HomeScreen(
    onPlayChannel: (String) -> Unit,
    onPlayMovie: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onAddPlaylist: (type: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.uiState.collectAsStateWithLifecycle()

    when {
        state.isLoading -> LoadingState()
        !state.hasPlaylists -> OnboardingContent(onAddPlaylist = onAddPlaylist)
        state.failed -> ErrorState(message = stringResource(R.string.error_generic), onRetry = onOpenSettings)
        state.isEmpty -> EmptyState(
            title = stringResource(R.string.home_empty_title),
            message = stringResource(R.string.home_empty_message),
            actionLabel = stringResource(R.string.nav_settings),
            onAction = onOpenSettings,
        )
        else -> HomeContent(
            state = state,
            onPlayChannel = onPlayChannel,
            onPlayMovie = onPlayMovie,
            onPlayEpisode = onPlayEpisode,
            onOpenMovie = onOpenMovie,
            onOpenSeries = onOpenSeries,
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onPlayChannel: (String) -> Unit,
    onPlayMovie: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = NovaDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(NovaDimens.RailSpacing),
    ) {
        state.hero?.let { hero ->
            item(key = "hero") {
                HeroBanner(
                    hero = hero,
                    onPlay = {
                        when (val src = hero.source) {
                            is Recommendations.Hero.MovieHero -> onPlayMovie(src.movie.id)
                            is Recommendations.Hero.SeriesHero ->
                                hero.resumeEpisodeId?.let(onPlayEpisode) ?: onOpenSeries(src.series.id)
                            is Recommendations.Hero.ChannelHero -> onPlayChannel(src.channel.id)
                        }
                    },
                    onDetails = when (val src = hero.source) {
                        is Recommendations.Hero.MovieHero -> ({ onOpenMovie(src.movie.id) })
                        is Recommendations.Hero.SeriesHero -> ({ onOpenSeries(src.series.id) })
                        is Recommendations.Hero.ChannelHero -> null
                    },
                )
            }
        }
        if (state.continueWatching.isNotEmpty()) {
            item(key = "continue") {
                ContentRail(title = stringResource(R.string.home_continue_watching)) {
                    items(state.continueWatching, key = { "${it.contentType}:${it.contentId}" }) { entry ->
                        val remainingMin = ((entry.durationMillis - entry.positionMillis) / 60_000L).coerceAtLeast(0L)
                        LandscapeCard(
                            title = entry.title,
                            imageUrl = entry.imageUrl,
                            progress = entry.progress,
                            subtitle = if (entry.durationMillis > 0) stringResource(R.string.home_minutes_left, remainingMin) else null,
                            onClick = {
                                when (entry.contentType) {
                                    ContentType.Movie -> onPlayMovie(entry.contentId)
                                    ContentType.Episode -> onPlayEpisode(entry.contentId)
                                    ContentType.Series -> onOpenSeries(entry.contentId)
                                    ContentType.Channel -> onPlayChannel(entry.contentId)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (state.nowOnTv.isNotEmpty()) {
            item(key = "now") {
                ChannelRail(stringResource(R.string.home_now_on_tv), state.nowOnTv, state, onPlayChannel)
            }
        }
        if (state.favoriteChannels.isNotEmpty() && !state.nowOnTvFromFavorites) {
            item(key = "favorites") {
                ChannelRail(stringResource(R.string.home_favorite_channels), state.favoriteChannels, state, onPlayChannel)
            }
        }
        if (state.recentChannels.isNotEmpty()) {
            item(key = "recentChannels") {
                ChannelRail(stringResource(R.string.home_recent_channels), state.recentChannels, state, onPlayChannel)
            }
        }
        if (state.movies.isNotEmpty()) {
            item(key = "movies") {
                ContentRail(title = stringResource(R.string.home_movies)) {
                    items(state.movies, key = { it.id }) { movie ->
                        PosterCard(
                            title = movie.name,
                            imageUrl = movie.posterUrl,
                            subtitle = movie.year?.toString(),
                            onClick = { onOpenMovie(movie.id) },
                        )
                    }
                }
            }
        }
        if (state.series.isNotEmpty()) {
            item(key = "series") {
                ContentRail(title = stringResource(R.string.home_series)) {
                    items(state.series, key = { it.id }) { series ->
                        PosterCard(
                            title = series.name,
                            imageUrl = series.posterUrl,
                            subtitle = series.year?.toString(),
                            onClick = { onOpenSeries(series.id) },
                        )
                    }
                }
            }
        }
        if (state.recentlyAdded.isNotEmpty()) {
            item(key = "added") {
                ContentRail(title = stringResource(R.string.home_recently_added)) {
                    items(state.recentlyAdded, key = {
                        when (it) {
                            is AddedItem.MovieItem -> "m:" + it.movie.id
                            is AddedItem.SeriesItem -> "s:" + it.series.id
                        }
                    }) { added ->
                        when (added) {
                            is AddedItem.MovieItem -> PosterCard(
                                title = added.movie.name,
                                imageUrl = added.movie.posterUrl,
                                subtitle = stringResource(R.string.home_badge_movie),
                                onClick = { onOpenMovie(added.movie.id) },
                            )
                            is AddedItem.SeriesItem -> PosterCard(
                                title = added.series.name,
                                imageUrl = added.series.posterUrl,
                                subtitle = stringResource(R.string.home_badge_series),
                                onClick = { onOpenSeries(added.series.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRail(
    title: String,
    channels: List<ChannelNow>,
    state: HomeUiState,
    onPlayChannel: (String) -> Unit,
) {
    ContentRail(title = title) {
        items(channels, key = { it.channel.id }) { item ->
            ChannelCard(
                name = item.channel.name,
                logoUrl = item.channel.logoUrl,
                number = if (state.showChannelNumbers) item.channel.number else null,
                nowTitle = item.now?.title,
                progress = item.progress(state.nowMillis),
                isFavorite = item.channel.id in state.favoriteChannelIds,
                onClick = { onPlayChannel(item.channel.id) },
            )
        }
    }
}

@Composable
private fun HeroBanner(hero: HomeHero, onPlay: () -> Unit, onDetails: (() -> Unit)?) {
    val isChannel = hero.source is Recommendations.Hero.ChannelHero
    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp),
    ) {
        // Backdrop: artwork on the right two thirds, fading into the background on the left/bottom.
        if (!isChannel && !hero.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = hero.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.66f).align(Alignment.CenterEnd),
            )
        } else {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.66f)
                    .align(Alignment.CenterEnd)
                    .background(Brush.radialGradient(listOf(NovaColors.AccentDim.copy(alpha = 0.55f), Color.Transparent))),
                contentAlignment = Alignment.Center,
            ) {
                if (isChannel) ChannelLogo(url = hero.imageUrl, name = hero.title, modifier = Modifier.size(160.dp))
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to NovaColors.Background,
                        0.38f to NovaColors.Background.copy(alpha = 0.92f),
                        0.75f to NovaColors.Background.copy(alpha = 0.2f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to NovaColors.Background)),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = NovaDimens.ScreenPadding)
                .widthIn(max = 560.dp),
        ) {
            Text(
                text = stringResource(
                    when (hero.source) {
                        is Recommendations.Hero.ChannelHero -> R.string.home_hero_live
                        else -> R.string.home_hero_for_you
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = NovaColors.Accent,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = hero.title,
                style = MaterialTheme.typography.displaySmall,
                color = NovaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hero.meta.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(hero.meta, style = MaterialTheme.typography.titleSmall, color = NovaColors.TextSecondary, maxLines = 1)
            }
            if (!hero.description.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = hero.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NovaButton(
                    text = stringResource(if (hero.resumeEpisodeId != null) R.string.common_resume else R.string.common_play),
                    onClick = onPlay,
                    icon = Icons.Filled.PlayArrow,
                    primary = true,
                )
                if (onDetails != null) {
                    NovaButton(text = stringResource(R.string.home_details), onClick = onDetails, icon = Icons.Filled.Info)
                }
            }
        }
    }
}
