package app.novaiptv.core.model

data class EpgProgram(
    val channelEpgId: String,
    /** Epoch millis, UTC. Time zones are resolved during parsing. */
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
    val episodeNumber: String? = null,
) {
    fun isLive(nowMillis: Long): Boolean = nowMillis in startMillis until endMillis
    val durationMillis: Long get() = endMillis - startMillis
}

data class EpgChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String? = null,
)

/** Current and next programme for a channel, used by Live TV and Home "Now on". */
data class NowNext(val now: EpgProgram?, val next: EpgProgram?)
