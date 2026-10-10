package app.novaiptv.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamUrlDetectorTest {
    @Test fun detectsGetPhpWithPort() {
        val c = XtreamUrlDetector.detect("http://tv.example.com:8080/get.php?username=alice&password=s3cret&type=m3u_plus&output=ts")
        assertNotNull(c)
        assertEquals("http://tv.example.com:8080", c!!.serverUrl)
        assertEquals("alice", c.username)
        assertEquals("s3cret", c.password)
    }

    @Test fun detectsHttpsWithoutPortAndParamOrder() {
        val c = XtreamUrlDetector.detect("https://Example.org/get.php?type=m3u&password=pw&username=bob")!!
        assertEquals("https://Example.org", c.serverUrl)
        assertEquals("bob", c.username)
        assertEquals("pw", c.password)
    }

    @Test fun detectsPlayerApiAndXmltv() {
        assertEquals("u", XtreamUrlDetector.detect("http://h.tv/player_api.php?username=u&password=p")!!.username)
        assertEquals("http://h.tv:25461", XtreamUrlDetector.detect("http://h.tv:25461/xmltv.php?username=u&password=p")!!.serverUrl)
        assertNotNull(XtreamUrlDetector.detect("http://h.tv/GET.PHP?USERNAME=u&Password=p"))
    }

    @Test fun keepsPathPrefix() {
        assertEquals("http://h.tv/iptv/panel", XtreamUrlDetector.detect("http://h.tv/iptv/panel/get.php?username=u&password=p")!!.serverUrl)
    }

    @Test fun decodesUrlEncodedCredentials() {
        val c = XtreamUrlDetector.detect("http://h.tv/get.php?username=j%C3%BCrgen%40mail&password=a%26b%3Dc%20d")!!
        assertEquals("jürgen@mail", c.username)
        assertEquals("a&b=c d", c.password)
    }

    @Test fun keepsLiteralPlus() {
        assertEquals("a+b", XtreamUrlDetector.detect("http://h.tv/get.php?username=u&password=a+b")!!.password)
    }

    @Test fun missingOrEmptyParamsReturnNull() {
        assertNull(XtreamUrlDetector.detect("http://h.tv/get.php?username=u"))
        assertNull(XtreamUrlDetector.detect("http://h.tv/get.php?password=p"))
        assertNull(XtreamUrlDetector.detect("http://h.tv/get.php?username=&password=p"))
        assertNull(XtreamUrlDetector.detect("http://h.tv/get.php?username=u&password="))
        assertNull(XtreamUrlDetector.detect("http://h.tv/get.php"))
    }

    @Test fun otherUrlsReturnNull() {
        assertNull(XtreamUrlDetector.detect("http://h.tv/playlist.m3u?username=u&password=p"))
        assertNull(XtreamUrlDetector.detect("http://h.tv/get.php.m3u?username=u&password=p"))
        assertNull(XtreamUrlDetector.detect("ftp://h.tv/get.php?username=u&password=p"))
        assertNull(XtreamUrlDetector.detect("not a url"))
        assertNull(XtreamUrlDetector.detect(""))
        assertNull(XtreamUrlDetector.detect("http://h.tv/get.php?username=u&password=%ZZ"))
    }

    @Test fun toStringHidesPassword() {
        val c = XtreamUrlDetector.detect("http://h.tv/get.php?username=alice&password=topsecret")!!
        assertFalse(c.toString().contains("topsecret"))
        assertFalse(c.toString().contains("alice"))
    }
}
