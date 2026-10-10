package app.novaiptv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * Wraps a navigation destination:
 * - remembers the last focused child (focusRestorer saves it with the destination's saveable
 *   state, so it survives going to a detail/player screen and coming back);
 * - when the destination is shown and nothing inside it is focused (app start, back from a
 *   pushed screen), it moves focus into the content, retrying for a few seconds while data is
 *   still loading. It never steals focus from the top navigation bar ([isFocusElsewhere]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FocusScreen(
    modifier: Modifier = Modifier,
    isFocusElsewhere: () -> Boolean = { false },
    content: @Composable BoxScope.() -> Unit,
) {
    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val elsewhere by rememberUpdatedState(isFocusElsewhere)
    Box(
        modifier
            .fillMaxSize()
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusRequester(requester)
            .focusRestorer(),
        content = content,
    )
    LaunchedEffect(Unit) {
        for (attempt in 0 until 50) {
            if (hasFocus || elsewhere()) break
            runCatching { requester.requestFocus() }
            delay(100)
        }
    }
}
