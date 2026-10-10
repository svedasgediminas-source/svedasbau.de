package app.novaiptv.core.data.db

import androidx.room.Dao
import androidx.room.Query

/** One distinct XMLTV id in epg_programs with a sample programme title and its programme count. */
data class EpgIdRow(
    val epgId: String,
    val sampleTitle: String?,
    val programmeCount: Int,
)

/** Read-only queries over the stored guide data for manual EPG mapping. */
@Dao
interface EpgMappingDao {
    /** [pattern] is a LIKE pattern escaped with '\' (see SearchText.escapeLike); "%" lists all ids. */
    @Query(
        "SELECT epgId, MIN(title) AS sampleTitle, COUNT(*) AS programmeCount FROM epg_programs " +
            "WHERE epgId LIKE :pattern ESCAPE '\\' GROUP BY epgId ORDER BY epgId LIMIT :limit",
    )
    suspend fun searchEpgIds(pattern: String, limit: Int): List<EpgIdRow>
}
