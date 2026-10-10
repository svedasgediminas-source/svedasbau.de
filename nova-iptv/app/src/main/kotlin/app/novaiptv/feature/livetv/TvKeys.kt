package app.novaiptv.feature.livetv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type

/** Small helpers for remote-control key handling shared by Live TV and the Guide. */
internal fun KeyEvent.isKeyDown(): Boolean = type == KeyEventType.KeyDown

internal fun KeyEvent.isKeyUp(): Boolean = type == KeyEventType.KeyUp

/** 0..9 for number-row and numpad digit keys, else null. */
internal fun KeyEvent.digitOrNull(): Int? {
    val code = key.nativeKeyCode
    return when (code) {
        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> code - AndroidKeyEvent.KEYCODE_0
        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> code - AndroidKeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }
}

internal fun KeyEvent.isSelectKey(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

internal fun KeyEvent.isMenuKey(): Boolean = key.nativeKeyCode == AndroidKeyEvent.KEYCODE_MENU
