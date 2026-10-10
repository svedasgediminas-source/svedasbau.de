package app.novaiptv.feature.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.NovaButton
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaListItem
import app.novaiptv.core.designsystem.NovaTextField
import app.novaiptv.core.domain.ChannelListItem
import app.novaiptv.ui.NovaDialog
import app.novaiptv.ui.RequestInitialFocus

/** MENU on a channel row: favourite toggle and "Fix TV guide". */
@Composable
internal fun ChannelOptionsDialog(
    channel: ChannelListItem,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onFixGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    NovaDialog(title = channel.name, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NovaListItem(
                headline = stringResource(if (isFavorite) R.string.common_favorite_remove else R.string.common_favorite_add),
                onClick = onToggleFavorite,
                modifier = Modifier.focusRequester(firstFocus),
            )
            NovaListItem(
                headline = stringResource(R.string.livetv_epgfix_action),
                supporting = stringResource(R.string.livetv_epgfix_action_hint),
                onClick = onFixGuide,
            )
        }
    }
    RequestInitialFocus(firstFocus)
}

/**
 * "Fix TV guide": search the XMLTV ids that have programmes stored, pick one and save it as the
 * channel's manual guide id, or clear the manual mapping.
 */
@Composable
internal fun EpgFixDialog(
    state: EpgFixState,
    onQueryChange: (String) -> Unit,
    onApply: (epgId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    val currentId = state.channel.epgId
    var selectedId by remember(state.channel.id) { mutableStateOf<String?>(null) }
    // Drop a selection that is no longer listed after a new search.
    val selected = selectedId?.takeIf { id -> state.suggestions.any { it.epgId == id } }

    NovaDialog(title = stringResource(R.string.livetv_epgfix_title, state.channel.name), onDismiss = onDismiss) {
        Text(
            text = if (currentId.isNullOrBlank()) {
                stringResource(R.string.livetv_epgfix_current_none)
            } else {
                stringResource(R.string.livetv_epgfix_current, currentId)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = NovaColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        NovaTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.livetv_epgfix_search_label),
            modifier = Modifier.focusRequester(fieldFocus),
            imeAction = ImeAction.Search,
        )
        Spacer(Modifier.height(12.dp))
        when {
            state.loading && state.suggestions.isEmpty() -> Text(
                text = stringResource(R.string.livetv_epgfix_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextMuted,
            )
            state.suggestions.isEmpty() -> Text(
                text = stringResource(R.string.livetv_epgfix_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextMuted,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.suggestions, key = { it.epgId }) { suggestion ->
                    val isSelected = suggestion.epgId == selected
                    NovaListItem(
                        headline = suggestion.epgId,
                        supporting = suggestion.sampleTitle,
                        trailing = (if (isSelected) "✓ " else "") + pluralStringResource(
                            R.plurals.livetv_epgfix_programmes,
                            suggestion.programmeCount,
                            suggestion.programmeCount,
                        ),
                        selected = isSelected || suggestion.epgId == currentId,
                        onClick = { selectedId = suggestion.epgId },
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        // Two rows: translated labels ("Clear manual mapping") are too long for one 560 dp row.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NovaButton(
                text = stringResource(R.string.livetv_epgfix_use),
                onClick = { selected?.let { onApply(it) } },
                primary = true,
                enabled = selected != null,
            )
            NovaButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
        }
        Spacer(Modifier.height(12.dp))
        NovaButton(text = stringResource(R.string.livetv_epgfix_clear), onClick = { onApply(null) })
    }
    RequestInitialFocus(fieldFocus)
}
