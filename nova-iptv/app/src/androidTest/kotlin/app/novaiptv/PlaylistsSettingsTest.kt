package app.novaiptv

import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Settings > Playlists and the playlist manager with the seeded playlist. */
@RunWith(AndroidJUnit4::class)
class PlaylistsSettingsTest {

    private val seed = SeededPlaylistRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(composeRule)

    private val ui = NovaUiDriver(composeRule)

    @Test
    fun seededPlaylistIsListedAsActiveAndManagerOpensWithDpad() {
        ui.openTab(R.string.nav_settings)
        val active = ui.string(R.string.playlist_active)
        val name = SeededPlaylistRule.PLAYLIST_NAME
        val manage = ui.string(R.string.settings_playlists_manage)
        val privacy = ui.string(R.string.settings_cat_privacy)
        // Settings opens on the Playlists category.
        ui.waitFor(clickableWith(manage), "'$manage'")
        ui.waitFor(clickableWith(name) and hasTextDeep(active), "playlist '$name' marked '$active' in Settings")
        TestData.log("ui settings: playlist '$name' listed as '$active'")

        // D-pad: focus is on the Playlists category; RIGHT into the detail list, OK opens the manager.
        ui.waitForFocus(ui.string(R.string.settings_cat_playlists))
        ui.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        ui.waitFor(androidx.compose.ui.test.isFocused() and (clickableWith(manage) or clickableWith(name)), "focus in the playlists detail list")
        ui.press(KeyEvent.KEYCODE_DPAD_CENTER)

        // Playlist manager: no settings categories, the playlist with its details and "Active".
        ui.waitForGone(hasText(privacy), "settings categories")
        ui.waitForText(ui.string(R.string.playlist_manage_title))
        ui.waitFor(clickableWith(name) and hasTextDeep(active), "playlist '$name' marked '$active' in the manager")
        ui.waitFor(clickableWith(ui.string(R.string.playlist_refresh)), "'Refresh' action")
        TestData.log("ui playlists: manager lists '$name' as '$active'")

        // BACK returns to Settings.
        ui.press(KeyEvent.KEYCODE_BACK)
        ui.waitForText(privacy)
        ui.assertAlive()
    }
}
