package app.honyuka.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import app.honyuka.model.ChapterEntry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the scan against a synthetic 300-chapter library served by a fake DocumentsProvider,
 * shaped like the reported problem case: one manga folder holding well over 200 CBZ archives.
 *
 * The load-bearing assertion is the query counter. Wall-clock timing is noisy, but the number of
 * ContentProvider round trips is exact, and it is the thing that used to grow with the chapter
 * count instead of the directory count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterScanTest {

    private lateinit var context: Context
    private lateinit var storage: HonyukaStorage

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FakeDocumentsProvider.reset(buildSyntheticLibrary())
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(AUTHORITY)

        val preferences = HonyukaPreferences(context)
        preferences.saveRootUri(DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID))
        storage = HonyukaStorage(context, preferences)
    }

    @Test
    fun `scan finds every chapter across sources and manga`() {
        val chapters = scan().chapters()

        assertEquals(300, chapters.size)
        assertEquals(
            listOf("SourceA", "SourceB", "SourceC", "SourceD"),
            chapters.map { it.sourceName }.distinct(),
        )
        assertEquals(220, chapters.count { it.mangaName == "Big Manga" })
        assertEquals(30, chapters.count { it.mangaName == "Folder Manga" })
        assertEquals(25, chapters.count { it.mangaName == "Mixed Manga" })
    }

    @Test
    fun `scan stays linear in the number of directories`() {
        scan()

        // 1 root + 1 translations + 1 downloads + 4 sources + 1 translations source
        //   + 5 manga + 1 translations manga + 1 thumbnail = 15
        val queries = FakeDocumentsProvider.queryCount
        assertTrue(
            "Expected a linear scan but the provider was queried $queries times",
            queries <= 20,
        )
    }

    @Test
    fun `scan emits one batch per manga before finishing`() {
        val updates = scan()

        val batches = updates.filterIsInstance<ScanUpdate.Batch>()
        assertEquals(5, batches.size)
        assertEquals(
            listOf("Big Manga", "Folder Manga", "Mixed Manga", "Manga One", "Manga Two"),
            batches.map { it.mangaName },
        )

        // The first manga is reported long before the scan completes, which is what lets the list
        // populate instead of sitting empty until the end.
        assertEquals(220, batches.first().chapters.size)
        assertEquals(220, batches.first().totalSoFar)
        assertTrue(batches.first().progress > 0f)

        assertTrue(updates.last() is ScanUpdate.Done)
        assertEquals(300, (updates.last() as ScanUpdate.Done).total)

        // Batch totals accumulate monotonically to the final count.
        assertEquals(
            listOf(220, 250, 275, 290, 300),
            batches.map { it.totalSoFar },
        )
    }

    @Test
    fun `translated chapters are detected from siblings and translation files`() {
        val chapters = scan().chapters().associateBy { it.storageKey }

        // A "<name>_translated.cbz" sibling in the manga folder.
        assertTrue(chapters.getValue("SourceA/Big Manga/Chapter 1").translated)
        assertTrue(chapters.getValue("SourceA/Big Manga/Chapter 5").translated)
        assertFalse(chapters.getValue("SourceA/Big Manga/Chapter 2").translated)

        // A "<name>_translated" sibling folder.
        assertTrue(chapters.getValue("SourceB/Folder Manga/Ch 1").translated)
        assertFalse(chapters.getValue("SourceB/Folder Manga/Ch 2").translated)

        // A JSON under translations/<source>/<manga>.
        assertTrue(chapters.getValue("SourceC/Mixed Manga/Vol 3").translated)
        assertTrue(chapters.getValue("SourceC/Mixed Manga/Vol 7").translated)
        assertFalse(chapters.getValue("SourceC/Mixed Manga/Vol 1").translated)

        assertEquals(5, chapters.values.count { it.translated })
    }

    @Test
    fun `translated output and backup folders are hidden from the listing`() {
        val chapters = scan().chapters()

        assertTrue(chapters.none { it.chapterBaseName.endsWith("_translated") })
        assertTrue(chapters.none { it.chapterBaseName == "__honyuka_backup__" })
    }

    @Test
    fun `one thumbnail is resolved per manga of folder chapters`() {
        val chapters = scan().chapters()

        val folderManga = chapters.filter { it.mangaName == "Folder Manga" }
        assertEquals(1, folderManga.count { it.thumbnailUri != null })
        assertNotNull(folderManga.first { it.chapterBaseName == "Ch 1" }.thumbnailUri)

        // CBZ chapters carry no thumbnail, exactly as before.
        assertNull(chapters.first { it.mangaName == "Big Manga" }.thumbnailUri)
    }

    @Test
    fun `scan completes quickly`() {
        val startedAt = System.nanoTime()
        val chapters = scan().chapters()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(300, chapters.size)
        assertTrue("Scan took ${elapsedMillis}ms", elapsedMillis < 5_000)
    }

    private fun scan(): List<ScanUpdate> = runBlocking { storage.scanChapters().toList() }

    private fun List<ScanUpdate>.chapters(): List<ChapterEntry> =
        filterIsInstance<ScanUpdate.Batch>().flatMap { it.chapters }

    private fun buildSyntheticLibrary(): Map<String, List<FakeNode>> {
        val tree = FakeTree(ROOT_ID)
        val downloads = tree.directory(ROOT_ID, "downloads")
        val translations = tree.directory(ROOT_ID, "translations")

        val sourceA = tree.directory(downloads, "SourceA")
        val bigManga = tree.directory(sourceA, "Big Manga")
        repeat(220) { index -> tree.file(bigManga, "Chapter ${index + 1}.cbz") }
        tree.file(bigManga, "Chapter 1_translated.cbz")
        tree.file(bigManga, "Chapter 5_translated.cbz")
        tree.directory(bigManga, "__honyuka_backup__")

        val sourceB = tree.directory(downloads, "SourceB")
        val folderManga = tree.directory(sourceB, "Folder Manga")
        repeat(30) { index ->
            val chapter = tree.directory(folderManga, "Ch ${index + 1}")
            tree.file(chapter, "001.jpg")
            tree.file(chapter, "002.jpg")
        }
        tree.directory(folderManga, "Ch 1_translated")

        val sourceC = tree.directory(downloads, "SourceC")
        val mixedManga = tree.directory(sourceC, "Mixed Manga")
        repeat(25) { index -> tree.file(mixedManga, "Vol ${index + 1}.cbz") }

        val sourceD = tree.directory(downloads, "SourceD")
        val mangaOne = tree.directory(sourceD, "Manga One")
        repeat(15) { index -> tree.file(mangaOne, "One ${index + 1}.cbz") }
        val mangaTwo = tree.directory(sourceD, "Manga Two")
        repeat(10) { index -> tree.file(mangaTwo, "Two ${index + 1}.cbz") }

        val translatedSourceC = tree.directory(translations, "SourceC")
        val translatedMixedManga = tree.directory(translatedSourceC, "Mixed Manga")
        tree.file(translatedMixedManga, "Vol 3.json")
        tree.file(translatedMixedManga, "Vol 7.json")

        return tree.children
    }

    private companion object {
        const val AUTHORITY = "app.honyuka.test.documents"
        const val ROOT_ID = "root"
    }
}

data class FakeNode(
    val documentId: String,
    val name: String,
    val mimeType: String,
)

class FakeTree(rootId: String) {
    val children = mutableMapOf(rootId to mutableListOf<FakeNode>())
    private var nextId = 0

    fun directory(parent: String, name: String): String {
        val id = add(parent, name, DocumentsContract.Document.MIME_TYPE_DIR)
        children[id] = mutableListOf()
        return id
    }

    fun file(parent: String, name: String): String =
        add(parent, name, "application/octet-stream")

    private fun add(parent: String, name: String, mimeType: String): String {
        val id = "doc${nextId++}"
        children.getValue(parent) += FakeNode(id, name, mimeType)
        return id
    }
}

/**
 * Serves child-document listings out of an in-memory tree and counts every round trip.
 */
class FakeDocumentsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queryCount++
        val columns = projection ?: DEFAULT_COLUMNS
        val cursor = MatrixCursor(columns)
        val parentId = DocumentsContract.getDocumentId(uri)
        tree[parentId].orEmpty().forEach { node ->
            cursor.addRow(columns.map { column -> node.column(column) }.toTypedArray())
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun FakeNode.column(name: String): Any? = when (name) {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> this.name
        DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType
        else -> null
    }

    companion object {
        private val DEFAULT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        var queryCount: Int = 0

        private var tree: Map<String, List<FakeNode>> = emptyMap()

        fun reset(tree: Map<String, List<FakeNode>>) {
            this.tree = tree
            queryCount = 0
        }
    }
}
