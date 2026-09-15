package `in`.smartie.quotedesk.data.mapping

import java.math.BigDecimal
import java.util.Date

/**
 * The PWA's "deliberately unset" marker (`NULLP` in `index.html`). It must
 * never become 0.
 */
const val UNSET_MARKER = "∅"

/**
 * Tolerant readers for values written by five years of PWA versions.
 *
 * Legacy documents hold numbers as strings ("5", "1,234.50"), booleans as
 * 0/1, and timestamps as Long, Double or Date. The beta app used strict
 * `as? Number` / `as? Boolean` casts, so all of those silently became 0 or
 * false — the cause of most "missing data" symptoms in the parity audit.
 */
fun Any?.asDoubleOrNull(): Double? = when (this) {
    null -> null
    is Double -> takeIf { !it.isNaN() && !it.isInfinite() }
    is Float -> toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
    is Number -> toDouble()
    is Boolean -> if (this) 1.0 else 0.0
    is String -> {
        val cleaned = trim()
            .removePrefix("₹")
            .replace(",", "")
            .replace(" ", "")
            .trim()
        when {
            cleaned.isEmpty() -> null
            cleaned == UNSET_MARKER -> null
            else -> cleaned.toDoubleOrNull()
        }
    }
    else -> null
}

fun Any?.asDouble(default: Double = 0.0): Double = asDoubleOrNull() ?: default

fun Any?.asIntOrNull(): Int? = asDoubleOrNull()?.let {
    if (it.isFinite()) it.toInt() else null
}

fun Any?.asInt(default: Int = 0): Int = asIntOrNull() ?: default

/** `true`, `1`, `"1"`, `"true"`, `"yes"` all mean true; unknown values are null. */
fun Any?.asBoolOrNull(): Boolean? = when (this) {
    null -> null
    is Boolean -> this
    is Number -> toDouble() != 0.0
    is String -> when (trim().lowercase()) {
        "1", "true", "yes", "y", "on" -> true
        "0", "false", "no", "n", "off", "" -> false
        else -> null
    }
    else -> null
}

fun Any?.asBool(default: Boolean = false): Boolean = asBoolOrNull() ?: default

/** Epoch milliseconds from a Long, Double, numeric string or Date. */
fun Any?.asMillisOrNull(): Long? = when (this) {
    null -> null
    is Date -> time
    is Number -> {
        val value = toDouble()
        if (value.isFinite() && value > 0) value.toLong() else null
    }
    is String -> trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0 }?.toLong()
    else -> null
}

fun Any?.asMillis(default: Long = 0L): Long = asMillisOrNull() ?: default

fun Any?.asStringOrNull(): String? = when (this) {
    null -> null
    is String -> trim().takeIf { it.isNotEmpty() && it != UNSET_MARKER }
    is Number -> BigDecimal(toString()).stripTrailingZeros().toPlainString()
    is Boolean -> toString()
    else -> null
}

fun Any?.asString(default: String = ""): String = asStringOrNull() ?: default

@Suppress("UNCHECKED_CAST")
fun Any?.asMapOrNull(): Map<String, Any?>? = (this as? Map<*, *>)?.entries
    ?.associate { (key, value) -> key.toString() to value }

fun Any?.asList(): List<Any?> = when (this) {
    null -> emptyList()
    is List<*> -> this
    is Array<*> -> toList()
    else -> listOf(this)
}

fun Any?.asStringList(): List<String> = asList().mapNotNull { it.asStringOrNull() }

// Convenience readers on a whole document.

fun DocData.double(vararg names: String, default: Double = 0.0): Double =
    first(*names).asDoubleOrNull() ?: default

/** Prices: a missing price is "not set", never zero (audit P2). */
fun DocData.optionalDouble(vararg names: String): Double? = first(*names).asDoubleOrNull()

fun DocData.int(vararg names: String, default: Int = 0): Int =
    first(*names).asIntOrNull() ?: default

fun DocData.bool(vararg names: String, default: Boolean = false): Boolean =
    first(*names).asBoolOrNull() ?: default

fun DocData.millis(vararg names: String, default: Long = 0L): Long =
    first(*names).asMillisOrNull() ?: default

fun DocData.string(vararg names: String, default: String = ""): String =
    first(*names).asStringOrNull() ?: default

fun DocData.stringOrNull(vararg names: String): String? = first(*names).asStringOrNull()

fun DocData.map(name: String): Map<String, Any?> = this[name].asMapOrNull() ?: emptyMap()
