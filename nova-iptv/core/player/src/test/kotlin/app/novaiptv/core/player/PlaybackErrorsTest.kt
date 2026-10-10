package app.novaiptv.core.player

import androidx.media3.common.PlaybackException
import app.novaiptv.core.model.PlaybackErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorsTest {

    private fun assertKind(expected: PlaybackErrorKind, vararg codes: Int) {
        for (code in codes) assertEquals("code $code", expected, PlaybackErrors.classify(code))
    }

    @Test
    fun networkErrors() = assertKind(
        PlaybackErrorKind.Network,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
    )

    @Test
    fun sourceUnavailable() = assertKind(
        PlaybackErrorKind.SourceUnavailable,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    )

    @Test
    fun unsupportedFormat() = assertKind(
        PlaybackErrorKind.UnsupportedFormat,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    )

    @Test
    fun decoder() = assertKind(
        PlaybackErrorKind.Decoder,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    @Test
    fun drm() = assertKind(
        PlaybackErrorKind.DrmProtected,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
    )

    @Test
    fun unknown() = assertKind(
        PlaybackErrorKind.Unknown,
        PlaybackException.ERROR_CODE_UNSPECIFIED,
        PlaybackException.ERROR_CODE_REMOTE_ERROR,
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        PlaybackException.CUSTOM_ERROR_CODE_BASE,
        -1,
    )

    @Test
    fun behindLiveWindowIsDetected() {
        assertTrue(PlaybackErrors.isBehindLiveWindow(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertFalse(PlaybackErrors.isBehindLiveWindow(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }
}
