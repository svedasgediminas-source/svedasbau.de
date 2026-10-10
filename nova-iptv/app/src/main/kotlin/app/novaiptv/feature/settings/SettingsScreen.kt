package app.novaiptv.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaDimens
import app.novaiptv.core.designsystem.NovaListItem
import app.novaiptv.core.designsystem.SectionHeader
import app.novaiptv.core.model.AppLanguage
import app.novaiptv.core.model.AspectRatioMode
import app.novaiptv.core.model.UserPreferences
import app.novaiptv.di.LocalAppContainer
import app.novaiptv.feature.playlists.PlaylistType
import app.novaiptv.feature.playlists.playlistSourceLabel
import app.novaiptv.feature.playlists.playlistTypeTitle
import app.novaiptv.ui.ChoiceDialog
import app.novaiptv.ui.ConfirmDialog
import app.novaiptv.ui.ImportProgressBanner
import app.novaiptv.ui.errorMessage

enum class SettingsCategory(val titleRes: Int, val icon: ImageVector) {
    Playlists(R.string.settings_cat_playlists, Icons.Filled.VideoLibrary),
    AddPlaylist(R.string.settings_cat_add_playlist, Icons.Filled.Add),
    Epg(R.string.settings_cat_epg, Icons.Filled.Schedule),
    Player(R.string.settings_cat_player, Icons.Filled.PlayArrow),
    Language(R.string.settings_cat_language, Icons.Filled.Language),
    Data(R.string.settings_cat_data, Icons.Filled.Storage),
    Parental(R.string.settings_cat_parental, Icons.Filled.Lock),
    Privacy(R.string.settings_cat_privacy, Icons.Filled.Shield),
    About(R.string.settings_cat_about, Icons.Filled.Info),
}

private enum class SettingsDialog { TimeShift, Aspect, Audio, SubtitleSize, ClearHistory }

/** Audio language choices: null = automatic (stream default). Labels are endonyms. */
private val AudioLanguages: List<Pair<String?, String>> = listOf(
    null to "",
    "en" to "English",
    "lt" to "Lietuvių",
    "de" to "Deutsch",
    "ru" to "Русский",
    "uk" to "Українська",
    "pl" to "Polski",
    "lv" to "Latviešu",
    "et" to "Eesti",
    "fr" to "Français",
    "es" to "Español",
    "it" to "Italiano",
)

private val SubtitleScales = listOf(0.75f, 1f, 1.25f, 1.5f)
private val TimeShiftHours = (-12..12).toList()

private val LanguageOptions: List<Pair<AppLanguage, String>> = listOf(
    AppLanguage.System to "",
    AppLanguage.English to "English",
    AppLanguage.Lithuanian to "Lietuvių",
    AppLanguage.German to "Deutsch",
    AppLanguage.Russian to "Русский",
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(onOpenPlaylists: () -> Unit, onAddPlaylist: (type: String) -> Unit) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var categoryName by rememberSaveable { mutableStateOf(SettingsCategory.Playlists.name) }
    val category = SettingsCategory.valueOf(categoryName)
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val detailFocus = remember { FocusRequester() }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = NovaDimens.ScreenPadding, end = NovaDimens.ScreenPadding, top = 16.dp),
    ) {
        LazyColumn(
            modifier = Modifier.width(300.dp).fillMaxHeight().focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(SettingsCategory.entries.toList(), key = { it.name }) { cat ->
                NovaListItem(
                    headline = stringResource(cat.titleRes),
                    icon = cat.icon,
                    selected = cat == category,
                    onClick = {
                        categoryName = cat.name
                        runCatching { detailFocus.requestFocus() }
                    },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) categoryName = cat.name },
                )
            }
        }
        Spacer(Modifier.width(40.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(detailFocus)
                .focusRestorer(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "header_${category.name}") {
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        SectionHeader(stringResource(category.titleRes))
                        Spacer(Modifier.height(8.dp))
                        ImportProgressBanner(progress)
                        SettingsMessageText(message, busy)
                    }
                }
                when (category) {
                    SettingsCategory.Playlists -> playlistsSection(
                        playlists = playlists,
                        activeId = prefs.activePlaylistId ?: playlists.firstOrNull()?.id,
                        onOpenPlaylists = onOpenPlaylists,
                    )
                    SettingsCategory.AddPlaylist -> addPlaylistSection(onAddPlaylist)
                    SettingsCategory.Epg -> epgSection(
                        prefs = prefs,
                        onRefresh = vm::refreshEpg,
                        onTimeShift = { dialog = SettingsDialog.TimeShift },
                        onOpenPlaylists = onOpenPlaylists,
                    )
                    SettingsCategory.Player -> playerSection(
                        prefs = prefs,
                        onAspect = { dialog = SettingsDialog.Aspect },
                        onAudio = { dialog = SettingsDialog.Audio },
                        onSubtitleSize = { dialog = SettingsDialog.SubtitleSize },
                        onUpdate = vm::update,
                    )
                    SettingsCategory.Language -> languageSection(prefs.language, vm::setLanguage)
                    SettingsCategory.Data -> dataSection(
                        prefs = prefs,
                        onRefreshAll = vm::refreshAllPlaylists,
                        onClearHistory = { dialog = SettingsDialog.ClearHistory },
                        onUpdate = vm::update,
                    )
                    SettingsCategory.Parental -> parentalSection(prefs, vm::update)
                    SettingsCategory.Privacy -> textSection(
                        listOf(R.string.settings_privacy_1, R.string.settings_privacy_2, R.string.settings_privacy_3, R.string.settings_privacy_4),
                    )
                    SettingsCategory.About -> aboutSection()
                }
            }
        }
    }

    when (dialog) {
        SettingsDialog.TimeShift -> ChoiceDialog(
            title = stringResource(R.string.settings_epg_time_shift),
            options = TimeShiftHours.map { stringResource(R.string.settings_time_shift_value, it) },
            selectedIndex = TimeShiftHours.indexOf(prefs.epgTimeShiftMinutes / 60).coerceAtLeast(0),
            onSelect = { i -> dialog = null; vm.update { it.copy(epgTimeShiftMinutes = TimeShiftHours[i] * 60) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Aspect -> ChoiceDialog(
            title = stringResource(R.string.settings_player_aspect),
            options = AspectRatioMode.entries.map { aspectLabel(it) },
            selectedIndex = AspectRatioMode.entries.indexOf(prefs.aspectRatio),
            onSelect = { i -> dialog = null; vm.update { it.copy(aspectRatio = AspectRatioMode.entries[i]) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Audio -> ChoiceDialog(
            title = stringResource(R.string.settings_player_audio_language),
            options = AudioLanguages.map { (code, label) -> if (code == null) stringResource(R.string.settings_auto) else label },
            selectedIndex = AudioLanguages.indexOfFirst { it.first == prefs.preferredAudioLanguage }.coerceAtLeast(0),
            onSelect = { i -> dialog = null; vm.update { it.copy(preferredAudioLanguage = AudioLanguages[i].first) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.SubtitleSize -> ChoiceDialog(
            title = stringResource(R.string.settings_player_subtitle_size),
            options = SubtitleScales.map { subtitleScaleLabel(it) },
            selectedIndex = SubtitleScales.indexOfFirst { kotlin.math.abs(it - prefs.subtitleScale) < 0.01f }.coerceAtLeast(0),
            onSelect = { i -> dialog = null; vm.update { it.copy(subtitleScale = SubtitleScales[i]) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.ClearHistory -> ConfirmDialog(
            title = stringResource(R.string.settings_data_clear_history),
            message = stringResource(R.string.settings_data_clear_history_confirm),
            confirmLabel = stringResource(R.string.settings_data_clear),
            onConfirm = { dialog = null; vm.clearHistory() },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun SettingsMessageText(message: SettingsMessage?, busy: Boolean) {
    val text: String? = when (message) {
        null -> if (busy) stringResource(R.string.common_loading) else null
        is SettingsMessage.EpgRefreshed -> stringResource(R.string.settings_epg_refreshed, message.programs)
        is SettingsMessage.PlaylistsRefreshed -> stringResource(R.string.settings_data_refreshed, message.count)
        SettingsMessage.HistoryCleared -> stringResource(R.string.settings_data_history_cleared)
        SettingsMessage.NothingToRefresh -> stringResource(R.string.playlist_none_title)
        is SettingsMessage.Failed -> errorMessage(message.error)
    }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (message is SettingsMessage.Failed) NovaColors.Error else NovaColors.Accent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun onOff(value: Boolean): String = stringResource(if (value) R.string.common_on else R.string.common_off)

@Composable
private fun aspectLabel(mode: AspectRatioMode): String = stringResource(
    when (mode) {
        AspectRatioMode.Fit -> R.string.settings_aspect_fit
        AspectRatioMode.Fill -> R.string.settings_aspect_fill
        AspectRatioMode.Zoom -> R.string.settings_aspect_zoom
        AspectRatioMode.Stretch -> R.string.settings_aspect_stretch
    },
)

@Composable
private fun subtitleScaleLabel(scale: Float): String = stringResource(
    when {
        scale < 0.9f -> R.string.settings_subtitle_small
        scale < 1.1f -> R.string.settings_subtitle_normal
        scale < 1.4f -> R.string.settings_subtitle_large
        else -> R.string.settings_subtitle_xlarge
    },
)

private fun LazyListScope.playlistsSection(
    playlists: List<app.novaiptv.core.model.Playlist>,
    activeId: Long?,
    onOpenPlaylists: () -> Unit,
) {
    item(key = "pl_manage") {
        NovaListItem(
            headline = stringResource(R.string.settings_playlists_manage),
            supporting = stringResource(R.string.settings_playlists_manage_desc),
            onClick = onOpenPlaylists,
        )
    }
    items(playlists, key = { "pl_${it.id}" }) { playlist ->
        NovaListItem(
            headline = playlist.name,
            supporting = playlist.lastError?.takeIf { it.isNotBlank() } ?: playlistSourceLabel(playlist.source),
            trailing = if (playlist.id == activeId) stringResource(R.string.playlist_active) else null,
            onClick = onOpenPlaylists,
        )
    }
}

private fun LazyListScope.addPlaylistSection(onAddPlaylist: (String) -> Unit) {
    items(PlaylistType.all, key = { "add_$it" }) { type ->
        NovaListItem(
            headline = playlistTypeTitle(type),
            supporting = stringResource(
                when (type) {
                    PlaylistType.M3U_FILE -> R.string.playlist_type_m3u_file_desc
                    PlaylistType.XTREAM -> R.string.playlist_type_xtream_desc
                    else -> R.string.playlist_type_m3u_url_desc
                },
            ),
            onClick = { onAddPlaylist(type) },
        )
    }
    item(key = "add_legal") { NoteText(stringResource(R.string.common_legal_note)) }
}

private fun LazyListScope.epgSection(
    prefs: UserPreferences,
    onRefresh: () -> Unit,
    onTimeShift: () -> Unit,
    onOpenPlaylists: () -> Unit,
) {
    item(key = "epg_refresh") {
        NovaListItem(headline = stringResource(R.string.settings_epg_refresh), onClick = onRefresh)
    }
    item(key = "epg_shift") {
        NovaListItem(
            headline = stringResource(R.string.settings_epg_time_shift),
            supporting = stringResource(R.string.settings_epg_time_shift_desc),
            trailing = stringResource(R.string.settings_time_shift_value, prefs.epgTimeShiftMinutes / 60),
            onClick = onTimeShift,
        )
    }
    item(key = "epg_sources") {
        NovaListItem(
            headline = stringResource(R.string.playlist_epg_sources),
            supporting = stringResource(R.string.settings_epg_sources_desc),
            onClick = onOpenPlaylists,
        )
    }
}

private fun LazyListScope.playerSection(
    prefs: UserPreferences,
    onAspect: () -> Unit,
    onAudio: () -> Unit,
    onSubtitleSize: () -> Unit,
    onUpdate: ((UserPreferences) -> UserPreferences) -> Unit,
) {
    item(key = "pl_aspect") {
        NovaListItem(headline = stringResource(R.string.settings_player_aspect), trailing = aspectLabel(prefs.aspectRatio), onClick = onAspect)
    }
    item(key = "pl_audio") {
        val code = prefs.preferredAudioLanguage
        NovaListItem(
            headline = stringResource(R.string.settings_player_audio_language),
            trailing = AudioLanguages.firstOrNull { it.first == code && code != null }?.second ?: stringResource(R.string.settings_auto),
            onClick = onAudio,
        )
    }
    item(key = "pl_subs") {
        NovaListItem(
            headline = stringResource(R.string.settings_player_subtitles),
            trailing = onOff(prefs.subtitlesEnabled),
            onClick = { onUpdate { it.copy(subtitlesEnabled = !it.subtitlesEnabled) } },
        )
    }
    item(key = "pl_subsize") {
        NovaListItem(
            headline = stringResource(R.string.settings_player_subtitle_size),
            trailing = subtitleScaleLabel(prefs.subtitleScale),
            onClick = onSubtitleSize,
        )
    }
    item(key = "pl_numbers") {
        NovaListItem(
            headline = stringResource(R.string.settings_player_channel_numbers),
            trailing = onOff(prefs.showChannelNumbers),
            onClick = { onUpdate { it.copy(showChannelNumbers = !it.showChannelNumbers) } },
        )
    }
}

private fun LazyListScope.languageSection(current: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    items(LanguageOptions, key = { "lang_${it.first.name}" }) { (language, label) ->
        NovaListItem(
            headline = if (language == AppLanguage.System) stringResource(R.string.settings_language_system) else label,
            selected = language == current,
            trailing = if (language == current) "✓" else null,
            onClick = { onSelect(language) },
        )
    }
}

private fun LazyListScope.dataSection(
    prefs: UserPreferences,
    onRefreshAll: () -> Unit,
    onClearHistory: () -> Unit,
    onUpdate: ((UserPreferences) -> UserPreferences) -> Unit,
) {
    item(key = "data_refresh") {
        NovaListItem(headline = stringResource(R.string.settings_data_refresh_all), onClick = onRefreshAll)
    }
    item(key = "data_auto") {
        NovaListItem(
            headline = stringResource(R.string.settings_data_auto_refresh),
            supporting = stringResource(R.string.settings_data_auto_refresh_desc),
            trailing = onOff(prefs.autoRefreshOnStart),
            onClick = { onUpdate { it.copy(autoRefreshOnStart = !it.autoRefreshOnStart) } },
        )
    }
    item(key = "data_history") {
        NovaListItem(headline = stringResource(R.string.settings_data_clear_history), onClick = onClearHistory)
    }
}

private fun LazyListScope.parentalSection(prefs: UserPreferences, onUpdate: ((UserPreferences) -> UserPreferences) -> Unit) {
    item(key = "par_adult") {
        NovaListItem(
            headline = stringResource(R.string.settings_parental_hide_adult),
            supporting = stringResource(R.string.settings_parental_hide_adult_desc),
            trailing = onOff(prefs.hideAdultContent),
            onClick = { onUpdate { it.copy(hideAdultContent = !it.hideAdultContent) } },
        )
    }
}

private fun LazyListScope.textSection(paragraphs: List<Int>) {
    items(paragraphs, key = { "txt_$it" }) { res -> NoteText(stringResource(res)) }
}

private fun LazyListScope.aboutSection() {
    item(key = "about_version") {
        val context = LocalContext.current
        NovaListItem(
            headline = stringResource(R.string.app_name),
            supporting = stringResource(R.string.settings_about_version, appVersion(context)),
            onClick = {},
        )
    }
    item(key = "about_licenses") { NoteText(stringResource(R.string.settings_about_licenses)) }
    item(key = "about_legal") { NoteText(stringResource(R.string.common_legal_note)) }
    item(key = "about_legal2") { NoteText(stringResource(R.string.settings_about_legal)) }
}

@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = NovaColors.TextSecondary,
        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

@Suppress("DEPRECATION")
private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
