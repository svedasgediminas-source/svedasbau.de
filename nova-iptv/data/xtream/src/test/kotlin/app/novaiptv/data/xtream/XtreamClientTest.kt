package app.novaiptv.data.xtream

import app.novaiptv.core.common.AppError
import app.novaiptv.core.common.AppResult
import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.CatchupType
import app.novaiptv.core.model.StreamFormat
import app.novaiptv.core.model.XtreamCredentials
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class XtreamClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val client = XtreamClient(http)

    private val user = "alice"
    private val pass = "S3cr3t&p@ss"
    private val routes = HashMap<String, MockResponse>()

    private fun creds() = XtreamCredentials(server.url("/").toString(), user, pass)

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.url.queryParameter("action") ?: "auth"
                return routes[action] ?: MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun <T> AppResult<T>.ok(): T {
        assertTrue("expected Ok but was $this", this is AppResult.Ok)
        return (this as AppResult.Ok).value
    }

    private fun AppResult<*>.err(): AppError {
        assertTrue("expected Err but was $this", this is AppResult.Err)
        return (this as AppResult.Err).error
    }

    private val authOk = """
        {"user_info":{"username":"alice","password":"S3cr3t&p@ss","auth":1,"status":"Active","exp_date":"1893456000",
         "is_trial":"0","active_cons":"1","max_connections":"2","allowed_output_formats":["m3u8","ts","rtmp"]},
         "server_info":{"url":"example.com","port":"80","timezone":"Europe/Vilnius"}}
    """.trimIndent()

    // ---- authentication -------------------------------------------------------------------

    @Test
    fun validLogin() = runTest {
        routes["auth"] = ok(authOk)
        val acc = client.authenticate(creds()).ok()
        assertEquals("Active", acc.status)
        assertEquals(1893456000_000L, acc.expiresAtMillis)
        assertFalse(acc.isTrial)
        assertEquals(1, acc.activeConnections)
        assertEquals(2, acc.maxConnections)
        assertEquals(listOf("m3u8", "ts", "rtmp"), acc.allowedOutputFormats)
        assertEquals("Europe/Vilnius", acc.serverTimezone)
        assertEquals(server.url("/").toString().trimEnd('/'), acc.serverUrl)

        val req = server.takeRequest()
        assertEquals("/player_api.php", req.url.encodedPath)
        assertEquals(user, req.url.queryParameter("username"))
        assertEquals(pass, req.url.queryParameter("password"))
        // Special characters are URL-encoded, not injected as extra parameters.
        assertTrue(req.url.encodedQuery!!.contains("S3cr3t%26p%40ss"))
        assertNull(req.url.queryParameter("action"))
    }

    @Test
    fun wrongPasswordAuthZero() = runTest {
        routes["auth"] = ok("""{"user_info":{"auth":0}}""")
        assertEquals(AppError.AuthFailed, client.authenticate(creds()).err())
    }

    @Test
    fun wrongPasswordEmptyArray() = runTest {
        routes["auth"] = ok("[]")
        assertEquals(AppError.AuthFailed, client.authenticate(creds()).err())
    }

    @Test
    fun wrongPasswordEmptyBody() = runTest {
        routes["auth"] = ok("")
        assertEquals(AppError.AuthFailed, client.authenticate(creds()).err())
    }

    @Test
    fun wrongPassword401() = runTest {
        routes["auth"] = MockResponse.Builder().code(401).body("Unauthorized").build()
        assertEquals(AppError.AuthFailed, client.authenticate(creds()).err())
    }

    @Test
    fun forbidden403OnCatalogue() = runTest {
        routes["get_vod_streams"] = MockResponse.Builder().code(403).build()
        assertEquals(AppError.AuthFailed, client.movies(creds(), 1).err())
    }

    @Test
    fun expiredAccount() = runTest {
        routes["auth"] = ok("""{"user_info":{"auth":"1","status":"Expired","exp_date":"1000"},"server_info":{}}""")
        assertEquals(AppError.AccountExpired, client.authenticate(creds()).err())
        routes["auth"] = ok("""{"user_info":{"auth":1,"status":"Banned"}}""")
        assertEquals(AppError.AccountExpired, client.authenticate(creds()).err())
    }

    @Test
    fun serverDownIsNetwork() = runTest {
        val c = creds()
        server.close()
        assertTrue(client.authenticate(c).err() is AppError.Network)
    }

    @Test
    fun unreachablePortIsNetwork() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val c = XtreamCredentials("http://127.0.0.1:$port", user, pass)
        val e = client.authenticate(c).err()
        assertTrue(e is AppError.Network)
        assertSafe(e)
    }

    @Test
    fun htmlErrorPageIsParse() = runTest {
        routes["auth"] = ok("<html><body><h1>502 Bad Gateway</h1></body></html>")
        val e = client.authenticate(creds()).err()
        assertTrue(e is AppError.Parse)
        assertSafe(e)
    }

    @Test
    fun largeArrayCatalogueIsStreamedAndSkipsBadItems() = runTest {
        val items = (1..20_000).joinToString(",") { i ->
            if (i % 1000 == 0) "\"garbage\"" else """{"stream_id":$i,"name":"Movie $i","container_extension":"mkv"}"""
        }
        routes["get_vod_streams"] = ok("[$items]")
        val movies = client.movies(creds(), 7).ok()
        assertEquals(19_980, movies.size)
        assertEquals("7:m:1", movies.first().id)
        assertTrue(movies.none { it.stream.url.contains(pass) })
    }

    @Test
    fun objectShapedCatalogueStillMaps() = runTest {
        routes["get_vod_streams"] = ok("""{"0":{"stream_id":1,"name":"A"},"1":{"stream_id":2,"name":"B"}}""")
        assertEquals(listOf("1:m:1", "1:m:2"), client.movies(creds(), 1).ok().map { it.id })
    }

    @Test
    fun truncatedJsonIsParse() = runTest {
        routes["get_vod_streams"] = ok("""[{"stream_id":1,"name":"A" """)
        val e = client.movies(creds(), 1).err()
        assertTrue(e is AppError.Parse)
        assertSafe(e)
    }

    @Test
    fun httpErrorDetailIsRedacted() = runTest {
        routes["get_series"] = MockResponse.Builder().code(500).build()
        val e = client.series(creds(), 1).err()
        assertTrue(e is AppError.Http)
        assertEquals(500, (e as AppError.Http).code)
        assertSafe(e)
    }

    @Test
    fun invalidServerUrl() = runTest {
        val e = client.authenticate(XtreamCredentials("javascript:alert(1)", user, pass)).err()
        assertTrue(e is AppError.InvalidInput)
    }

    // ---- catalogues -------------------------------------------------------------------------

    @Test
    fun emptyCatalogues() = runTest {
        routes["auth"] = ok(authOk)
        routes["get_live_categories"] = ok("[]")
        routes["get_live_streams"] = ok("[]")
        routes["get_vod_categories"] = ok("")
        routes["get_vod_streams"] = ok("[]")
        routes["get_series_categories"] = ok("[]")
        routes["get_series"] = ok("{}")
        routes["get_series_info"] = ok("[]")
        assertEquals(emptyList<Any>(), client.liveStreams(creds(), 1).ok())
        assertEquals(emptyList<Any>(), client.movieCategories(creds(), 1).ok())
        assertEquals(emptyList<Any>(), client.movies(creds(), 1).ok())
        assertEquals(emptyList<Any>(), client.seriesCategories(creds(), 1).ok())
        assertEquals(emptyList<Any>(), client.series(creds(), 1).ok())
        assertEquals(emptyList<Any>(), client.seasons(creds(), 1, "1:s:5").ok())
    }

    @Test
    fun liveMappingWithCatchupAndPlaceholders() = runTest {
        routes["auth"] = ok(authOk)
        routes["get_live_categories"] = ok(
            """[{"category_id":"10","category_name":"News","parent_id":0},
                {"category_id":11,"category_name":"XXX Adult"}]""",
        )
        routes["get_live_streams"] = ok(
            """[
              {"num":1,"name":"News 24","stream_type":"live","stream_id":101,"stream_icon":"http://img/1.png",
               "epg_channel_id":"news24.lt","category_id":"10","tv_archive":1,"tv_archive_duration":"7"},
              {"num":"2","name":"Sport","stream_id":"102","stream_icon":"","epg_channel_id":"",
               "category_id":10,"tv_archive":"1","tv_archive_duration":"0"},
              {"name":"Night","stream_id":103,"category_id":"11","tv_archive":"0","tv_archive_duration":5},
              {"name":"Flagged","stream_id":104,"category_id":"999","is_adult":"1"}
            ]""",
        )
        val channels = client.liveStreams(creds(), 7).ok()
        assertEquals(4, channels.size)
        val base = server.url("/").toString().trimEnd('/')

        val news = channels[0]
        assertEquals("7:101", news.id)
        assertEquals(1, news.number)
        assertEquals("News 24", news.name)
        assertEquals("News", news.groupName)
        assertEquals("http://img/1.png", news.logoUrl)
        assertEquals("news24.lt", news.epgId)
        assertEquals(CatchupCapability.Supported(CatchupType.Xtream, 7), news.catchup)
        assertEquals("$base/live/{xtream_user}/{xtream_pass}/101.m3u8", news.stream.url)
        assertEquals(StreamFormat.Hls, news.stream.mimeTypeHint)
        assertFalse(news.isAdult)

        val sport = channels[1]
        assertEquals(2, sport.number)
        assertNull(sport.logoUrl)
        assertNull(sport.epgId)
        assertEquals("News", sport.groupName)
        assertEquals(CatchupCapability.None, sport.catchup)

        val night = channels[2]
        assertEquals(3, night.number) // index fallback
        assertEquals(CatchupCapability.None, night.catchup)
        assertTrue(night.isAdult)
        assertEquals("XXX Adult", night.groupName)

        val flagged = channels[3]
        assertNull(flagged.groupName)
        assertTrue(flagged.isAdult)

        assertNoCredentials(channels)
    }

    @Test
    fun liveUsesTsWhenM3u8NotAllowed() = runTest {
        routes["auth"] = ok("""{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts"]}}""")
        routes["get_live_categories"] = ok("[]")
        routes["get_live_streams"] = ok("""[{"name":"A","stream_id":5}]""")
        val ch = client.liveStreams(creds(), 1).ok().single()
        assertTrue(ch.stream.url.endsWith("/live/{xtream_user}/{xtream_pass}/5.ts"))
        assertEquals(StreamFormat.MpegTs, ch.stream.mimeTypeHint)
    }

    @Test
    fun liveStreamsPropagatesAuthFailure() = runTest {
        routes["auth"] = ok("""{"user_info":{"auth":0}}""")
        assertEquals(AppError.AuthFailed, client.liveStreams(creds(), 1).err())
    }

    @Test
    fun oddDataIsTolerated() = runTest {
        routes["get_vod_streams"] = ok(
            """[
              {"num":1,"name":"Movie A","stream_id":"55","stream_icon":"http://p/a.jpg","rating":"7.5","added":"1700000000",
               "category_id":3,"container_extension":"mkv","year":"2021"},
              {"name":"No id","stream_id":null},
              {"name":"Blank id","stream_id":""},
              {"name":"Bad id","stream_id":"../../etc"},
              {"stream_id":60},
              "garbage",
              null,
              {"name":"Movie B","stream_id":61.0,"rating":"","added":"","category_id":null,"container_extension":null,"rating_5based":4}
            ]""",
        )
        val movies = client.movies(creds(), 2).ok()
        assertEquals(listOf("2:m:55", "2:m:61"), movies.map { it.id })
        val a = movies[0]
        assertEquals("Movie A", a.name)
        assertEquals("3", a.categoryId)
        assertEquals("http://p/a.jpg", a.posterUrl)
        assertEquals(7.5, a.rating!!, 0.0001)
        assertEquals(1_700_000_000_000L, a.addedAtMillis)
        assertEquals(2021, a.year)
        assertTrue(a.stream.url.endsWith("/movie/{xtream_user}/{xtream_pass}/55.mkv"))
        assertEquals(StreamFormat.Progressive, a.stream.mimeTypeHint)
        val b = movies[1]
        assertNull(b.rating)
        assertNull(b.addedAtMillis)
        assertNull(b.categoryId)
        assertTrue(b.stream.url.endsWith("/61.mp4"))
        assertNoCredentials(movies)
    }

    @Test
    fun categoriesIntAndStringIds() = runTest {
        routes["get_vod_categories"] = ok(
            """[{"category_id":1,"category_name":"Action"},{"category_id":"2","category_name":"Drama"},
                {"category_id":null,"category_name":"x"},{"category_id":"3"}]""",
        )
        val cats = client.movieCategories(creds(), 4).ok()
        assertEquals(listOf("1" to "Action", "2" to "Drama"), cats.map { it.id to it.name })
        assertTrue(cats.all { it.playlistId == 4L })
    }

    @Test
    fun seriesMapping() = runTest {
        routes["get_series_categories"] = ok("""[{"category_id":"20","category_name":"Shows"}]""")
        routes["get_series"] = ok(
            """[{"num":1,"name":"Show","series_id":9,"cover":"http://c/9.jpg","plot":"Plot","genre":"Drama",
                 "releaseDate":"2019-05-01","rating":"8","last_modified":"1700000000","category_id":"20"},
                {"name":"Missing id"}]""",
        )
        assertEquals("Shows", client.seriesCategories(creds(), 3).ok().single().name)
        val s = client.series(creds(), 3).ok().single()
        assertEquals("3:s:9", s.id)
        assertEquals("http://c/9.jpg", s.posterUrl)
        assertEquals("Plot", s.plot)
        assertEquals("Drama", s.genre)
        assertEquals(2019, s.year)
        assertEquals(8.0, s.rating!!, 0.0)
        assertEquals("20", s.categoryId)
        assertEquals(1_700_000_000_000L, s.addedAtMillis)
    }

    @Test
    fun seriesInfoMapShapedEpisodes() = runTest {
        routes["get_series_info"] = ok(
            """{"seasons":[{"season_number":1,"name":"Pilot season"},{"season_number":"10","name":""}],
                "info":{"name":"Show"},
                "episodes":{
                  "10":[{"id":"1002","episode_num":2,"title":"S10E2","container_extension":"mp4","info":{"duration_secs":"1500"}},
                        {"id":"1001","episode_num":"1","title":"S10E1","container_extension":"mkv","info":[]}],
                  "2":[{"id":"201","episode_num":1,"title":"S2E1","info":{"plot":"P","duration":"00:45:00","movie_image":"http://i/201.jpg"}}],
                  "1":[{"id":"101","episode_num":1,"title":"S1E1","container_extension":"avi","info":{"plot":"First"}},
                       {"episode_num":2,"title":"no id"}]
                }}""",
        )
        val seasons = client.seasons(creds(), 3, "3:s:9").ok()
        assertEquals("9", server.takeRequest().url.queryParameter("series_id"))
        assertEquals(listOf(1, 2, 10), seasons.map { it.number })
        assertEquals("Pilot season", seasons[0].name)
        assertEquals("Season 2", seasons[1].name)
        assertEquals("Season 10", seasons[2].name)
        assertEquals(listOf("3:e:1001", "3:e:1002"), seasons[2].episodes.map { it.id })
        val e1 = seasons[0].episodes.single()
        assertEquals("3:s:9", e1.seriesId)
        assertEquals("First", e1.plot)
        assertTrue(e1.stream.url.endsWith("/series/{xtream_user}/{xtream_pass}/101.avi"))
        val e2 = seasons[1].episodes.single()
        assertEquals(2700, e2.durationSeconds)
        assertEquals("http://i/201.jpg", e2.thumbnailUrl)
        assertEquals(1500, seasons[2].episodes[1].durationSeconds)
        assertNoCredentials(seasons)
    }

    @Test
    fun seriesInfoListShapedEpisodes() = runTest {
        routes["get_series_info"] = ok(
            """{"seasons":[],"info":{},
                "episodes":[
                  [{"id":11,"episode_num":1,"season":1,"title":"A"},{"id":12,"episode_num":2,"season":1}],
                  [{"id":21,"episode_num":1,"title":"B"}]
                ]}""",
        )
        val seasons = client.seasons(creds(), 5, "5:s:77").ok()
        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(listOf("A", "Episode 2"), seasons[0].episodes.map { it.title })
        assertEquals("5:e:21", seasons[1].episodes.single().id)
        assertEquals(2, seasons[1].episodes.single().seasonNumber)
    }

    @Test
    fun seasonsRejectsInvalidSeriesId() = runTest {
        assertTrue(client.seasons(creds(), 1, "1:m:5").err() is AppError.InvalidInput)
        assertTrue(client.seasons(creds(), 1, "1:s:../x").err() is AppError.InvalidInput)
    }

    @Test
    fun xmltvUrlHasEncodedCredentials() {
        val url = client.xmltvUrl(creds())
        assertTrue(url.startsWith(server.url("/").toString().trimEnd('/') + "/xmltv.php?"))
        assertTrue(url.contains("username=alice"))
        assertTrue(url.contains("password=S3cr3t%26p%40ss"))
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun assertSafe(e: AppError) {
        val text = "$e ${e.detail}"
        assertFalse("password leaked: $text", text.contains(pass) || text.contains("S3cr3t"))
        assertFalse("username leaked: $text", text.contains("username=alice"))
    }

    private fun assertNoCredentials(objects: Any) {
        val text = objects.toString()
        assertFalse("password leaked", text.contains("S3cr3t"))
        assertFalse("username leaked", text.contains("alice"))
    }
}
