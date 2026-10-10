package app.novaiptv.feature.playlists

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.NovaButton
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.designsystem.NovaTextField
import app.novaiptv.di.LocalAppContainer
import app.novaiptv.ui.ImportProgressBanner
import app.novaiptv.ui.errorMessage
import app.novaiptv.ui.importSummaryText

private val M3U_MIME_TYPES = arrayOf(
    "audio/x-mpegurl",
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "text/plain",
    "*/*",
)

@Composable
fun playlistTypeTitle(type: String): String = when (type) {
    PlaylistType.M3U_FILE -> stringResource(R.string.playlist_type_m3u_file)
    PlaylistType.XTREAM -> stringResource(R.string.playlist_type_xtream)
    else -> stringResource(R.string.playlist_type_m3u_url)
}

@Composable
fun AddPlaylistScreen(type: String, onDone: () -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: AddPlaylistViewModel = viewModel(key = "add_$type") { AddPlaylistViewModel(container, type) }
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    // Leaving the screen would cancel the import (it runs in viewModelScope) and discard the
    // half-imported playlist, so Back is held while an import is running.
    BackHandler(enabled = state.submitting) {}
    var pickerUnavailable by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // Keep read access across restarts so the file can be re-imported later.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.onFilePicked(uri.toString(), displayNameOf(context, uri))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NovaDimens.ScreenPadding, vertical = 32.dp),
    ) {
        Text(stringResource(R.string.playlist_add_title), style = MaterialTheme.typography.labelLarge, color = NovaColors.Accent)
        Spacer(Modifier.height(6.dp))
        Text(playlistTypeTitle(type), style = MaterialTheme.typography.headlineLarge, color = NovaColors.TextPrimary)
        Spacer(Modifier.height(24.dp))

        val result = state.result
        if (result != null) {
            Text(stringResource(R.string.playlist_added), style = MaterialTheme.typography.headlineSmall, color = NovaColors.Accent)
            Spacer(Modifier.height(8.dp))
            Text(importSummaryText(result), style = MaterialTheme.typography.bodyLarge, color = NovaColors.TextSecondary)
            if (result.skippedEntries > 0) {
                Text(
                    stringResource(R.string.playlist_skipped_entries, result.skippedEntries),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextMuted,
                )
            }
            Spacer(Modifier.height(24.dp))
            NovaButton(text = stringResource(R.string.common_ok), onClick = onDone, primary = true)
        } else {
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (type) {
                PlaylistType.M3U_FILE -> {
                    NovaButton(
                        text = state.fileDisplayName ?: stringResource(R.string.playlist_choose_file),
                        icon = Icons.Filled.FolderOpen,
                        onClick = {
                            try {
                                pickerUnavailable = false
                                picker.launch(M3U_MIME_TYPES)
                            } catch (e: ActivityNotFoundException) {
                                pickerUnavailable = true
                            }
                        },
                    )
                    if (pickerUnavailable) {
                        Text(stringResource(R.string.playlist_no_file_picker), style = MaterialTheme.typography.bodyMedium, color = NovaColors.Error)
                    }
                    NovaTextField(value = state.name, onValueChange = vm::onName, label = stringResource(R.string.playlist_field_name))
                    NovaTextField(
                        value = state.epgUrl, onValueChange = vm::onEpgUrl, label = stringResource(R.string.playlist_field_epg_url),
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, onImeAction = vm::submit,
                    )
                }
                PlaylistType.XTREAM -> {
                    NovaTextField(value = state.name, onValueChange = vm::onName, label = stringResource(R.string.playlist_field_name))
                    NovaTextField(
                        value = state.server, onValueChange = vm::onServer, label = stringResource(R.string.playlist_field_server),
                        keyboardType = KeyboardType.Uri,
                    )
                    NovaTextField(value = state.username, onValueChange = vm::onUsername, label = stringResource(R.string.playlist_field_username))
                    NovaTextField(
                        value = state.password, onValueChange = vm::onPassword, label = stringResource(R.string.playlist_field_password),
                        isPassword = true, imeAction = ImeAction.Done, onImeAction = vm::submit,
                    )
                }
                else -> {
                    NovaTextField(value = state.name, onValueChange = vm::onName, label = stringResource(R.string.playlist_field_name))
                    NovaTextField(
                        value = state.url, onValueChange = vm::onUrl, label = stringResource(R.string.playlist_field_url),
                        keyboardType = KeyboardType.Uri,
                    )
                    NovaTextField(
                        value = state.epgUrl, onValueChange = vm::onEpgUrl, label = stringResource(R.string.playlist_field_epg_url),
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, onImeAction = vm::submit,
                    )
                }
            }

            state.problem?.let { Text(problemText(it), style = MaterialTheme.typography.bodyLarge, color = NovaColors.Error) }
            state.error?.let { Text(errorMessage(it), style = MaterialTheme.typography.bodyLarge, color = NovaColors.Error) }
            if (state.submitting) ImportProgressBanner(progress)

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NovaButton(
                    text = stringResource(if (state.submitting) R.string.playlist_importing else R.string.playlist_add_button),
                    onClick = vm::submit,
                    primary = true,
                )
                NovaButton(text = stringResource(R.string.common_cancel), onClick = onBack)
            }
            Spacer(Modifier.height(12.dp))
            if (type == PlaylistType.XTREAM) {
                Text(stringResource(R.string.playlist_credentials_note), style = MaterialTheme.typography.bodySmall, color = NovaColors.TextMuted)
            }
            Text(stringResource(R.string.common_legal_note), style = MaterialTheme.typography.bodySmall, color = NovaColors.TextMuted)
        }
        }
    }
}

@Composable
private fun problemText(problem: FormProblem): String = stringResource(
    when (problem) {
        FormProblem.NameMissing -> R.string.playlist_problem_name
        FormProblem.UrlInvalid -> R.string.playlist_problem_url
        FormProblem.EpgUrlInvalid -> R.string.playlist_problem_epg_url
        FormProblem.FileMissing -> R.string.playlist_problem_file
        FormProblem.ServerInvalid -> R.string.playlist_problem_server
        FormProblem.UsernameMissing -> R.string.playlist_problem_username
        FormProblem.PasswordMissing -> R.string.playlist_problem_password
    },
)

private fun displayNameOf(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: "playlist.m3u"
}
