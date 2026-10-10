package app.novaiptv.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.novaiptv.R
import app.novaiptv.core.designsystem.LocalNovaStrings
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaStrings
import app.novaiptv.feature.favorites.FavoritesScreen
import app.novaiptv.feature.guide.GuideScreen
import app.novaiptv.feature.home.HomeScreen
import app.novaiptv.feature.livetv.LiveTvScreen
import app.novaiptv.feature.movies.MovieDetailScreen
import app.novaiptv.feature.movies.MoviesScreen
import app.novaiptv.feature.player.PlayerArgs
import app.novaiptv.feature.player.PlayerScreen
import app.novaiptv.feature.playlists.AddPlaylistScreen
import app.novaiptv.feature.playlists.PlaylistsScreen
import app.novaiptv.feature.search.SearchScreen
import app.novaiptv.feature.series.SeriesDetailScreen
import app.novaiptv.feature.series.SeriesScreen
import app.novaiptv.feature.settings.SettingsScreen
import app.novaiptv.ui.FocusScreen

private fun NavDestination?.topLevelIndex(): Int {
    val dest = this ?: return -1
    return TopNavItems.indexOfFirst { item -> dest.hasRoute(item.route::class) }
}

/** Switches tab: one copy of each tab on top of Home, tab state saved/restored. */
private fun NavHostController.navigateTopLevel(route: Route) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Pops only if this destination is still the current one (ignores double BACK/OK presses). */
private fun NavHostController.popFrom(destinationId: Int) {
    if (currentDestination?.id == destinationId) popBackStack()
}

/**
 * App navigation. Top-level tabs show [TopNavBar]; detail and settings sub-screens are pushed
 * full-width without the bar; player routes are full-screen.
 *
 * BACK: on a tab other than Home the tab entry is popped, landing on Home (tabs are always
 * stacked directly on Home). On Home, BACK calls [onExit].
 */
@Composable
fun NovaNavHost(onExit: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = destination.topLevelIndex()
    val showBar = destination == null || currentTab >= 0

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(currentTab) { if (currentTab >= 0) selectedTab = currentTab }
    var barHasFocus by remember { mutableStateOf(false) }
    val barFocused: () -> Boolean = { barHasFocus }
    LaunchedEffect(showBar) { if (!showBar) barHasFocus = false }

    val strings = NovaStrings(
        retry = stringResource(R.string.common_retry),
        loading = stringResource(R.string.common_loading),
    )

    CompositionLocalProvider(LocalNovaStrings provides strings) {
        Column(Modifier.fillMaxSize().background(NovaColors.Background)) {
            if (showBar) {
                TopNavBar(
                    selectedIndex = selectedTab,
                    onSelect = { index ->
                        selectedTab = index
                        navController.navigateTopLevel(TopNavItems[index].route)
                    },
                    modifier = Modifier.onFocusChanged { barHasFocus = it.hasFocus },
                )
            }
            NavHost(
                navController = navController,
                startDestination = Route.Home,
                modifier = Modifier.fillMaxWidth().weight(1f),
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(120)) },
            ) {
                composable<Route.Home> {
                    BackHandler { onExit() }
                    FocusScreen(isFocusElsewhere = barFocused) {
                        HomeScreen(
                            onPlayChannel = { id -> navController.navigate(Route.PlayChannel(id)) },
                            onPlayMovie = { id -> navController.navigate(Route.PlayMovie(id)) },
                            onPlayEpisode = { id -> navController.navigate(Route.PlayEpisode(id)) },
                            onOpenMovie = { id -> navController.navigate(Route.MovieDetail(id)) },
                            onOpenSeries = { id -> navController.navigate(Route.SeriesDetail(id)) },
                            onAddPlaylist = { type -> navController.navigate(Route.AddPlaylist(type)) },
                            onOpenSettings = { navController.navigateTopLevel(Route.Settings) },
                            onOpenGuide = { navController.navigateTopLevel(Route.Guide) },
                        )
                    }
                }
                composable<Route.LiveTv> {
                    FocusScreen(isFocusElsewhere = barFocused) {
                        LiveTvScreen(onPlayChannel = { id, group -> navController.navigate(Route.PlayChannel(id, group)) })
                    }
                }
                composable<Route.Guide> {
                    FocusScreen(isFocusElsewhere = barFocused) {
                        GuideScreen(
                            onPlayChannel = { id -> navController.navigate(Route.PlayChannel(id)) },
                            onPlayCatchup = { id, start, end -> navController.navigate(Route.PlayCatchup(id, start, end)) },
                        )
                    }
                }
                composable<Route.Movies> {
                    FocusScreen(isFocusElsewhere = barFocused) {
                        MoviesScreen(onOpenMovie = { id -> navController.navigate(Route.MovieDetail(id)) })
                    }
                }
                composable<Route.Series> {
                    FocusScreen(isFocusElsewhere = barFocused) {
                        SeriesScreen(onOpenSeries = { id -> navController.navigate(Route.SeriesDetail(id)) })
                    }
                }
                composable<Route.Favorites> {
                    FocusScreen(isFocusElsewhere = barFocused) {
                        FavoritesScreen(
                            onPlayChannel = { id -> navController.navigate(Route.PlayChannel(id)) },
                            onOpenMovie = { id -> navController.navigate(Route.MovieDetail(id)) },
                            onOpenSeries = { id -> navController.navigate(Route.SeriesDetail(id)) },
                        )
                    }
                }
                composable<Route.Search> {
                    FocusScreen(isFocusElsewhere = barFocused) {
                        SearchScreen(
                            onPlayChannel = { id -> navController.navigate(Route.PlayChannel(id)) },
                            onOpenMovie = { id -> navController.navigate(Route.MovieDetail(id)) },
                            onOpenSeries = { id -> navController.navigate(Route.SeriesDetail(id)) },
                        )
                    }
                }
                composable<Route.Settings> {
                    FocusScreen(isFocusElsewhere = barFocused) {
                        SettingsScreen(
                            onOpenPlaylists = { navController.navigate(Route.Playlists) },
                            onAddPlaylist = { type -> navController.navigate(Route.AddPlaylist(type)) },
                        )
                    }
                }
                composable<Route.Playlists> { entry ->
                    val id = entry.destination.id
                    FocusScreen {
                        PlaylistsScreen(
                            onBack = { navController.popFrom(id) },
                            onAddPlaylist = { type -> navController.navigate(Route.AddPlaylist(type)) },
                        )
                    }
                }
                composable<Route.AddPlaylist> { entry ->
                    val route = entry.toRoute<Route.AddPlaylist>()
                    val id = entry.destination.id
                    FocusScreen {
                        AddPlaylistScreen(
                            type = route.type,
                            onDone = { navController.popFrom(id) },
                            onBack = { navController.popFrom(id) },
                        )
                    }
                }
                composable<Route.MovieDetail> { entry ->
                    val route = entry.toRoute<Route.MovieDetail>()
                    val id = entry.destination.id
                    FocusScreen {
                        MovieDetailScreen(
                            movieId = route.movieId,
                            onPlay = { movieId -> navController.navigate(Route.PlayMovie(movieId)) },
                            onBack = { navController.popFrom(id) },
                        )
                    }
                }
                composable<Route.SeriesDetail> { entry ->
                    val route = entry.toRoute<Route.SeriesDetail>()
                    val id = entry.destination.id
                    FocusScreen {
                        SeriesDetailScreen(
                            seriesId = route.seriesId,
                            onPlayEpisode = { episodeId -> navController.navigate(Route.PlayEpisode(episodeId)) },
                            onBack = { navController.popFrom(id) },
                        )
                    }
                }
                composable<Route.PlayChannel> { entry ->
                    val route = entry.toRoute<Route.PlayChannel>()
                    val id = entry.destination.id
                    PlayerScreen(args = PlayerArgs.Channel(route.channelId, route.group), onBack = { navController.popFrom(id) })
                }
                composable<Route.PlayCatchup> { entry ->
                    val route = entry.toRoute<Route.PlayCatchup>()
                    val id = entry.destination.id
                    PlayerScreen(
                        args = PlayerArgs.Catchup(route.channelId, route.startMillis, route.endMillis),
                        onBack = { navController.popFrom(id) },
                    )
                }
                composable<Route.PlayMovie> { entry ->
                    val route = entry.toRoute<Route.PlayMovie>()
                    val id = entry.destination.id
                    PlayerScreen(args = PlayerArgs.Movie(route.movieId), onBack = { navController.popFrom(id) })
                }
                composable<Route.PlayEpisode> { entry ->
                    val route = entry.toRoute<Route.PlayEpisode>()
                    val id = entry.destination.id
                    PlayerScreen(args = PlayerArgs.Episode(route.episodeId), onBack = { navController.popFrom(id) })
                }
            }
        }
    }
}
