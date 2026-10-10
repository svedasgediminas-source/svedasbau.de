package app.novaiptv.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = NovaColors.TextPrimary,
        modifier = modifier,
    )
}

/**
 * A titled horizontal rail. Items line up with the overscan-safe screen padding and the row
 * remembers its last focused item, so moving up/down between rails returns to the same card.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContentRail(
    title: String,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(text = title, modifier = Modifier.padding(horizontal = NovaDimens.ScreenPadding))
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            // Vertical padding leaves room for the focused card's scale + border + glow.
            contentPadding = PaddingValues(horizontal = NovaDimens.ScreenPadding, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(NovaDimens.CardSpacing),
            content = content,
        )
    }
}
