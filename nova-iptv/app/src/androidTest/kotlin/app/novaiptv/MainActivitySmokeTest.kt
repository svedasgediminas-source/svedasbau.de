package app.novaiptv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** UI smoke test of the real activity on a clean install (no playlist). */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    /** Data is cleared before the activity starts, so Home shows the onboarding. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(CleanDataRule()).around(composeRule)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun press(vararg keyCodes: Int) {
        for (k in keyCodes) {
            instrumentation.sendKeyDownUpSync(k)
            composeRule.waitForIdle()
        }
    }

    @Test
    fun onboardingIsShownOnCleanInstall() {
        val title = string(R.string.home_onboarding_title)
        waitForText(title)
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.playlist_type_m3u_url)).assertIsDisplayed()
        TestData.log("ui: onboarding '$title' displayed")
    }

    @Test
    fun dpadNavigationDoesNotCrash() {
        val title = string(R.string.home_onboarding_title)
        waitForText(title)
        press(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
        assertFalse("activity finished after D-pad input", composeRule.activity.isFinishing)
        composeRule.onNodeWithText(title).assertIsDisplayed()
        TestData.log("ui: D-pad sequence ok, onboarding still displayed")
    }

    @Test
    fun settingsTabOpensFromTopBar() {
        waitForText(string(R.string.home_onboarding_title))
        val settings = string(R.string.nav_settings)
        // The Settings pill is icon-only (content description = label) while not focused.
        composeRule.onNode(
            hasClickAction() and (hasContentDescription(settings) or hasAnyDescendant(hasContentDescription(settings))),
        ).performSemanticsAction(SemanticsActions.OnClick)
        val privacy = string(R.string.settings_cat_privacy)
        waitForText(privacy)
        composeRule.onAllNodesWithText(string(R.string.settings_cat_language)).onFirst().assertIsDisplayed()
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT)
        assertFalse(composeRule.activity.isFinishing)
        TestData.log("ui: Settings opened from top bar ('$privacy' visible), D-pad inside ok")
    }
}
