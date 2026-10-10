package app.novaiptv

import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Live TV with a seeded playlist, driven with the D-pad and MENU like a TV remote. */
@RunWith(AndroidJUnit4::class)
class LiveTvScreenTest {

    private val seed = SeededPlaylistRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(composeRule)

    private val ui = NovaUiDriver(composeRule)

    private fun categoryRow(label: String, count: Int) = clickableWith(label) and hasTextDeep(count.toString())

    private fun channelRow(name: String) = clickableWith(name)

    private fun openLiveTv() {
        ui.openTab(R.string.nav_live_tv)
        ui.waitForText(ui.string(R.string.livetv_categories_title))
    }

    @Test
    fun categoriesShowGroupCountsAndRowsShowCurrentProgramme() {
        openLiveTv()
        val all = ui.string(R.string.livetv_category_all)
        val favourites = ui.string(R.string.livetv_category_favorites)
        ui.waitFor(categoryRow(all, SeededPlaylistRule.CHANNELS.size), "'$all' with ${SeededPlaylistRule.CHANNELS.size}")
        ui.waitFor(categoryRow(favourites, 0), "'$favourites' with 0")
        for ((group, count) in SeededPlaylistRule.GROUPS) {
            ui.waitFor(categoryRow(group, count), "category '$group' with $count")
        }
        // Initial focus: the first channel; its row shows the programme airing now (lazy now/next).
        val first = SeededPlaylistRule.CHANNELS.first()
        ui.waitForFocus(first.name)
        ui.waitFor(channelRow(first.name) and hasTextDeep(first.nowTitle!!), "current programme '${first.nowTitle}' in the row of ${first.name}")
        // Details pane: now and next of the focused channel.
        ui.waitForText(first.nextTitle!!)
        TestData.log(
            "ui livetv: categories ${SeededPlaylistRule.GROUPS} + all=${SeededPlaylistRule.CHANNELS.size} fav=0 shown; " +
                "row '${first.name}' shows now='${first.nowTitle}', details next='${first.nextTitle}'",
        )
    }

    @Test
    fun selectingAGroupWithDpadFiltersTheChannelList() {
        openLiveTv()
        val first = SeededPlaylistRule.CHANNELS.first()
        ui.waitForFocus(first.name)

        val group = "Sports"
        val inGroup = SeededPlaylistRule.inGroup(group)
        // LEFT: into the categories (the selected one, "All"); DOWN until the Sports category is focused.
        ui.press(KeyEvent.KEYCODE_DPAD_LEFT)
        ui.waitForFocus(ui.string(R.string.livetv_category_all))
        ui.pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, categoryRow(group, inGroup.size), "category '$group'")

        // Focusing a category selects it: only its channels remain.
        ui.waitFor(channelRow(inGroup.first().name), "first $group channel")
        ui.waitForGone(channelRow(first.name), "channel '${first.name}' (not in $group)")
        for (c in SeededPlaylistRule.CHANNELS) {
            if (c.group != group) assertEquals("row of ${c.name} still shown in '$group'", 0, ui.count(channelRow(c.name)))
        }
        assertEquals(inGroup.size, inGroup.count { ui.exists(channelRow(it.name)) })

        // RIGHT goes back to the (filtered) list.
        ui.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        ui.waitForFocus(inGroup.first().name)
        ui.press(KeyEvent.KEYCODE_DPAD_DOWN)
        ui.waitForFocus(inGroup[1].name)
        ui.assertAlive()
        TestData.log("ui livetv: D-pad selected '$group' -> ${inGroup.size} rows (${inGroup.joinToString { it.name }}), other groups hidden")
    }

    @Test
    fun menuOpensChannelOptionsAndFavouritesAreCounted() {
        openLiveTv()
        val favourites = ui.string(R.string.livetv_category_favorites)
        val first = SeededPlaylistRule.CHANNELS[0]
        val second = SeededPlaylistRule.CHANNELS[1]
        ui.waitForFocus(first.name)
        ui.waitFor(categoryRow(favourites, 0), "'$favourites' with 0")

        // MENU: channel options dialog titled with the channel name.
        ui.press(KeyEvent.KEYCODE_MENU)
        val add = ui.string(R.string.common_favorite_add)
        val fixGuide = ui.string(R.string.livetv_epgfix_action)
        ui.waitFor(clickableWith(add), "'$add' in the channel options")
        ui.waitFor(clickableWith(fixGuide), "'$fixGuide' in the channel options")
        TestData.log("ui livetv: MENU opened channel options ('$add', '$fixGuide')")

        // OK on the first option ("Add to favorites", focused when the dialog opens).
        ui.waitForFocus(add)
        ui.press(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitForGone(clickableWith(fixGuide), "channel options dialog")
        ui.waitFor(categoryRow(favourites, 1), "'$favourites' with 1 after MENU > $add")
        ui.waitForText(ui.string(R.string.livetv_favorite_added, first.name))
        TestData.log("ui livetv: MENU > '$add' -> Favourites count 1")

        // Long-press OK on the next channel toggles it as favourite too.
        ui.waitForFocus(first.name)
        ui.press(KeyEvent.KEYCODE_DPAD_DOWN)
        ui.waitForFocus(second.name)
        ui.longPress(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitFor(categoryRow(favourites, 2), "'$favourites' with 2 after long-press OK")
        TestData.log("ui livetv: long-press OK -> Favourites count 2")
        ui.longPress(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitFor(categoryRow(favourites, 1), "'$favourites' back to 1 after a second long-press OK")
        // The long press must not have started playback.
        assertTrue("player opened by a long press", ui.exists(hasText(ui.string(R.string.livetv_categories_title))))

        // The Favourites category lists exactly the remaining favourite.
        ui.press(KeyEvent.KEYCODE_DPAD_LEFT)
        ui.pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, categoryRow(favourites, 1), "category '$favourites'")
        ui.waitForGone(channelRow(second.name), "non-favourite '${second.name}' in Favourites")
        ui.waitFor(channelRow(first.name), "favourite '${first.name}' in Favourites")
        assertEquals(1, SeededPlaylistRule.CHANNELS.count { ui.exists(channelRow(it.name)) })
        TestData.log("ui livetv: Favourites category lists only '${first.name}'")
    }

    @Test
    fun menuFixTvGuideOpensTheMappingDialog() {
        openLiveTv()
        val first = SeededPlaylistRule.CHANNELS.first()
        ui.waitForFocus(first.name)
        ui.press(KeyEvent.KEYCODE_MENU)
        val fixGuide = ui.string(R.string.livetv_epgfix_action)
        ui.waitFor(clickableWith(fixGuide), "'$fixGuide'")
        ui.pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, clickableWith(fixGuide), "'$fixGuide'", max = 3)
        ui.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val title = ui.string(R.string.livetv_epgfix_title, first.name)
        ui.waitForText(title)
        // BACK closes the dialog and focus returns to the channel.
        ui.press(KeyEvent.KEYCODE_BACK)
        ui.waitForGone(hasText(title), "EPG fix dialog")
        ui.waitFor(isFocused() and hasTextDeep(first.name), "focus back on '${first.name}'")
        ui.assertAlive()
        TestData.log("ui livetv: MENU > '$fixGuide' opened '$title', BACK closed it")
    }
}
