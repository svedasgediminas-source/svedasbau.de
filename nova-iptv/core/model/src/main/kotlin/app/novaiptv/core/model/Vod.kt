package app.novaiptv.core.model

data class VodCategory(val playlistId: Long, val id: String, val name: String)

data class Movie(
    val id: String,
    val playlistId: Long,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val stream: StreamSource,
    val year: Int? = null,
    val plot: String? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val durationSeconds: Int? = null,
    val addedAtMillis: Long? = null,
)

data class Series(
    val id: String,
    val playlistId: Long,
    val name: String,
    val categoryId: String?,
    val posterUrl: String?,
    val plot: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val addedAtMillis: Long? = null,
)

data class Season(
    val seriesId: String,
    val number: Int,
    val name: String,
    val episodes: List<Episode>,
)

data class Episode(
    val id: String,
    val seriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val stream: StreamSource,
    val plot: String? = null,
    val durationSeconds: Int? = null,
    val thumbnailUrl: String? = null,
)
