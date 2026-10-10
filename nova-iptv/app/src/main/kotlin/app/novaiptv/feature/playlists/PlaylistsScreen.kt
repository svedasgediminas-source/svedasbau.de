package app.novaiptv.feature.playlists

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.common.Redact
import app.novaiptv.core.designsystem.EmptyState
import app.novaiptv.core.designsystem.LoadingState
import app.novaiptv.core.designsystem.NovaButton
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.designsystem.NovaListItem
import app.novaiptv.core.model.Playlist
import app.novaiptv.core.model.PlaylistSource
import app.novaiptv.di.LocalAppContainer
import app.novaiptv.ui.ChoiceDialog
import app.novaiptv.ui.ConfirmDialog
import app.novaiptv.ui.ImportProgressBanner
import app.novaiptv.ui.TextInputDialog
import app.novaiptv.ui.errorMessage
import app.novaiptv.ui.playlistErrorMessage
import app.novaiptv.ui.importSummaryText

private enum class PlaylistDialog { AddType, Rename, Epg, Delete }

@Composable
fun playlistSourceLabel(source: PlaylistSource): String = when (source) {
    is PlaylistSource.M3uUrl -> stringResource(R.string.playlist_type_m3u_url)
    is PlaylistSource.M3uFile -> stringResource(R.string.playlist_type_m3u_file)
    is PlaylistSource.Xtream -> stringResource(R.string.playlist_type_xtream)
}

/** Where the playlist comes from, without credentials. */
fun playlistSourceDetail(source: PlaylistSource): String = when (source) {
    is PlaylistSource.M3uUrl -> Redact.url(source.url)
    is PlaylistSource.M3uFile -> source.displayName
    is PlaylistSource.Xtream -> Redact.url(source.serverUrl)
}

@Composable
fun PlaylistMessageBanner(message: PlaylistMessage?) {
    val (text, isError) = when (message) {
        null -> return
        is PlaylistMessage.Refreshed -> importSummaryText(message.summary) to false
        is PlaylistMessage.Failed -> errorMessage(message.error) to true
        PlaylistMessage.EpgUrlInvalid -> stringResource(R.string.playlist_problem_epg_url) to true
        PlaylistMessage.Deleted -> stringResource(R.string.playlist_deleted) to false
        PlaylistMessage.Saved -> stringResource(R.string.common_saved) to false
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = if (isError) NovaColors.Error else NovaColors.Accent)
}

@Composable
fun PlaylistsScreen(onBack: () -> Unit, onAddPlaylist: (type: String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: PlaylistsViewModel = viewModel { PlaylistsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = NovaDimens.ScreenPadding, vertical = 32.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.playlist_manage_title), style = MaterialTheme.typography.headlineLarge, color = NovaColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            NovaButton(
                text = stringResource(R.string.playlist_add_title),
                icon = Icons.Filled.Add,
                onClick = { dialog = PlaylistDialog.AddType },
            )
        }
        Spacer(Modifier.height(16.dp))
        ImportProgressBanner(progress)
        PlaylistMessageBanner(message)
        Spacer(Modifier.height(16.dp))

        when {
            state.loading -> LoadingState()
            state.playlists.isEmpty() -> EmptyState(
                title = stringResource(R.string.playlist_none_title),
                message = stringResource(R.string.common_legal_note),
                actionLabel = stringResource(R.string.playlist_add_title),
                onAction = { dialog = PlaylistDialog.AddType },
            )
            else -> {
                val selected = state.playlists.firstOrNull { it.id == selectedId } ?: state.playlists.first()
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.width(380.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.playlists, key = { it.id }) { playlist ->
                            NovaListItem(
                                headline = playlist.name,
                                supporting = playlistSourceLabel(playlist.source),
                                trailing = if (playlist.id == state.activePlaylistId) stringResource(R.string.playlist_active) else null,
                                selected = playlist.id == selected.id,
                                onClick = { selectedId = playlist.id },
                                modifier = Modifier.onFocusChanged { if (it.isFocused) selectedId = playlist.id },
                            )
                        }
                    }
                    Spacer(Modifier.width(40.dp))
                    PlaylistDetails(
                        playlist = selected,
                        isActive = selected.id == state.activePlaylistId,
                        busy = busy,
                        onSetActive = { vm.setActive(selected.id) },
                        onRefresh = { vm.dismissMessage(); vm.refresh(selected.id) },
                        onRename = { dialog = PlaylistDialog.Rename },
                        onEpg = { dialog = PlaylistDialog.Epg },
                        onDelete = { dialog = PlaylistDialog.Delete },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    val selected = state.playlists.firstOrNull { it.id == selectedId } ?: state.playlists.firstOrNull()
    when (dialog) {
        PlaylistDialog.AddType -> {
            val types = PlaylistType.all
            ChoiceDialog(
                title = stringResource(R.string.playlist_add_title),
                options = types.map { playlistTypeTitle(it) },
                selectedIndex = 0,
                onSelect = { index -> dialog = null; onAddPlaylist(types[index]) },
                onDismiss = { dialog = null },
            )
        }
        PlaylistDialog.Rename -> if (selected != null) {
            TextInputDialog(
                title = stringResource(R.string.playlist_rename),
                label = stringResource(R.string.playlist_field_name),
                initialValue = selected.name,
                onConfirm = { name -> dialog = null; vm.rename(selected.id, name) },
                onDismiss = { dialog = null },
            )
        }
        PlaylistDialog.Epg -> if (selected != null) {
            TextInputDialog(
                title = stringResource(R.string.playlist_epg_sources),
                label = stringResource(R.string.playlist_field_epg_url),
                initialValue = selected.epgUrls.joinToString(", "),
                onConfirm = { raw -> dialog = null; vm.setEpgUrls(selected.id, raw) },
                onDismiss = { dialog = null },
            )
        }
        PlaylistDialog.Delete -> if (selected != null) {
            ConfirmDialog(
                title = stringResource(R.string.playlist_delete_title),
                message = stringResource(R.string.playlist_delete_message, selected.name),
                confirmLabel = stringResource(R.string.playlist_delete),
                onConfirm = { dialog = null; vm.delete(selected.id); selectedId = null },
                onDismiss = { dialog = null },
            )
        }
        null -> Unit
    }
}

@Composable
private fun PlaylistDetails(
    playlist: Playlist,
    isActive: Boolean,
    busy: Boolean,
    onSetActive: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onEpg: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.headlineSmall, color = NovaColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${playlistSourceLabel(playlist.source)} · ${playlistSourceDetail(playlist.source)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    maxLines = 2,
                )
                val refreshed = playlist.lastRefreshedAtMillis
                Text(
                    if (refreshed != null) {
                        stringResource(
                            R.string.playlist_last_refreshed,
                            DateUtils.formatDateTime(
                                context, refreshed,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                            ),
                        )
                    } else {
                        stringResource(R.string.playlist_never_refreshed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextMuted,
                )
                if (busy) {
                    Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium, color = NovaColors.Accent)
                }
                if (!playlist.lastError.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.playlist_last_error, playlistErrorMessage(playlist.lastError.orEmpty())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NovaColors.Error,
                        maxLines = 2,
                    )
                }
            }
        }
        if (!isActive) {
            item {
                NovaListItem(headline = stringResource(R.string.playlist_set_active), icon = Icons.Filled.CheckCircle, onClick = onSetActive)
            }
        }
        item {
            NovaListItem(headline = stringResource(R.string.playlist_refresh), icon = Icons.Filled.Refresh, onClick = onRefresh)
        }
        item {
            NovaListItem(headline = stringResource(R.string.playlist_rename), icon = Icons.Filled.Edit, onClick = onRename)
        }
        item {
            NovaListItem(
                headline = stringResource(R.string.playlist_epg_sources),
                supporting = playlist.epgUrls.joinToString(", ") { Redact.url(it) }.ifBlank { stringResource(R.string.playlist_epg_none) },
                icon = Icons.Filled.Schedule,
                onClick = onEpg,
            )
        }
        item {
            NovaListItem(headline = stringResource(R.string.playlist_delete), icon = Icons.Filled.Delete, onClick = onDelete)
        }
    }
}
