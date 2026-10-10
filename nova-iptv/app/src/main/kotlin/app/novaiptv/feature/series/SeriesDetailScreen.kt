@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package app.novaiptv.feature.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.EmptyState
import app.novaiptv.core.designsystem.ErrorState
import app.novaiptv.core.designsystem.LoadingState
import app.novaiptv.core.designsystem.NovaButton
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.model.Episode
import app.novaiptv.core.model.Season
import app.novaiptv.core.model.Series
import app.novaiptv.core.model.WatchHistory
import app.novaiptv.di.LocalAppContainer
import app.novaiptv.feature.movies.DetailBackdrop
import app.novaiptv.feature.movies.PosterImage
import app.novaiptv.feature.movies.VodFormat
import app.novaiptv.feature.movies.durationText
import app.novaiptv.ui.errorMessage
import coil3.compose.AsyncImage

@Composable
fun SeriesDetailScreen(seriesId: String, onPlayEpisode: (String) -> Unit, onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "series:$seriesId") { SeriesDetailViewModel(seriesId, c.vod, c.favorites, c.history) }
    val info by vm.info.collectAsStateWithLifecycle()
    val seasons by vm.seasons.collectAsStateWithLifecycle()
    val history by vm.episodeHistory.collectAsStateWithLifecycle()
    val selectedSeason by vm.selectedSeason.collectAsStateWithLifecycle()
    val nextUp by vm.nextUp.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshHistory() }

    when (val i = info) {
        SeriesInfoState.Loading -> LoadingState(Modifier.fillMaxSize())
        SeriesInfoState.NotFound -> EmptyState(
            title = stringResource(R.string.vod_not_found),
            modifier = Modifier.fillMaxSize(),
            actionLabel = stringResource(R.string.common_back),
            onAction = onBack,
        )
        is SeriesInfoState.Loaded -> SeriesDetailContent(
            series = i.series,
            seasons = seasons,
            history = history,
            selectedSeason = selectedSeason,
            nextUp = nextUp,
            isFavorite = isFavorite,
            onPlayEpisode = onPlayEpisode,
            onSelectSeason = vm::selectSeason,
            onToggleFavorite = vm::toggleFavorite,
            onRetry = vm::loadSeasons,
        )
    }
}

@Composable
private fun SeriesDetailContent(
    series: Series,
    seasons: SeasonsState,
    history: Map<String, WatchHistory>,
    selectedSeason: Int?,
    nextUp: EpisodeLogic.NextUp?,
    isFavorite: Boolean,
    onPlayEpisode: (String) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onRetry: () -> Unit,
) {
    val primary = remember { FocusRequester() }
    val hasNextUp = nextUp != null
    // Focus the main action on entry, and again once episodes arrive (it moves to "Continue").
    LaunchedEffect(series.id, hasNextUp) {
        try {
            primary.requestFocus()
        } catch (ignored: IllegalStateException) {
        }
    }

    Box(Modifier.fillMaxSize()) {
        DetailBackdrop(series.posterUrl)
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = NovaDimens.ScreenPadding * 1.5f, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header", contentType = "header") {
                SeriesHeader(
                    series = series,
                    nextUp = nextUp,
                    isFavorite = isFavorite,
                    primary = primary,
                    onPlayEpisode = onPlayEpisode,
                    onToggleFavorite = onToggleFavorite,
                )
            }
            when (seasons) {
                SeasonsState.Loading -> item(key = "loading", contentType = "state") {
                    LoadingState(Modifier.fillMaxWidth().height(220.dp))
                }
                is SeasonsState.Error -> item(key = "error", contentType = "state") {
                    ErrorState(
                        message = errorMessage(seasons.error),
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        onRetry = onRetry,
                    )
                }
                is SeasonsState.Loaded -> {
                    val current = seasons.seasons.firstOrNull { it.number == selectedSeason } ?: seasons.seasons.firstOrNull()
                    if (current == null) {
                        item(key = "empty", contentType = "state") {
                            EmptyState(
                                title = stringResource(R.string.vod_no_episodes),
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                            )
                        }
                    } else {
                        item(key = "seasons", contentType = "seasons") {
                            SeasonChips(seasons = seasons.seasons, selected = current.number, onSelect = onSelectSeason)
                        }
                        items(current.episodes, key = { "e:${it.id}" }, contentType = { "episode" }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                entry = history[episode.id],
                                onClick = { onPlayEpisode(episode.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesHeader(
    series: Series,
    nextUp: EpisodeLogic.NextUp?,
    isFavorite: Boolean,
    primary: FocusRequester,
    onPlayEpisode: (String) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PosterImage(series.posterUrl, Modifier.width(200.dp).aspectRatio(2f / 3f))
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = series.name,
                style = MaterialTheme.typography.displaySmall,
                color = NovaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = VodFormat.metaLine(series.year?.toString(), VodFormat.rating(series.rating), series.genre)
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(text = meta, style = MaterialTheme.typography.titleMedium, color = NovaColors.TextSecondary, maxLines = 1)
            }
            if (!series.plot.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = series.plot.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NovaColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.85f),
                )
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (nextUp != null) {
                    val code = EpisodeLogic.episodeCode(nextUp.episode.seasonNumber, nextUp.episode.episodeNumber)
                    val label = when (nextUp.kind) {
                        EpisodeLogic.Kind.Start -> stringResource(R.string.vod_play_episode, code)
                        EpisodeLogic.Kind.Resume, EpisodeLogic.Kind.Next -> stringResource(R.string.vod_continue_episode, code)
                        EpisodeLogic.Kind.Rewatch -> stringResource(R.string.vod_watch_again, code)
                    }
                    NovaButton(
                        text = label,
                        onClick = { onPlayEpisode(nextUp.episode.id) },
                        icon = Icons.Filled.PlayArrow,
                        primary = true,
                        modifier = Modifier.focusRequester(primary),
                    )
                }
                NovaButton(
                    text = stringResource(if (isFavorite) R.string.common_favorite_remove else R.string.common_favorite_add),
                    onClick = onToggleFavorite,
                    icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    modifier = if (nextUp == null) Modifier.focusRequester(primary) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun SeasonChips(seasons: List<Season>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.vod_seasons),
            style = MaterialTheme.typography.titleLarge,
            color = NovaColors.TextPrimary,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(seasons, key = { "s:${it.number}" }) { season ->
                FilterChip(
                    selected = season.number == selected,
                    onClick = { onSelect(season.number) },
                ) {
                    Text(text = stringResource(R.string.vod_season_n, season.number))
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, entry: WatchHistory?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(NovaDimens.CardCorner)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NovaColors.Surface.copy(alpha = 0.72f),
            contentColor = NovaColors.TextPrimary,
            focusedContainerColor = NovaColors.SurfaceBright,
            focusedContentColor = NovaColors.TextPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(NovaDimens.FocusBorder, NovaColors.Accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(208.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NovaColors.SurfaceRaised),
            ) {
                if (!episode.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                val progress = entry?.progress ?: 0f
                if (progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(NovaColors.Scrim),
                    ) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(NovaColors.Accent))
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = EpisodeLogic.episodeLabel(episode),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = when {
                    entry?.isFinished == true -> stringResource(R.string.vod_watched)
                    else -> null
                }
                val meta = VodFormat.metaLine(durationText(episode.durationSeconds), status)
                if (meta.isNotEmpty()) {
                    Text(text = meta, style = MaterialTheme.typography.bodyMedium, color = NovaColors.Accent, maxLines = 1)
                }
                if (!episode.plot.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = episode.plot.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
