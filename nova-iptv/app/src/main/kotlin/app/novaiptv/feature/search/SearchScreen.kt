@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package app.novaiptv.feature.search

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SuggestionChip
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.ChannelCard
import app.novaiptv.core.designsystem.ContentRail
import app.novaiptv.core.designsystem.EmptyState
import app.novaiptv.core.designsystem.LandscapeCard
import app.novaiptv.core.designsystem.LoadingState
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.designsystem.NovaTextField
import app.novaiptv.core.designsystem.PosterCard
import app.novaiptv.di.LocalAppContainer
import java.util.Date

@Composable
fun SearchScreen(
    onPlayChannel: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val c = LocalAppContainer.current
    val vm = viewModel { SearchViewModel(c.channels, c.vod, c.epg, c.preferences, c.playlists, c.clock) }
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    // Recent searches live only in memory (saved across configuration changes / back stack).
    var recentRaw by rememberSaveable { mutableStateOf("") }
    val recent = remember(recentRaw) { RecentSearches.decode(recentRaw) }
    val rememberQuery: () -> Unit = {
        recentRaw = RecentSearches.encode(RecentSearches.add(RecentSearches.decode(recentRaw), query))
    }

    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        NovaTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            label = stringResource(R.string.search_label),
            modifier = Modifier
                .padding(horizontal = NovaDimens.ScreenPadding)
                .fillMaxWidth(0.6f),
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
            onImeAction = rememberQuery,
        )
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            SearchUiState.Idle -> SearchIdle(recent = recent, onPick = vm::onQueryChange)
            SearchUiState.NoPlaylist -> EmptyState(
                title = stringResource(R.string.search_no_playlist_title),
                message = stringResource(R.string.search_no_playlist_message),
                modifier = Modifier.fillMaxSize(),
            )
            SearchUiState.Loading -> LoadingState(Modifier.fillMaxSize())
            is SearchUiState.Results -> if (s.results.isEmpty) {
                EmptyState(
                    title = stringResource(R.string.search_no_results_title, s.results.query),
                    message = stringResource(R.string.search_no_results_message),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SearchResultsList(
                    results = s.results,
                    onPlayChannel = { rememberQuery(); onPlayChannel(it) },
                    onOpenMovie = { rememberQuery(); onOpenMovie(it) },
                    onOpenSeries = { rememberQuery(); onOpenSeries(it) },
                )
            }
        }
    }
}

@Composable
private fun SearchIdle(recent: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (recent.isNotEmpty()) {
            Text(
                text = stringResource(R.string.search_recent),
                style = MaterialTheme.typography.titleMedium,
                color = NovaColors.TextSecondary,
                modifier = Modifier.padding(horizontal = NovaDimens.ScreenPadding, vertical = 8.dp),
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(horizontal = NovaDimens.ScreenPadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(recent, key = { it }) { term ->
                    SuggestionChip(onClick = { onPick(term) }) { Text(term) }
                }
            }
        }
        EmptyState(
            title = stringResource(R.string.search_hint_title),
            message = stringResource(R.string.search_hint_message),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun SearchResultsList(
    results: SearchResults,
    onPlayChannel: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val context = LocalContext.current
    // Results sit directly below the field, so D-pad DOWN from the field lands in the first rail.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(NovaDimens.RailSpacing),
    ) {
        if (results.channels.isNotEmpty()) {
            item(key = "channels", contentType = "rail") {
                ContentRail(title = stringResource(R.string.search_channels)) {
                    items(results.channels, key = { it.id }) { channel ->
                        ChannelCard(
                            name = channel.name,
                            logoUrl = channel.logoUrl,
                            number = channel.number,
                            nowTitle = results.nowTitles[channel.id],
                            onClick = { onPlayChannel(channel.id) },
                        )
                    }
                }
            }
        }
        if (results.programmes.isNotEmpty()) {
            item(key = "programmes", contentType = "rail") {
                val liveLabel = stringResource(R.string.search_live)
                ContentRail(title = stringResource(R.string.search_programmes)) {
                    items(results.programmes, key = { "${it.channel.id}@${it.program.startMillis}" }) { hit ->
                        val whenText = if (hit.isLive) liveLabel else formatStart(context, hit.program.startMillis, results.nowMillis)
                        LandscapeCard(
                            title = hit.program.title,
                            imageUrl = hit.program.iconUrl ?: hit.channel.logoUrl,
                            // Live and upcoming programmes both tune to their channel.
                            onClick = { onPlayChannel(hit.channel.id) },
                            subtitle = "$whenText · ${hit.channel.name}",
                            progress = hit.progress(results.nowMillis),
                        )
                    }
                }
            }
        }
        if (results.movies.isNotEmpty()) {
            item(key = "movies", contentType = "rail") {
                ContentRail(title = stringResource(R.string.search_movies)) {
                    items(results.movies, key = { it.id }) { movie ->
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
        if (results.series.isNotEmpty()) {
            item(key = "series", contentType = "rail") {
                ContentRail(title = stringResource(R.string.search_series)) {
                    items(results.series, key = { it.id }) { series ->
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
    }
}

/** "18:30" today, "Tue 18:30" on other days; follows the device 12/24 h setting. */
private fun formatStart(context: Context, startMillis: Long, nowMillis: Long): String {
    val time = DateFormat.getTimeFormat(context).format(Date(startMillis))
    if (DateUtils.isToday(startMillis) || startMillis - nowMillis < 0) return time
    val day = DateUtils.formatDateTime(context, startMillis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
    return "$day $time"
}
