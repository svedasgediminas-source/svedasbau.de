package app.novaiptv.core.domain

import app.novaiptv.core.model.CatchupCapability
import app.novaiptv.core.model.Channel

/**
 * Slim channel row for long lists (Live TV, 50k–200k channels). Holds only what a list row needs:
 * no stream, catch-up template or extras, so loading it never decodes per-row JSON. The full
 * [Channel] is fetched with [ChannelRepository.getChannel] when it is actually needed (playback).
 *
 * [epgId] is the effective XMLTV id (manual override, then name-matched id, then tvg-id), the same
 * value [Channel.epgId] carries.
 */
data class ChannelListItem(
    val id: String,
    val playlistId: Long,
    val number: Int,
    val name: String,
    val groupName: String?,
    val logoUrl: String?,
    val epgId: String?,
    val hasCatchup: Boolean,
    val catchupDays: Int,
    val isAdult: Boolean,
)

fun Channel.toListItem(): ChannelListItem {
    val supported = catchup as? CatchupCapability.Supported
    return ChannelListItem(
        id = id,
        playlistId = playlistId,
        number = number,
        name = name,
        groupName = groupName,
        logoUrl = logoUrl,
        epgId = epgId,
        hasCatchup = supported != null,
        catchupDays = supported?.days ?: 0,
        isAdult = isAdult,
    )
}
