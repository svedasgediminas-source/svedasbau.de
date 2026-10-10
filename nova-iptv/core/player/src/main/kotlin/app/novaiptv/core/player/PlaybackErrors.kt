package app.novaiptv.core.player

import androidx.media3.common.PlaybackException
import app.novaiptv.core.model.PlaybackErrorKind

/**
 * Maps Media3 [PlaybackException.errorCode] values to user-facing [PlaybackErrorKind]s.
 * Takes a plain Int so it can be unit-tested without a Media3 runtime (the constants are
 * compile-time ints and get inlined).
 *
 * DRM errors are only reported; there is no attempt to work around protection.
 */
object PlaybackErrors {

    fun classify(errorCode: Int): PlaybackErrorKind = when (errorCode) {
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> PlaybackErrorKind.Network

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        -> PlaybackErrorKind.SourceUnavailable

        else -> when (errorCode) {
            // 2xxx: any other IO error.
            in IO_RANGE -> PlaybackErrorKind.Network
            // 3xxx: container / manifest parsing.
            in PARSING_RANGE -> PlaybackErrorKind.UnsupportedFormat
            // 4xxx: decoders, 5xxx: audio track output.
            in DECODER_RANGE, in AUDIO_RANGE -> PlaybackErrorKind.Decoder
            // 6xxx: DRM.
            in DRM_RANGE -> PlaybackErrorKind.DrmProtected
            else -> PlaybackErrorKind.Unknown
        }
    }

    /** The live window moved past the playback position: rejoin the live edge, not a failure. */
    fun isBehindLiveWindow(errorCode: Int): Boolean =
        errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW

    private val IO_RANGE = 2000..2999
    private val PARSING_RANGE = 3000..3999
    private val DECODER_RANGE = 4000..4999
    private val AUDIO_RANGE = 5000..5999
    private val DRM_RANGE = 6000..6999
}
