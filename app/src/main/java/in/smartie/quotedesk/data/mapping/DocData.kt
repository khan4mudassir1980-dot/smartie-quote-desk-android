package `in`.smartie.quotedesk.data.mapping

/**
 * A Firestore document reduced to its id and a plain field map.
 *
 * Every mapper in this package works on [DocData] rather than on
 * `DocumentSnapshot`, so the whole legacy-tolerance layer is ordinary Kotlin
 * that unit tests can exercise without Firebase or an emulator.
 */
data class DocData(
    val id: String,
    val fields: Map<String, Any?> = emptyMap()
) {
    operator fun get(name: String): Any? = fields[name]

    /** First present value among [names]; legacy documents rename fields. */
    fun first(vararg names: String): Any? =
        names.firstNotNullOfOrNull { name -> fields[name]?.takeUnless { it is String && it.isBlank() } }

    fun has(name: String): Boolean = fields.containsKey(name)
}
