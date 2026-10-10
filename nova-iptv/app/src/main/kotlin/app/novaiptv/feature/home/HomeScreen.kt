package app.novaiptv.feature.home

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import app.novaiptv.core.designsystem.NovaProgressBar
import app.novaiptv.core.designsystem.PosterCard
import app.novaiptv.core.domain.Recommendations
import app.novaiptv.core.model.ContentType
import app.novaiptv.di.LocalAppContainer
import coil3.compose.AsyncImage
import java.util.Date
import kotlinx.coroutines.delay

/** Hero height: roughly 45 % of a 1080p TV screen (960x540 dp), leaving the first rail visible. */
private val HeroHeight = 264.dp

/** Compact live tiles for Home rails: five fit next to each other on a 960 dp wide TV screen. */
private val HomeChannelCardWidth = 164.dp
private val HomeChannelCardHeight = 128.dp

@Composable
fun HomeScreen(
    onPlayChannel: (String) -> Unit,
    onPlayMovie: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onAddPlaylist: (type: String) -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens the TV Guide (live hero's [TV Guide] button). */
    onOpenGuide: () -> Unit = {},
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
            onOpenGuide = onOpenGuide,
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
    onOpenGuide: () -> Unit,
) {
    val heroFocus = remember { FocusRequester() }
    val hasHero = state.hero != null || state.liveHero != null
    // Initial focus on the hero's primary button, once per destination: the flag is saved with the
    // destination state, so coming back from the player keeps the restored focus instead.
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    var heroFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Whenever the hero's button gains focus, show the hero fully (bring-into-view only scrolls
    // until the button is visible, which left the hero's top under the navigation bar).
    LaunchedEffect(heroFocused) {
        if (heroFocused) runCatching { listState.animateScrollToItem(0) }
    }
    LaunchedEffect(hasHero) {
        if (hasHero && !initialFocusDone) {
            // The lazy hero item is attached a frame after composition; retry briefly.
            for (attempt in 0 until 20) {
                if (heroFocused) break
                runCatching { heroFocus.requestFocus() }
                delay(50)
            }
            initialFocusDone = true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = NovaDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(NovaDimens.RailSpacing / 2),
    ) {
        val vodHero = state.hero
        val liveHero = state.liveHero
        if (vodHero != null) {
            item(key = "hero") {
                HeroBanner(
                    hero = vodHero,
                    playFocus = heroFocus,
                    onPlayFocused = { heroFocused = it },
                    onPlay = {
                        when (val src = vodHero.source) {
                            is Recommendations.Hero.MovieHero -> onPlayMovie(src.movie.id)
                            is Recommendations.Hero.SeriesHero ->
                                vodHero.resumeEpisodeId?.let(onPlayEpisode) ?: onOpenSeries(src.series.id)
                            is Recommendations.Hero.ChannelHero -> onPlayChannel(src.channel.id)
                        }
                    },
                    onDetails = when (val src = vodHero.source) {
                        is Recommendations.Hero.MovieHero -> ({ onOpenMovie(src.movie.id) })
                        is Recommendations.Hero.SeriesHero -> ({ onOpenSeries(src.series.id) })
                        is Recommendations.Hero.ChannelHero -> null
                    },
                )
            }
        } else if (liveHero != null) {
            item(key = "liveHero") {
                LiveHeroBanner(
                    hero = liveHero,
                    nowMillis = state.nowMillis,
                    showNumber = state.showChannelNumbers,
                    playFocus = heroFocus,
                    onPlayFocused = { heroFocused = it },
                    onWatch = { onPlayChannel(liveHero.channel.id) },
                    onOpenGuide = onOpenGuide,
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
        if (state.favoriteChannels.isNotEmpty()) {
            item(key = "favorites") {
                ChannelRail(stringResource(R.string.home_favorite_channels), state.favoriteChannels, state, onPlayChannel)
            }
        }
        if (state.recentChannels.isNotEmpty()) {
            item(key = "recentChannels") {
                ChannelRail(stringResource(R.string.home_recent_channels), state.recentChannels, state, onPlayChannel)
            }
        }
        if (state.nowOnTv.isNotEmpty()) {
            item(key = "now") {
                ChannelRail(stringResource(R.string.home_now_on_tv), state.nowOnTv, state, onPlayChannel)
            }
        }
        state.groupRails.forEach { rail ->
            item(key = "group:${rail.name}") {
                val title = rail.name.ifBlank { stringResource(R.string.home_group_other) }
                ChannelRail(title, rail.channels, state, onPlayChannel)
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
                // The caller's size wins over the design-system default (first size modifier applies).
                modifier = Modifier.width(HomeChannelCardWidth).height(HomeChannelCardHeight),
            )
        }
    }
}

/** Soft aurora backdrop drawn from the palette: accent glow top right, violet glow bottom right. */
private fun Modifier.auroraBackdrop(): Modifier = drawBehind {
    drawRect(
        Brush.horizontalGradient(
            0f to NovaColors.Background,
            0.55f to NovaColors.Surface.copy(alpha = 0.55f),
            1f to NovaColors.SurfaceRaised.copy(alpha = 0.65f),
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(NovaColors.Accent.copy(alpha = 0.20f), Color.Transparent),
            center = Offset(size.width * 0.80f, size.height * 0.30f),
            radius = size.width * 0.40f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(NovaColors.Secondary.copy(alpha = 0.14f), Color.Transparent),
            center = Offset(size.width * 0.97f, size.height * 0.95f),
            radius = size.width * 0.28f,
        ),
    )
    // Fade into the page so the first rail starts on a clean background.
    drawRect(Brush.verticalGradient(0.72f to Color.Transparent, 1f to NovaColors.Background))
}

@Composable
private fun LiveBadge() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NovaColors.Live)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color.White))
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.home_live_badge),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

@Composable
private fun formatTime(millis: Long): String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    return format.format(Date(millis))
}

@Composable
private fun LiveHeroBanner(
    hero: LiveHero,
    nowMillis: Long,
    showNumber: Boolean,
    playFocus: FocusRequester,
    onPlayFocused: (Boolean) -> Unit,
    onWatch: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val channel = hero.channel
    val now = hero.now
    Box(
        Modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .auroraBackdrop(),
    ) {
        // Large logo / initials tile on the right, framed by a faint accent ring.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = NovaDimens.ScreenPadding * 2)
                .size(176.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(NovaColors.SurfaceRaised.copy(alpha = 0.7f))
                .border(BorderStroke(2.dp, NovaColors.Accent.copy(alpha = 0.35f)), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(url = channel.logoUrl, name = channel.name, modifier = Modifier.size(132.dp))
        }
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = NovaDimens.ScreenPadding)
                .widthIn(max = 600.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveBadge()
                Spacer(Modifier.width(12.dp))
                val group = channel.groupName?.takeIf { it.isNotBlank() }
                if (group != null) {
                    Text(
                        text = group.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = NovaColors.Accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NovaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showNumber && channel.number > 0) {
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.home_channel_number, channel.number),
                        style = MaterialTheme.typography.titleMedium,
                        color = NovaColors.TextSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = now?.title ?: stringResource(R.string.home_live_no_info),
                style = MaterialTheme.typography.titleLarge,
                color = if (now != null) NovaColors.TextPrimary else NovaColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (now != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(now.startMillis) + " – " + formatTime(now.endMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = NovaColors.TextSecondary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(12.dp))
                    NovaProgressBar(
                        progress = HomeLogic.progress(now, nowMillis) ?: 0f,
                        modifier = Modifier.width(180.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    val leftMin = ((now.endMillis - nowMillis) / 60_000L).coerceAtLeast(0L)
                    Text(
                        text = stringResource(R.string.home_minutes_left, leftMin),
                        style = MaterialTheme.typography.labelMedium,
                        color = NovaColors.TextMuted,
                        maxLines = 1,
                    )
                }
            }
            val description = now?.description?.takeIf { it.isNotBlank() }
            val next = hero.next
            if (description != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (next != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.home_live_next, formatTime(next.startMillis), next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NovaButton(
                    text = stringResource(R.string.home_watch_now),
                    onClick = onWatch,
                    icon = Icons.Filled.PlayArrow,
                    primary = true,
                    modifier = Modifier.focusRequester(playFocus).onFocusChanged { onPlayFocused(it.isFocused || it.hasFocus) },
                )
                NovaButton(text = stringResource(R.string.nav_guide), onClick = onOpenGuide, icon = Icons.Filled.Schedule)
            }
        }
    }
}

@Composable
private fun HeroBanner(
    hero: HomeHero,
    playFocus: FocusRequester,
    onPlayFocused: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onDetails: (() -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(HeroHeight),
    ) {
        // Backdrop: artwork on the right two thirds, fading into the background on the left/bottom.
        if (!hero.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = hero.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.66f).align(Alignment.CenterEnd),
            )
        } else {
            Box(Modifier.fillMaxSize().auroraBackdrop())
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
                text = stringResource(R.string.home_hero_for_you),
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NovaButton(
                    text = stringResource(if (hero.resumeEpisodeId != null) R.string.common_resume else R.string.common_play),
                    onClick = onPlay,
                    icon = Icons.Filled.PlayArrow,
                    primary = true,
                    modifier = Modifier.focusRequester(playFocus).onFocusChanged { onPlayFocused(it.isFocused || it.hasFocus) },
                )
                if (onDetails != null) {
                    NovaButton(text = stringResource(R.string.home_details), onClick = onDetails, icon = Icons.Filled.Info)
                }
            }
        }
    }
}
