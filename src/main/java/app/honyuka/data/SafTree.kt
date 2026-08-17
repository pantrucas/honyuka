package app.honyuka.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

data class SafEntry(
    val documentId: String,
    val name: String,
    val mimeType: String,
) {
    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

/**
 * Reads a SAF document tree one directory at a time.
 *
 * DocumentFile charges a separate ContentProvider query for every property access — name, type,
 * existence — and findFile() pays one of those per child on top of the listing. A single cursor
 * per directory carries all three columns at once, which is what keeps the library scan linear.
 */
class SafTreeReader(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) {

    fun rootDocumentId(): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    fun listChildren(documentId: String): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = runCatching {
            resolver.query(childrenUri, PROJECTION, null, null, null)
        }.getOrNull() ?: return emptyList()

        val entries = mutableListOf<SafEntry>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                entries += SafEntry(
                    documentId = id,
                    name = name,
                    mimeType = it.getString(2).orEmpty(),
                )
            }
        }
        return entries
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}

fun List<SafEntry>.sortedByNaturalName(): List<SafEntry> {
    return sortedWith { left, right -> naturalCompare(left.name, right.name) }
}

fun naturalCompare(left: String, right: String): Int {
    val leftParts = NATURAL_PARTS.findAll(left).map { it.value }.toList()
    val rightParts = NATURAL_PARTS.findAll(right).map { it.value }.toList()
    val maxSize = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until maxSize) {
        val a = leftParts.getOrNull(index) ?: return -1
        val b = rightParts.getOrNull(index) ?: return 1
        val result = when {
            a.all(Char::isDigit) && b.all(Char::isDigit) -> compareDigitStrings(a, b)
            else -> a.compareTo(b, ignoreCase = true)
        }
        if (result != 0) return result
    }
    return 0
}

private fun compareDigitStrings(left: String, right: String): Int {
    val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
    val normalizedRight = right.trimStart('0').ifEmpty { "0" }
    return when {
        normalizedLeft.length != normalizedRight.length -> normalizedLeft.length.compareTo(normalizedRight.length)
        else -> normalizedLeft.compareTo(normalizedRight)
    }
}

private val NATURAL_PARTS = Regex("(\\d+|\\D+)")
