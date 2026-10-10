package app.novaiptv.data.epg

import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgChannel
import app.novaiptv.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgMatcherTest {
    private fun channel(name: String, epgId: String? = null, tvgName: String? = null) = Channel(
        id = "1:$name", playlistId = 1, number = 1, name = name, groupName = null, logoUrl = null,
        epgId = epgId, stream = StreamSource("http://x/$name"),
        extras = if (tvgName != null) mapOf("tvg-name" to tvgName) else emptyMap(),
    )

    @Test fun normalization() {
        assertEquals("bbc one", EpgMatcher.normalize("BBC One HD"))
        assertEquals("bbc one", EpgMatcher.normalize("bbc one"))
        assertEquals("sky news", EpgMatcher.normalize("UK: Sky News FHD"))
        assertEquals("sky news", EpgMatcher.normalize("UK | Sky News"))
        assertEquals("das erste", EpgMatcher.normalize("DE | Das Erste HEVC"))
        assertEquals("lrt plius", EpgMatcher.normalize("LT - LRT Plius"))
        assertEquals("pro7", EpgMatcher.normalize("[DE] Pro7 4K"))
        assertEquals("discovery", EpgMatcher.normalize("Discovery UHD H265"))
        assertEquals("zdf neo", EpgMatcher.normalize("ZDF-neo"))
        assertEquals("ct 1", EpgMatcher.normalize("ČT 1 SD"))
        assertEquals("canal plus", EpgMatcher.normalize("Canal+"))
        assertEquals("hd", EpgMatcher.normalize("HD"))
        // Timeshift channels stay distinct.
        assertEquals("sky one plus1", EpgMatcher.normalize("Sky One +1 HD"))
        assertNotEquals(EpgMatcher.normalize("Sky One"), EpgMatcher.normalize("Sky One +1"))
    }

    private val epg = listOf(
        EpgChannel("BBCOne.uk", listOf("BBC One", "BBC 1")),
        EpgChannel("SkyNews.uk", listOf("Sky News")),
        EpgChannel("LRT Televizija", listOf("LRT")),
        EpgChannel("ProSieben.de", listOf("Pro7 HD")),
        EpgChannel("TV3.lt", emptyList()),
    )
    private val byId = epg.associateBy { it.id }
    private val byName = EpgMatcher.buildNameIndex(epg)

    @Test fun matching() {
        assertEquals("BBCOne.uk", EpgMatcher.match(channel("BBC One HD"), byId, byName))
        assertEquals("SkyNews.uk", EpgMatcher.match(channel("UK: Sky News FHD"), byId, byName))
        assertEquals("LRT Televizija", EpgMatcher.match(channel("Whatever", epgId = "lrt televizija"), byId, byName))
        assertEquals("LRT Televizija", EpgMatcher.match(channel("Whatever", epgId = "LRT Televizija"), byId, byName))
        assertEquals("ProSieben.de", EpgMatcher.match(channel("DE: Pro7"), byId, byName))
        assertEquals("TV3.lt", EpgMatcher.match(channel("LT | TV3 HD"), byId, byName))
        assertEquals("BBCOne.uk", EpgMatcher.match(channel("x", tvgName = "BBC 1 FHD"), byId, byName))
        // Unknown epgId falls back to the name.
        assertEquals("SkyNews.uk", EpgMatcher.match(channel("Sky News", epgId = "nope"), byId, byName))
        assertNull(EpgMatcher.match(channel("Unknown Channel"), byId, byName))
        assertNull(EpgMatcher.match(channel("Sky News +1"), byId, byName))
    }

    @Test fun nameIndexFirstWins() {
        val idx = EpgMatcher.buildNameIndex(listOf(EpgChannel("a", listOf("Same HD")), EpgChannel("b", listOf("Same"))))
        assertEquals("a", idx["same"])
    }
}
