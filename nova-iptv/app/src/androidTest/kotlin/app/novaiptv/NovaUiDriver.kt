package app.novaiptv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse

typealias MainRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/** Matches a node whose own (merged) text or one of its descendants' text equals [text]. */
fun hasTextDeep(text: String, substring: Boolean = false): SemanticsMatcher =
    hasText(text, substring = substring) or hasAnyDescendant(hasText(text, substring = substring))

/** A clickable element (TV Surface, card, list item) showing [text]. */
fun clickableWith(text: String, substring: Boolean = false): SemanticsMatcher = hasClickAction() and hasTextDeep(text, substring)

/** Small D-pad / wait helpers shared by the screen tests. Keys go through the real input pipeline. */
class NovaUiDriver(private val rule: MainRule) {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun string(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    fun exists(matcher: SemanticsMatcher): Boolean =
        rule.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    fun count(matcher: SemanticsMatcher): Int =
        rule.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).size

    fun waitFor(matcher: SemanticsMatcher, what: String, timeoutMs: Long = 20_000) {
        try {
            rule.waitUntil(timeoutMs) { exists(matcher) }
        } catch (e: Throwable) {
            throw failure("timed out after ${timeoutMs}ms waiting for $what", e)
        }
    }

    fun waitForGone(matcher: SemanticsMatcher, what: String, timeoutMs: Long = 20_000) {
        try {
            rule.waitUntil(timeoutMs) { !exists(matcher) }
        } catch (e: Throwable) {
            throw failure("timed out after ${timeoutMs}ms waiting for $what to disappear", e)
        }
    }

    /** Texts of the focused node and of the screen, for failure messages (CI shows only logs). */
    fun screenContext(): String = runCatching {
        fun texts(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes(atLeastOneRootRequired = false)
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
        val focused = texts(isFocused()).joinToString("|")
        val all = texts(hasText("", substring = true)).distinct().take(30).joinToString("|")
        "focused=[$focused] screen=[$all]"
    }.getOrElse { "context unavailable: ${it.javaClass.simpleName}" }

    private fun failure(message: String, cause: Throwable): AssertionError {
        val context = screenContext()
        TestData.log("FAIL $message; $context")
        return AssertionError("$message; $context", cause)
    }

    fun waitForText(text: String, timeoutMs: Long = 20_000) = waitFor(hasText(text), "text '$text'", timeoutMs)

    /** Waits until the focused element shows [text]. */
    fun waitForFocus(text: String, timeoutMs: Long = 15_000) =
        waitFor(isFocused() and hasTextDeep(text), "focus on '$text'", timeoutMs)

    fun press(vararg keyCodes: Int) {
        for (k in keyCodes) {
            instrumentation.sendKeyDownUpSync(k)
            rule.waitForIdle()
        }
    }

    /** Long press as a TV remote sends it: DOWN, a repeated DOWN flagged as long press, UP. */
    fun longPress(keyCode: Int) {
        val down = SystemClock.uptimeMillis()
        val first = KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0)
        instrumentation.sendKeySync(first)
        SystemClock.sleep(LONG_PRESS_STEP_MS)
        instrumentation.sendKeySync(KeyEvent.changeTimeRepeat(first, SystemClock.uptimeMillis(), 1, KeyEvent.FLAG_LONG_PRESS))
        SystemClock.sleep(LONG_PRESS_STEP_MS)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
        rule.waitForIdle()
    }

    /** Presses [keyCode] until the focused element matches [target] (at most [max] presses). */
    fun pressUntilFocused(keyCode: Int, target: SemanticsMatcher, what: String, max: Int = 12) {
        repeat(max) {
            if (exists(isFocused() and target)) return
            press(keyCode)
            // Focus moves can be followed by a scroll / lazy composition.
            runCatching { rule.waitUntil(1_000) { exists(isFocused() and target) } }
        }
        if (!exists(isFocused() and target)) {
            throw failure("focus never reached $what after $max presses of key $keyCode", IllegalStateException(what))
        }
    }

    /** Opens a top-bar tab (text pill or icon pill whose content description is the label). */
    fun openTab(labelRes: Int) {
        val label = string(labelRes)
        val pill = hasClickAction() and (hasTextDeep(label) or hasContentDescription(label) or hasAnyDescendant(hasContentDescription(label)))
        waitFor(pill, "top bar tab '$label'")
        rule.onAllNodes(pill).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction = rule.onAllNodes(matcher).onFirst()

    fun assertAlive() = assertFalse("activity finished unexpectedly", rule.activity.isFinishing)

    private companion object {
        const val LONG_PRESS_STEP_MS = 120L
    }
}
