@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.novaiptv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.NovaButton
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.domain.Reminder
import app.novaiptv.core.domain.ReminderRules
import app.novaiptv.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** An ignored banner closes itself (and drops the reminder) after this long. */
private const val BANNER_AUTO_HIDE_MILLIS = 60_000L

/**
 * App-level programme reminder banner, placed once as an overlay by NovaNavHost.
 *
 * While the app is in the foreground (STARTED) it checks the stored reminders every
 * [ReminderRules.CHECK_INTERVAL_MILLIS]; once a reminded programme is due (from
 * [ReminderRules.LEAD_MILLIS] before its start) it shows "Starting now: <title> on <channel>" with
 * [Watch] and [Dismiss]. Either action, BACK, or ignoring it for a minute removes the reminder.
 *
 * The banner is a small popup window in the bottom-right corner: the screen underneath (and
 * playback) keeps running. It takes focus on [Watch] when it appears so the remote can act on it
 * directly. Background alarms / system notifications are out of scope: reminders only fire
 * while the app is open; a reminder whose programme is still running when the app is opened
 * fires then, ended ones are pruned.
 */
@Composable
fun ReminderBanner(onWatch: (channelId: String) -> Unit) {
    val container = LocalAppContainer.current
    val repo = container.reminders
    val clock = container.clock
    val reminders by remember(repo) { repo.observeReminders().catch { emit(emptyList()) } }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // Ticks only while the UI is at least STARTED (collectAsStateWithLifecycle stops the flow).
    val now by remember(clock) {
        flow {
            while (true) {
                emit(clock.nowMillis())
                delay(ReminderRules.CHECK_INTERVAL_MILLIS)
            }
        }
    }.collectAsStateWithLifecycle(initialValue = clock.nowMillis())

    // Keys handled in this session, so a failed/slow removal never shows the same banner twice.
    var handled by remember { mutableStateOf(emptySet<String>()) }
    val due = ReminderRules.nextDue(reminders.filterNot { it.key in handled }, now) ?: return

    val scope = rememberCoroutineScope()
    val close: (Reminder) -> Unit = { r ->
        handled = handled + r.key
        scope.launch {
            try {
                repo.remove(r.channelId, r.startMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Best effort: it is already hidden for this session and pruned once the programme ends.
            }
        }
    }

    key(due.key) {
        LaunchedEffect(Unit) {
            delay(BANNER_AUTO_HIDE_MILLIS)
            close(due)
        }
        Popup(
            alignment = Alignment.BottomEnd,
            offset = IntOffset(0, 0),
            onDismissRequest = { close(due) }, // BACK
            properties = PopupProperties(focusable = true),
        ) {
            ReminderBannerContent(
                reminder = due,
                onWatch = {
                    close(due)
                    onWatch(due.channelId)
                },
                onDismiss = { close(due) },
            )
        }
    }
}

@Composable
private fun ReminderBannerContent(reminder: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val watchFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier
            .padding(end = 32.dp, bottom = 32.dp)
            .widthIn(max = 520.dp)
            .border(BorderStroke(1.dp, NovaColors.Accent), shape),
        shape = shape,
        colors = SurfaceDefaults.colors(containerColor = NovaColors.SurfaceRaised, contentColor = NovaColors.TextPrimary),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = NovaColors.Favorite,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        R.string.reminder_banner_text,
                        reminder.title,
                        reminder.channelName.ifBlank { reminder.channelId },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NovaButton(
                    text = stringResource(R.string.reminder_watch),
                    onClick = onWatch,
                    primary = true,
                    modifier = Modifier.focusRequester(watchFocus),
                )
                NovaButton(text = stringResource(R.string.reminder_dismiss), onClick = onDismiss)
            }
        }
    }
    RequestInitialFocus(watchFocus)
}
