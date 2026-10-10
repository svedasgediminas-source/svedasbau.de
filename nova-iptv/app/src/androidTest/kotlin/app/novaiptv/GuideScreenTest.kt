package app.novaiptv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** TV Guide grid with a seeded playlist and XMLTV guide around "now". */
@RunWith(AndroidJUnit4::class)
class GuideScreenTest {

    private val seed = SeededPlaylistRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(composeRule)

    private val ui = NovaUiDriver(composeRule)

    /** Group chips read "<label>  <count>". */
    private fun chip(label: String, count: Int) = clickableWith("$label  $count")

    private fun openGuide() {
        ui.openTab(R.string.nav_guide)
        ui.waitForText(ui.string(R.string.guide_title))
    }

    @Test
    fun gridShowsSeededProgrammesLiveBadgeAndGroupChips() {
        openGuide()
        val first = SeededPlaylistRule.CHANNELS.first()
        // The cursor starts at "now" on the first row: the info panel shows the live programme.
        ui.waitForText(first.nowTitle!!)
        ui.waitForText(ui.string(R.string.guide_live_badge))
        ui.waitForText(first.name)
        // The next programme is visible in the grid as well.
        ui.waitForText(first.nextTitle!!)

        ui.waitFor(chip(ui.string(R.string.guide_group_all), SeededPlaylistRule.CHANNELS.size), "chip 'All'")
        ui.waitFor(chip(ui.string(R.string.guide_group_favorites), 0), "chip 'Favourites'")
        for ((group, count) in SeededPlaylistRule.GROUPS) ui.waitFor(chip(group, count), "chip '$group  $count'")
        TestData.log(
            "ui guide: '${first.nowTitle}' with ${ui.string(R.string.guide_live_badge)} badge, next '${first.nextTitle}', " +
                "chips all/fav/${SeededPlaylistRule.GROUPS.keys.joinToString("/")} shown",
        )
    }

    @Test
    fun choosingAGroupChipFiltersRows() {
        openGuide()
        val first = SeededPlaylistRule.CHANNELS.first()
        ui.waitForText(first.nowTitle!!)

        val group = "Movies"
        val inGroup = SeededPlaylistRule.inGroup(group)
        ui.node(chip(group, inGroup.size)).performSemanticsAction(SemanticsActions.OnClick)

        ui.waitFor(hasText(inGroup.first().name), "first '$group' row")
        ui.waitForGone(hasText(first.name), "row '${first.name}' (not in '$group')")
        for (c in SeededPlaylistRule.CHANNELS) {
            if (c.group != group) assertEquals("row of ${c.name} still shown in '$group'", 0, ui.count(hasText(c.name)))
        }
        // The cursor moved to the first row of the group: its live programme is in the info panel.
        ui.waitForText(inGroup.first().nowTitle!!)
        // DOWN moves the cursor through the group's rows (the grid scrolls); a channel without
        // guide data still gets a row that says there is no programme information.
        val noEpg = inGroup.last()
        assertEquals(null, noEpg.epgId)
        repeat(inGroup.size - 1) { ui.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        ui.waitForText(noEpg.name)
        ui.waitForText(ui.string(R.string.guide_no_programme))
        TestData.log("ui guide: chip '$group' -> rows ${inGroup.joinToString { it.name }}, other groups hidden")
    }

    @Test
    fun okOnAFutureProgrammeOffersAReminder() {
        openGuide()
        val first = SeededPlaylistRule.CHANNELS.first()
        ui.waitForText(first.nowTitle!!)
        ui.waitForText(ui.string(R.string.guide_live_badge))

        // RIGHT: the cursor steps to the next (future) programme; OK asks about a reminder.
        ui.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        ui.waitForGone(hasText(ui.string(R.string.guide_live_badge)), "LIVE badge after moving to the next programme")
        ui.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val remind = ui.string(R.string.guide_reminder_add)
        ui.waitFor(clickableWith(remind), "'$remind' button")
        ui.waitForText(ui.string(R.string.guide_reminder_question))
        // Dialog title = the programme.
        ui.waitFor(hasText(first.nextTitle!!), "dialog title '${first.nextTitle}'")

        // Cancel: nothing is scheduled, the dialog closes, the guide stays.
        ui.waitForFocus(remind)
        ui.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        ui.waitForFocus(ui.string(R.string.common_cancel))
        ui.press(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitForGone(clickableWith(remind), "reminder dialog")
        ui.waitForText(ui.string(R.string.guide_title))
        ui.assertAlive()
        TestData.log("ui guide: OK on '${first.nextTitle}' showed '$remind', Cancel closed it")
    }
}
