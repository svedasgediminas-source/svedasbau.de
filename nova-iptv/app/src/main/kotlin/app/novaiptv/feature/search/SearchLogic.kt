package app.novaiptv.feature.search

import app.novaiptv.core.model.Channel
import app.novaiptv.core.model.EpgProgram
import java.text.Normalizer

/** Query normalisation. Pure, no Android. */
object SearchQuery {
    const val MIN_LENGTH = 2
    const val DEBOUNCE_MILLIS = 300L

    private val whitespace = Regex("\\s+")
    private val marks = Regex("\\p{Mn}+")

    /** Trimmed, inner whitespace collapsed; this is what is sent to the repositories. */
    fun normalize(raw: String): String = raw.trim().replace(whitespace, " ")

    /** Case- and accent-insensitive form used for ranking ("Žalgiris" -> "zalgiris"). */
    fun fold(text: String): String =
        Normalizer.normalize(normalize(text), Normalizer.Form.NFD).replace(marks, "").lowercase()

    fun isSearchable(raw: String): Boolean = normalize(raw).length >= MIN_LENGTH
}

/** Ranking and merging of repository results. Pure, no Android. */
object SearchRanking {
    const val EXACT = 100
    const val PREFIX = 80
    const val WORD_PREFIX = 60
    const val CONTAINS = 40
    const val ALL_WORDS = 20
    const val NONE = 0

    private val wordSplit = Regex("[^\\p{L}\\p{N}]+")

    fun score(name: String, query: String): Int {
        val n = SearchQuery.fold(name)
        val q = SearchQuery.fold(query)
        if (q.isEmpty()) return NONE
        return when {
            n == q -> EXACT
            n.startsWith(q) -> PREFIX
            n.split(wordSplit).any { it.startsWith(q) } -> WORD_PREFIX
            n.contains(q) -> CONTAINS
            q.split(' ').all { it.isNotEmpty() && n.contains(it) } -> ALL_WORDS
            else -> NONE
        }
    }

    /**
     * Best matches first (exact, prefix, word prefix, substring, all words); ties keep the shorter
     * name first, then the repository order. Items with the same [idOf] are merged (first wins).
     */
    fun <T> rank(items: List<T>, query: String, idOf: (T) -> String, nameOf: (T) -> String): List<T> =
        items.distinctBy(idOf)
            .mapIndexed { index, item -> Ranked(item, score(nameOf(item), query), nameOf(item).length, index) }
            .sortedWith(compareByDescending<Ranked<T>> { it.score }.thenBy { it.length }.thenBy { it.index })
            .map { it.item }

    private data class Ranked<T>(val item: T, val score: Int, val length: Int, val index: Int)

    /**
     * Programmes that are live or upcoming and whose channel is known, live ones first, then by
     * start time; one entry per channel+start.
     */
    fun programmeHits(
        programs: List<EpgProgram>,
        channelsByEpgId: Map<String, Channel>,
        nowMillis: Long,
        limit: Int,
    ): List<ProgrammeHit> =
        programs.asSequence()
            .filter { it.endMillis > nowMillis }
            .mapNotNull { p -> channelsByEpgId[p.channelEpgId]?.let { ProgrammeHit(p, it, p.isLive(nowMillis)) } }
            .distinctBy { it.channel.id to it.program.startMillis }
            .sortedWith(compareByDescending<ProgrammeHit> { it.isLive }.thenBy { it.program.startMillis })
            .take(limit)
            .toList()

    /** Index of channels by EPG id; the first channel wins when several share an id. */
    fun indexByEpgId(channels: List<Channel>): Map<String, Channel> {
        val map = LinkedHashMap<String, Channel>()
        for (c in channels) {
            val id = c.epgId?.takeIf { it.isNotBlank() } ?: continue
            if (id !in map) map[id] = c
        }
        return map
    }
}

data class ProgrammeHit(val program: EpgProgram, val channel: Channel, val isLive: Boolean) {
    fun progress(nowMillis: Long): Float? {
        if (!isLive || program.durationMillis <= 0) return null
        return ((nowMillis - program.startMillis).toFloat() / program.durationMillis).coerceIn(0f, 1f)
    }
}

/** In-memory recent search list. Pure, no Android. */
object RecentSearches {
    const val MAX = 8

    fun add(current: List<String>, raw: String, max: Int = MAX): List<String> {
        val q = SearchQuery.normalize(raw)
        if (q.length < SearchQuery.MIN_LENGTH) return current
        val folded = SearchQuery.fold(q)
        return (listOf(q) + current.filterNot { SearchQuery.fold(it) == folded }).take(max)
    }

    /** Encoding for rememberSaveable (one entry per line). */
    fun encode(list: List<String>): String = list.joinToString("\n")
    fun decode(raw: String): List<String> = raw.split('\n').filter { it.isNotBlank() }
}
