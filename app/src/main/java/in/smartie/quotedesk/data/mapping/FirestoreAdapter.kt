package `in`.smartie.quotedesk.data.mapping

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot

/**
 * The only place Firestore types meet the mappers. Firestore `Timestamp`
 * values are normalised to `java.util.Date` and `Blob` to `ByteArray` here,
 * so everything downstream is plain Kotlin and testable off-device.
 */
fun DocumentSnapshot.toDocData(): DocData = DocData(id, normaliseMap(data.orEmpty()))

fun QuerySnapshot.toDocDataList(): List<DocData> = documents.map { it.toDocData() }

private fun normaliseMap(source: Map<String, Any?>): Map<String, Any?> =
    source.mapValues { (_, value) -> normalise(value) }

private fun normalise(value: Any?): Any? = when (value) {
    is Timestamp -> value.toDate()
    // A stock photo arrives as a Blob; downstream only ever wants the bytes.
    is Blob -> value.toBytes()
    is Map<*, *> -> value.entries.associate { (key, nested) -> key.toString() to normalise(nested) }
    is List<*> -> value.map { normalise(it) }
    else -> value
}
