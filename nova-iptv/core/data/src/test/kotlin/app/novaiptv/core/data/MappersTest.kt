package app.novaiptv.core.data

import app.novaiptv.core.data.db.EpisodeEntity
import app.novaiptv.core.data.db.PlaylistEntity
import app.novaiptv.core.data.db.PlaylistTypes
import app.novaiptv.core.data.db.SeasonEntity
import app.novaiptv.core.data.db.StreamEmbedded
import app.novaiptv.core.data.db.WatchHistoryEntity
import app.novaiptv.core.data.mapper.assembleSeasons
import app.novaiptv.core.data.mapper.joinUrls
import app.novaiptv.core.data.mapper.splitUrls
import app.novaiptv.core.data.mapper.toEntity
import app.novaiptv.core.data.mapper.toModel
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.ContentType
import app.novaiptv.core.model.EpgProgram
import app.novaiptv.core.model.Movie
import app.novaiptv.core.model.PlaylistSource
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.StreamSource
import app.novaiptv.core.model.WatchHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappersTest {

    private val channel = Channel(
        id = "3:abc",
        playlistId = 3,
        number = 7,
        name = "Žinios HD",
        groupName = "News",
        logoUrl = "http://logo/x.png",
        epgId = "zinios.lt",
        stream = StreamSource(
            url = "http://s/1.m3u8",
            userAgent = "UA",
            referrer = "http://ref",
            headers = mapOf("X-Token" to "t\"1"),
            mimeTypeHint = StreamFormat.Hls,
        ),
        catchup = CatchupCapability.Supported(CatchupType.Shift, 5, "?utc={utc}"),
        isAdult = false,
        extras = mapOf("tvg-name" to "Zinios"),
    )

    @Test
    fun channelRoundTrip() {
        val entity = channel.toEntity(sortIndex = 42)
        assertEquals("zinios hd", entity.nameNormalized)
        assertEquals(42, entity.sortIndex)
        assertEquals("Shift", entity.catchupType)
        assertEquals(channel, entity.toModel())
    }

    @Test
    fun channelWithoutGroupOrCatchup() {
        val c = channel.copy(groupName = null, catchup = CatchupCapability.None, extras = emptyMap(), epgId = null)
        val e = c.toEntity(0)
        assertEquals("", e.groupName)
        assertNull(e.catchupType)
        assertNull(e.extrasJson)
        assertEquals(c, e.toModel())
    }

    @Test
    fun effectiveEpgIdPrefersManualThenResolved() {
        val e = channel.toEntity(0)
        assertEquals("zinios.lt", e.toModel().epgId)
        assertEquals("Zinios.lt", e.copy(resolvedEpgId = "Zinios.lt").toModel().epgId)
        assertEquals("manual", e.copy(resolvedEpgId = "Zinios.lt", manualEpgId = "manual").toModel().epgId)
    }

    @Test
    fun unknownEnumValuesFallBack() {
        val e = channel.toEntity(0).let { it.copy(stream = it.stream.copy(format = "Bogus"), catchupType = "Bogus") }
        val m = e.toModel()
        assertEquals(StreamFormat.Unknown, m.stream.mimeTypeHint)
        assertEquals(CatchupCapability.None, m.catchup)
    }

    @Test
    fun playlistSources() {
        val base = PlaylistEntity(id = 1, name = "P", type = PlaylistTypes.M3U_URL, url = "http://a/b.m3u", epgUrls = "http://e/1.xml\nhttp://e/2.xml")
        val m = base.toModel()
        assertEquals(PlaylistSource.M3uUrl("http://a/b.m3u"), m.source)
        assertEquals(listOf("http://e/1.xml", "http://e/2.xml"), m.epgUrls)
        assertEquals(
            PlaylistSource.M3uFile("content://x/1", "list.m3u"),
            base.copy(type = PlaylistTypes.M3U_FILE, url = "content://x/1", displayName = "list.m3u").toModel().source,
        )
        assertEquals(
            PlaylistSource.Xtream("http://portal", "key-1"),
            base.copy(type = PlaylistTypes.XTREAM, url = "http://portal", credentialKey = "key-1").toModel().source,
        )
    }

    @Test
    fun urlJoinSplit() {
        assertEquals("a\nb", joinUrls(listOf(" a ", "", "b", "a")))
        assertEquals(emptyList<String>(), splitUrls(""))
        assertEquals(emptyList<String>(), splitUrls(null))
    }

    @Test
    fun programRoundTrip() {
        val p = EpgProgram("id.lt", 1000, 2000, "Title", "Desc", "Cat", "http://i", "1.2.")
        assertEquals(p, p.toEntity().toModel())
    }

    @Test
    fun movieRoundTrip() {
        val m = Movie(
            id = "1:m:5", playlistId = 1, name = "Les Misérables", categoryId = "9", posterUrl = null,
            stream = StreamSource("http://s/movie/{xtream_user}/{xtream_pass}/5.mkv", mimeTypeHint = StreamFormat.Progressive),
            year = 2012, plot = "p", genre = "g", rating = 7.5, durationSeconds = 9000, addedAtMillis = 123,
        )
        val e = m.toEntity(3)
        assertEquals("les miserables", e.nameNormalized)
        assertEquals(m, e.toModel())
    }

    @Test
    fun historyRoundTripAndUnknownType() {
        val h = WatchHistory(ContentType.Episode, "1:e:9", 1, "Ep", null, 10, 100, 5, parentId = "1:s:2")
        assertEquals(h, h.toEntity().toModel())
        val bad = WatchHistoryEntity("Podcast", "x", 1, "t", null, 0, 0, 0, null)
        assertNull(bad.toModel())
    }

    @Test
    fun assembleSeasonsAddsMissingSeasonRowsAndSortsEpisodes() {
        fun ep(id: String, season: Int, n: Int) =
            EpisodeEntity(id, "1:s:1", season, n, "E$n", StreamEmbedded("http://e/$id"))
        val seasons = assembleSeasons(
            listOf(SeasonEntity("1:s:1", 1, "Pilot")),
            listOf(ep("b", 1, 2), ep("a", 1, 1), ep("c", 2, 1)),
        )
        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals("Pilot", seasons[0].name)
        assertEquals("Season 2", seasons[1].name)
        assertEquals(listOf("a", "b"), seasons[0].episodes.map { it.id })
        assertEquals(emptyList<Any>(), assembleSeasons(emptyList(), emptyList()))
    }
}
