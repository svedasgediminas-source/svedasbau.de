package app.novaiptv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Global search over the seeded playlist. */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {

    private val seed = SeededPlaylistRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seed).around(composeRule)

    private val ui = NovaUiDriver(composeRule)

    /** Opens Search and puts the NovaTextField into editing mode (OK on the field). */
    private fun openSearchField() {
        ui.openTab(R.string.nav_search)
        val label = ui.string(R.string.search_label)
        ui.waitFor(clickableWith(label), "search field")
        ui.node(clickableWith(label)).performSemanticsAction(SemanticsActions.OnClick)
        ui.waitFor(hasSetTextAction(), "editable search text field")
    }

    /** A result card (clickable, not the text field) showing exactly [name]. */
    private fun resultCard(name: String) = clickableWith(name) and !hasSetTextAction()

    @Test
    fun typingAChannelNameShowsItInResults() {
        openSearchField()
        val target = SeededPlaylistRule.CHANNELS.first { it.group == "Sports" && it.name.startsWith("Foxtrot") }
        val query = "Foxtrot"
        ui.node(hasSetTextAction()).performTextInput(query)

        ui.waitForText(ui.string(R.string.search_channels))
        ui.waitFor(resultCard(target.name), "result card '${target.name}'")
        // Only the matching channel is listed among the seeded channels.
        for (c in SeededPlaylistRule.CHANNELS) {
            if (c != target) assertEquals("unexpected result ${c.name} for '$query'", 0, ui.count(resultCard(c.name)))
        }
        TestData.log("ui search: '$query' -> channel '${target.name}' in results")

        // A query without matches shows the empty state.
        val nothing = "zzqx"
        ui.node(hasSetTextAction()).performTextReplacement(nothing)
        ui.waitForText(ui.string(R.string.search_no_results_title, nothing))
        ui.waitForGone(resultCard(target.name), "old result card")
        ui.assertAlive()
        TestData.log("ui search: '$nothing' -> no results message")
    }

    @Test
    fun searchingAProgrammeTitleFindsTheProgramme() {
        openSearchField()
        val target = SeededPlaylistRule.CHANNELS.first { it.name.startsWith("Hotel") }
        val query = "Hotel Movies Live"
        ui.node(hasSetTextAction()).performTextInput(query)
        ui.waitForText(ui.string(R.string.search_programmes))
        ui.waitFor(clickableWith(target.nowTitle!!) and !hasSetTextAction(), "programme card '${target.nowTitle}'")
        TestData.log("ui search: '$query' -> programme '${target.nowTitle}'")
    }
}
