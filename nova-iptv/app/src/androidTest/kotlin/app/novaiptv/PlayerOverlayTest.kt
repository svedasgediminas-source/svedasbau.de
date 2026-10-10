package app.novaiptv

import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Player opened from Live TV: overlay, remote keys and BACK. BACK to Live TV is the regression
 * check for the NetworkOnMainThreadException crash when leaving the player.
 */
@RunWith(AndroidJUnit4::class)
class PlayerOverlayTest {

    private val seed = SeededPlaylistRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(composeRule)

    private val ui = NovaUiDriver(composeRule)

    private val liveTvMarker get() = hasText(ui.string(R.string.livetv_categories_title))

    private fun openLiveTvFocused(name: String) {
        ui.openTab(R.string.nav_live_tv)
        ui.waitFor(liveTvMarker, "Live TV")
        ui.waitForFocus(SeededPlaylistRule.CHANNELS.first().name)
        ui.pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, clickableWith(name), "channel '$name'")
    }

    /** OK on the focused channel; waits until Live TV is gone (player full screen). */
    private fun playFocused() {
        ui.press(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitForGone(liveTvMarker, "Live TV after OK (player should open)")
    }

    private fun backToLiveTv(what: String) {
        ui.press(KeyEvent.KEYCODE_BACK)
        ui.waitFor(liveTvMarker, "Live TV after BACK from $what", timeoutMs = 15_000)
        ui.assertAlive()
    }

    @Test
    fun overlayShowsChannelKeysWorkAndBackReturnsToLiveTv() {
        assumeTrue(
            "emulator has no internet access to the public HLS sample (${TestData.PUBLIC_HLS}); overlay checks need a playing stream",
            TestData.reachable(TestData.PUBLIC_HLS),
        )
        val channels = SeededPlaylistRule.CHANNELS
        val first = channels[0]
        val second = channels[1]
        openLiveTvFocused(first.name)
        playFocused()

        // Info overlay: channel name, LIVE marker and the programme airing now.
        ui.waitForText(first.name)
        ui.waitForText(ui.string(R.string.player_live))
        ui.waitForText(first.nowTitle!!)
        TestData.log("ui player: overlay shows '${first.name}' / '${first.nowTitle}'")

        // MENU opens the track panel; BACK closes it and stays in the player.
        ui.press(KeyEvent.KEYCODE_MENU)
        val audio = ui.string(R.string.player_audio)
        ui.waitForText(audio)
        ui.press(KeyEvent.KEYCODE_BACK)
        ui.waitForGone(hasText(audio), "track panel after BACK")
        assertNotOnLiveTv()

        // CH+ switches to the adjacent channel (overlay shows the new name).
        ui.press(KeyEvent.KEYCODE_CHANNEL_UP)
        ui.waitForText(second.name)
        ui.waitForGone(hasText(first.name), "previous channel name after CH+")
        TestData.log("ui player: MENU panel opened/closed, CH+ switched '${first.name}' -> '${second.name}'")

        // INFO shows the controls; BACK hides them, a second BACK leaves the player.
        ui.press(KeyEvent.KEYCODE_INFO)
        val settings = ui.string(R.string.player_settings)
        ui.waitFor(clickableWith(settings), "player controls after INFO")
        ui.press(KeyEvent.KEYCODE_BACK)
        ui.waitForGone(clickableWith(settings), "player controls after BACK")
        assertNotOnLiveTv()
        backToLiveTv("the player")
        TestData.log("ui player: INFO controls, BACK x2 -> Live TV, no crash")
    }

    @Test
    fun brokenStreamShowsErrorAndBackReturnsToLiveTv() {
        val broken = SeededPlaylistRule.CHANNELS.first { it.broken }
        openLiveTvFocused(broken.name)
        playFocused()
        // 404 stream: bounded retries end in the error panel (never an endless spinner, never a crash).
        ui.waitForText(ui.string(R.string.player_error_title), timeoutMs = 60_000)
        ui.waitFor(clickableWith(ui.string(R.string.player_retry)), "Retry button")
        TestData.log("ui player: broken stream '${broken.name}' -> '${ui.string(R.string.player_error_title)}'")
        backToLiveTv("the error panel")
        ui.waitForFocus(broken.name)
        TestData.log("ui player: BACK from error -> Live TV, focus back on '${broken.name}'")
    }

    @Test
    fun backDuringLoadingReturnsToLiveTvWithoutCrash() {
        val first = SeededPlaylistRule.CHANNELS.first()
        openLiveTvFocused(first.name)
        // Leave immediately, while the stream is still being opened (release on a busy player).
        playFocused()
        backToLiveTv("a loading player")
        // And once more, to exercise a second player instance in the same process.
        ui.waitForFocus(first.name)
        playFocused()
        backToLiveTv("the second player")
        TestData.log("ui player: open/BACK twice while loading -> Live TV, no crash")
    }

    private fun assertNotOnLiveTv() {
        if (ui.exists(liveTvMarker)) throw AssertionError("left the player unexpectedly")
    }
}
