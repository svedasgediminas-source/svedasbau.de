package app.novaiptv.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {
    @Test fun acceptsHttpAndHttps() {
        assertTrue(UrlValidator.validateSourceUrl("https://example.com/list.m3u") is AppResult.Ok)
        assertTrue(UrlValidator.validateSourceUrl("http://10.0.0.2:8080/get.php?a=b") is AppResult.Ok)
    }

    @Test fun rejectsDangerousSchemes() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "content://x/y", "ftp://a.b/c", "", "   ", "http://")
            .forEach { assertTrue(it, UrlValidator.validateSourceUrl(it) is AppResult.Err) }
    }

    @Test fun streamAllowsRtmpButNotFile() {
        assertTrue(UrlValidator.isValidStreamUrl("rtmp://host/app/stream"))
        assertFalse(UrlValidator.isValidStreamUrl("file:///sdcard/a.ts"))
    }

    @Test fun normalizesServerUrl() {
        assertEquals(AppResult.Ok("http://portal.example:8080"), UrlValidator.normalizeServerUrl(" portal.example:8080/ "))
    }
}

class RedactTest {
    @Test fun redactsQueryAndPathCredentials() {
        assertEquals(
            "http://h:80/get.php?username=***&password=***&type=m3u",
            Redact.url("http://h:80/get.php?username=bob&password=s3cret&type=m3u"),
        )
        assertEquals("http://h/live/***/***/42.ts", Redact.url("http://h/live/bob/s3cret/42.ts"))
        assertEquals("http://***@h/x", Redact.url("http://bob:pw@h/x"))
    }
}
