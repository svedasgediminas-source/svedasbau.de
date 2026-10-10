package app.novaiptv.core.data.logic

import java.text.Normalizer
import java.util.Locale

/**
 * Text normalisation for LIKE-based search. Names are stored normalised (lowercase, no
 * diacritics, single spaces) in a dedicated column and queries are normalised the same way.
 */
object SearchText {
    private val COMBINING = Regex("""\p{Mn}+""")
    private val SPACES = Regex("""\s+""")

    /** Escape character used in every `LIKE ... ESCAPE '\'` query. */
    const val ESCAPE_CHAR = '\\'

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING, "")
            .lowercase(Locale.ROOT)
            .replace(SPACES, " ")
            .trim()

    /** Escapes LIKE wildcards ('%', '_') and the escape character itself. */
    fun escapeLike(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            if (c == ESCAPE_CHAR || c == '%' || c == '_') sb.append(ESCAPE_CHAR)
            sb.append(c)
        }
        return sb.toString()
    }

    /** "%query%" pattern on the normalised query, or null when the query is blank. */
    fun containsPattern(query: String): String? {
        val q = normalize(query)
        return if (q.isEmpty()) null else "%" + escapeLike(q) + "%"
    }

    /** "query%" pattern used to rank prefix matches first. */
    fun prefixPattern(query: String): String = escapeLike(normalize(query)) + "%"

    /** Pattern for columns that are not normalised (e.g. EPG titles; SQLite LIKE is ASCII case-insensitive). */
    fun rawContainsPattern(query: String): String? {
        val q = query.trim().replace(SPACES, " ")
        return if (q.isEmpty()) null else "%" + escapeLike(q) + "%"
    }
}
