package `in`.smartie.quotedesk.data.mapping

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Loads legacy document fixtures from `src/test/resources/fixtures`.
 *
 * The fixtures are synthesised from every variant the parity audit records in
 * section 5.2 and section 6. When a read-only production export is available
 * its documents can be dropped into the same folder and these tests re-run
 * unchanged.
 */
object Fixtures {

    fun load(name: String): List<DocData> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "Missing fixture file: fixtures/$name"
        }
        val root = stream.bufferedReader().use { JsonParser.parseReader(it) }
        return root.asJsonArray.map { element ->
            val doc = element.asJsonObject
            DocData(
                id = doc.get("id").asString,
                fields = doc.getAsJsonObject("fields").toFieldMap()
            )
        }
    }

    fun loadOne(name: String, id: String): DocData =
        load(name).firstOrNull { it.id == id } ?: error("Fixture $name has no document $id")

    private fun JsonObject.toFieldMap(): Map<String, Any?> =
        entrySet().associate { (key, value) -> key to value.toKotlin() }

    /**
     * Keeps whole numbers as Long and fractional ones as Double, so the
     * fixtures reproduce the Long/Double split real Firestore documents have.
     */
    private fun JsonElement.toKotlin(): Any? = when {
        isJsonNull -> null
        isJsonObject -> asJsonObject.toFieldMap()
        isJsonArray -> (this as JsonArray).map { it.toKotlin() }
        else -> {
            val primitive = asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asString.let { raw ->
                    if (raw.contains('.') || raw.contains('e') || raw.contains('E')) raw.toDouble()
                    else raw.toLong()
                }
                else -> primitive.asString
            }
        }
    }
}
