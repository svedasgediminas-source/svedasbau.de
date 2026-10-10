package app.novaiptv.data.xtream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lenient JSON configuration for Xtream portals, which are notoriously inconsistent. */
val XtreamJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/*
 * Tolerant accessors. Real servers send numbers as strings and vice versa, "" for missing
 * numbers, null, booleans as "1"/"0"/true, and arrays where objects are expected.
 */

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Primitive content as a trimmed string; null for JsonNull, objects, arrays and blank values. */
internal fun JsonElement?.str(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val s = p.content.trim()
    return s.ifEmpty { null }?.takeUnless { it.equals("null", ignoreCase = true) }
}

internal fun JsonElement?.long(): Long? {
    val s = str() ?: return null
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
}

internal fun JsonElement?.int(): Int? {
    val l = long() ?: return null
    return if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else null
}

internal fun JsonElement?.double(): Double? = str()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun JsonElement?.bool(): Boolean = when (str()?.lowercase()) {
    "1", "true", "yes" -> true
    else -> long()?.let { it != 0L } ?: false
}

/** Elements of an array, or the values of an object (some servers key lists by index). */
internal fun JsonElement?.items(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> values.toList()
    else -> emptyList()
}

/** Identifier: string or number; integral doubles ("12.0") are normalised. */
internal fun JsonElement?.id(): String? {
    val s = str() ?: return null
    val d = s.toDoubleOrNull()
    if (s.toLongOrNull() == null && d != null && d.isFinite() && d == Math.floor(d) && s.contains('.')) {
        return d.toLong().toString()
    }
    return s
}
