package app.novaiptv.core.common

/** Hard limits on imported data to keep memory bounded on low-end TV boxes. */
object Limits {
    const val MAX_PLAYLIST_BYTES: Long = 100L * 1024 * 1024
    const val MAX_EPG_BYTES: Long = 300L * 1024 * 1024
    const val MAX_XTREAM_RESPONSE_BYTES: Long = 150L * 1024 * 1024
    const val MAX_CHANNELS_PER_PLAYLIST: Int = 200_000
    const val MAX_LINE_LENGTH: Int = 64 * 1024
    const val MAX_NAME_LENGTH: Int = 512
    const val MAX_URL_LENGTH: Int = 8 * 1024
    const val CONNECT_TIMEOUT_SECONDS: Long = 15
    const val READ_TIMEOUT_SECONDS: Long = 60
}
