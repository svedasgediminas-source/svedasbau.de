package app.novaiptv.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * D-pad friendly text field. The outer surface is what receives focus while navigating, so
 * moving across a form does not pop the keyboard up. Pressing OK enters edit mode: focus moves
 * into the inner text field and the IME is shown. The IME action (Next/Done/Go/Search) or
 * moving focus away leaves edit mode.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NovaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var innerFocused by remember { mutableStateOf(false) }
    val outer = remember { FocusRequester() }
    val inner = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(editing) {
        if (editing) {
            runCatching { inner.requestFocus() }
            keyboard?.show()
        }
    }

    Surface(
        onClick = { editing = true },
        modifier = modifier.fillMaxWidth().focusRequester(outer),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NovaColors.SurfaceRaised,
            contentColor = NovaColors.TextPrimary,
            focusedContainerColor = NovaColors.SurfaceBright,
            focusedContentColor = NovaColors.TextPrimary,
            pressedContainerColor = NovaColors.SurfaceBright,
            pressedContentColor = NovaColors.TextPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, if (innerFocused) NovaColors.Accent else NovaColors.SurfaceBright), shape = shape),
            focusedBorder = Border(border = BorderStroke(NovaDimens.FocusBorder, NovaColors.Accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = NovaColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = NovaColors.TextPrimary),
                cursorBrush = SolidColor(NovaColors.Accent),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = {
                    editing = false
                    keyboard?.hide()
                    runCatching { outer.requestFocus() }
                    onImeAction?.invoke()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inner)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged {
                        innerFocused = it.isFocused
                        if (!it.isFocused && editing) editing = false
                    },
            )
        }
    }
}
