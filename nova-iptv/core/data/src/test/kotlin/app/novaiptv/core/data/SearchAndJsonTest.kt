package app.novaiptv.core.data

import app.novaiptv.core.data.logic.MiniJson
import app.novaiptv.core.data.logic.SearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndJsonTest {

    @Test
    fun normalizeLowercasesStripsDiacriticsAndCollapsesSpaces() {
        assertEquals("lrt plius", SearchText.normalize("  LRT   Plius "))
        assertEquals("zinios 24", SearchText.normalize("Žinios\t24"))
        assertEquals("tele zuri", SearchText.normalize("Télé Züri"))
    }

    @Test
    fun escapeLikeEscapesWildcardsAndEscapeChar() {
        assertEquals("100\\%", SearchText.escapeLike("100%"))
        assertEquals("a\\_b", SearchText.escapeLike("a_b"))
        assertEquals("c\\\\d", SearchText.escapeLike("c\\d"))
        assertEquals("plain", SearchText.escapeLike("plain"))
    }

    @Test
    fun patternsAreNormalisedAndEscaped() {
        assertEquals("%sport\\_1%", SearchText.containsPattern(" Sport_1 "))
        assertEquals("sport\\_1%", SearchText.prefixPattern("Sport_1"))
        assertEquals("%50\\%%", SearchText.containsPattern("50%"))
        assertNull(SearchText.containsPattern("   "))
        assertNull(SearchText.rawContainsPattern(""))
        assertEquals("%News \\%%", SearchText.rawContainsPattern(" News  % "))
    }

    @Test
    fun miniJsonRoundTripsSpecialCharacters() {
        val map = linkedMapOf(
            "tvg-name" to "Quote \" and \\ backslash",
            "x" to "line\nbreak\ttab\u0001ctrl",
            "unicode" to "Žalgiris – ☃",
            "" to "",
        )
        val json = MiniJson.encode(map)
        assertEquals(map, MiniJson.decode(json))
    }

    @Test
    fun miniJsonDecodesStandardEscapesAndToleratesGarbage() {
        assertEquals(mapOf("a" to "/é"), MiniJson.decode("""{ "a" : "\/é" }"""))
        assertEquals(emptyMap<String, String>(), MiniJson.decode("{}"))
        assertEquals(emptyMap<String, String>(), MiniJson.decode(null))
        assertEquals(emptyMap<String, String>(), MiniJson.decode("not json"))
        assertEquals(emptyMap<String, String>(), MiniJson.decode("""{"a":"unterminated"""))
        assertEquals(emptyMap<String, String>(), MiniJson.decode("""{"a":1}"""))
        assertNull(MiniJson.encodeOrNull(emptyMap()))
        assertTrue(MiniJson.encode(mapOf("k" to "v")) == """{"k":"v"}""")
    }
}
