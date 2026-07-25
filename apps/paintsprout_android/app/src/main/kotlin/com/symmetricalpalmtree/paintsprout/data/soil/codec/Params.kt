package com.symmetricalpalmtree.paintsprout.data.soil.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The `params` column: small, closed parameter bags that SQL never filters on.
 *
 * Everything else in a document row is a typed column, and that is the rule —
 * *promote a field to a column when the database has to answer a question about
 * it; leave it in the payload when only the app cares*. Three types qualify for
 * the payload: a page's surface parameters (seven different shapes of them), a
 * surface op's copy of the same, and the palette's pigment recipes. None of them
 * is ever a `WHERE` clause; all of them change shape as the app grows.
 *
 * **Reading is total.** Every accessor takes a default and returns it for a
 * missing key, a wrong-typed value, or a payload that is not JSON at all. A
 * parameter bag from a newer build carries keys this one has never heard of and
 * is missing none that matter; a bag from an older build is missing the newest
 * ones. Both have to render a page.
 */
class Params private constructor(private val values: Map<String, JsonElement>) {

    val keys: Set<String> get() = values.keys

    val isEmpty: Boolean get() = values.isEmpty()

    private fun primitive(key: String): JsonPrimitive? = values[key] as? JsonPrimitive

    fun float(key: String, default: Float): Float = primitive(key)?.floatOrNull ?: default
    fun int(key: String, default: Int): Int = primitive(key)?.intOrNull ?: default
    fun long(key: String, default: Long): Long = primitive(key)?.longOrNull ?: default
    fun boolean(key: String, default: Boolean): Boolean = primitive(key)?.booleanOrNull ?: default

    fun string(key: String, default: String): String {
        val p = primitive(key) ?: return default
        return if (p.isString) p.content else default
    }

    /** A colour is stored as its ARGB int, which survives JSON exactly. */
    fun color(key: String, default: Int): Int = int(key, default)

    fun encode(): String = Json.encodeToString(JsonObject.serializer(), JsonObject(values))

    override fun toString(): String = encode()

    override fun equals(other: Any?): Boolean = other is Params && other.values == values

    override fun hashCode(): Int = values.hashCode()

    companion object {

        val EMPTY = Params(emptyMap())

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun of(vararg pairs: Pair<String, Any?>): Params =
            Params(pairs.mapNotNull { (k, v) -> primitiveOf(v)?.let { k to it } }.toMap())

        fun of(values: Map<String, Any?>): Params =
            Params(values.mapNotNull { (k, v) -> primitiveOf(v)?.let { k to it } }.toMap())

        /** Never throws. A payload that will not parse is simply an empty bag. */
        fun decode(text: String?): Params {
            if (text.isNullOrBlank()) return EMPTY
            return try {
                Params(json.parseToJsonElement(text).jsonObject.toMap())
            } catch (t: Throwable) {
                EMPTY
            }
        }

        private fun primitiveOf(value: Any?): JsonElement? = when (value) {
            null -> null
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
}
