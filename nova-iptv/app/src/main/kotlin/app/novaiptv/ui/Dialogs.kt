package app.novaiptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.NovaButton
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaListItem
import app.novaiptv.core.designsystem.NovaTextField
import kotlinx.coroutines.delay

/** Dark rounded dialog container for TV. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NovaDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = NovaColors.Surface, contentColor = NovaColors.TextPrimary),
        ) {
            Column(Modifier.padding(32.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(20.dp))
                content()
            }
        }
    }
}

/** Requests focus on [requester] once the dialog window is attached. */
@Composable
fun RequestInitialFocus(requester: FocusRequester) {
    LaunchedEffect(requester) {
        repeat(10) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    NovaDialog(title = title, onDismiss = onDismiss) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = NovaColors.TextSecondary)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Cancel is focused first so an accidental OK never deletes anything.
            NovaButton(text = stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus))
            NovaButton(text = confirmLabel, onClick = onConfirm, primary = true)
        }
    }
    RequestInitialFocus(cancelFocus)
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    val fieldFocus = remember { FocusRequester() }
    NovaDialog(title = title, onDismiss = onDismiss) {
        NovaTextField(
            value = value,
            onValueChange = { value = it },
            label = label,
            modifier = Modifier.focusRequester(fieldFocus),
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NovaButton(text = stringResource(R.string.common_save), onClick = { onConfirm(value.trim()) }, primary = true)
            NovaButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
        }
    }
    RequestInitialFocus(fieldFocus)
}

/** Single-choice list; the current option is focused when the dialog opens. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedFocus = remember { FocusRequester() }
    val initialIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    NovaDialog(title = title, onDismiss = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            // Make sure the selected row is composed so it can receive focus.
            state = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = (initialIndex - 2).coerceAtLeast(0),
            ),
        ) {
            itemsIndexed(options) { index, label ->
                NovaListItem(
                    headline = label,
                    onClick = { onSelect(index) },
                    selected = index == selectedIndex,
                    trailing = if (index == selectedIndex) "✓" else null,
                    modifier = if (index == initialIndex) Modifier.focusRequester(selectedFocus) else Modifier,
                )
            }
        }
    }
    if (options.isNotEmpty()) RequestInitialFocus(selectedFocus)
}
