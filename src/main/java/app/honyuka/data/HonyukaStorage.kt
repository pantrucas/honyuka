package app.honyuka.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.honyuka.model.ChapterEntry
import app.honyuka.model.PageTranslation
import app.honyuka.model.SourceLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.OutputStream
import java.util.Locale

class HonyukaStorage(
    private val context: Context,
    private val preferences: HonyukaPreferences,
) {

    fun saveRoot(uri: Uri) {
        preferences.saveRootUri(uri)
    }

    fun rootSummary(): String? = preferences.rootUri()?.toString()

    fun hasCompatibleRoot(): Boolean {
        val treeUri = preferences.rootUri() ?: return false
        return runCatching {
            listChildren(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                .any { it.name == DOWNLOADS_DIR && it.isDirectory }
        }.getOrElse { rootDirectory()?.findFile(DOWNLOADS_DIR)?.isDirectory == true }
    }

    /**
     * Lists every downloaded chapter.
     *
     * Uses one DocumentsContract query per folder (id + name + type in a single cursor)
     * instead of DocumentFile, whose listFiles()/getName()/isDirectory() cost one
     * provider round-trip per child per call — that made a 167-chapter folder take
     * tens of minutes.
     *
     * @param onlyLanguages when set, source folders tagged with a language outside this set
     * (Mihon names them `Source (EN)`, `Source (ES)`…) are skipped entirely.
     * Folders without a language tag are always included.
     */
    fun scanChapters(onlyLanguages: Set<SourceLanguage>? = null): List<ChapterEntry> {
        val treeUri = preferences.rootUri() ?: return emptyList()
        val rootChildren = listChildren(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val downloadsDir = rootChildren.firstOrNull { it.name == DOWNLOADS_DIR && it.isDirectory }
            ?: return emptyList()

        // local/<manga> (Honyuka)/ folders, listed lazily only for mangas we actually show.
        val localDirs = rootChildren.firstOrNull { it.name == LOCAL_SOURCE_DIR && it.isDirectory }
            ?.let { local -> listChildren(treeUri, local.documentId).filter { it.isDirectory } }
            .orEmpty()
            .associateBy { it.name }

        val wantedTags = onlyLanguages?.takeIf { it.isNotEmpty() }?.map { it.folderTag }?.toSet()
        val startedAt = System.currentTimeMillis()

        val chapters = listChildren(treeUri, downloadsDir.documentId)
            .filter { it.isDirectory && it.name.isNotBlank() && !it.name.startsWith(".") }
            .filter { sourceDir ->
                val tag = sourceLanguageTag(sourceDir.name)
                wantedTags == null || tag == null || tag in wantedTags
            }
            .sortedByNaturalName()
            .flatMap { sourceDir ->
                listChildren(treeUri, sourceDir.documentId)
                    .filter { it.isDirectory && it.name.isNotBlank() && !it.name.startsWith(".") }
                    .sortedByNaturalName()
                    .flatMap { mangaDir ->
                        val exported = localDirs[localMangaDirName(mangaDir.name)]
                            ?.let { dir -> listChildren(treeUri, dir.documentId).map { it.name }.toSet() }
                            .orEmpty()
                        listChildren(treeUri, mangaDir.documentId)
                            .filter { isVisibleChapterContainer(it) }
                            .sortedByNaturalName()
                            .map { chapterFile ->
                                val baseName = chapterFile.name.removeSuffix(".cbz")
                                ChapterEntry(
                                    sourceName = sourceDir.name,
                                    mangaName = mangaDir.name,
                                    chapterDisplayName = baseName,
                                    chapterBaseName = baseName,
                                    chapterUri = chapterFile.uri,
                                    thumbnailUri = if (chapterFile.isDirectory) {
                                        firstImageUri(treeUri, chapterFile.documentId, depth = 0)
                                    } else {
                                        null
                                    },
                                    isCbz = !chapterFile.isDirectory,
                                    translated = localChapterFileNames(baseName).any { it in exported },
                                )
                            }
                    }
            }
        Log.d(TAG, "Scanned ${chapters.size} chapters in ${System.currentTimeMillis() - startedAt} ms")
        return chapters
    }

    fun resolveChapterDocument(chapter: ChapterEntry): DocumentFile? {
        val root = rootDirectory() ?: return null
        val mangaDir = root.fastFindFile(DOWNLOADS_DIR)
            ?.fastFindFile(chapter.sourceName)
            ?.fastFindFile(chapter.mangaName)
            ?: return null

        val candidateNames = buildList {
            add(chapter.chapterBaseName)
            if (chapter.isCbz) {
                add("${chapter.chapterBaseName}.cbz")
            }
        }

        return candidateNames
            .asSequence()
            .mapNotNull { mangaDir.fastFindFile(it) }
            .firstOrNull()
    }

    fun resolveOriginalChapterDocument(chapter: ChapterEntry): DocumentFile? {
        return resolveChapterDocument(chapter)
    }

    /** `local/<manga> (Honyuka)/`, created if needed. Mihon's Local Source lists each folder here as a manga. */
    fun localSourceMangaDirectory(chapter: ChapterEntry): DocumentFile {
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val localRoot = root.fastFindFile(LOCAL_SOURCE_DIR) ?: root.createDirectory(LOCAL_SOURCE_DIR)
        requireNotNull(localRoot) { "Unable to locate or create the local source root." }

        val mangaDirName = localMangaDirName(chapter.mangaName)
        val mangaDir = localRoot.fastFindFile(mangaDirName) ?: localRoot.createDirectory(mangaDirName)
        return requireNotNull(mangaDir) { "Unable to create local source manga directory." }
    }

    /** Title shown in Mihon for the translated copy (also the local folder name). */
    fun localSeriesTitle(chapter: ChapterEntry): String = localMangaDirName(chapter.mangaName)

    /**
     * Creates (replacing any previous export) `local/<manga> (Honyuka)/<chapter>.cbz`.
     * Exports from older builds named `<chapter>_translated.cbz` are removed so Mihon
     * doesn't list the chapter twice.
     */
    fun prepareLocalSourceExportTarget(chapter: ChapterEntry): DocumentFile {
        val mangaDir = localSourceMangaDirectory(chapter)
        val names = localChapterFileNames(chapter.chapterBaseName)
        names.forEach { name -> mangaDir.fastFindFile(name)?.delete() }
        // octet-stream keeps the file name exactly as given; some providers rewrite
        // the extension for comic-book MIME types.
        return mangaDir.createFile("application/octet-stream", names.first())
            ?: error("Unable to create local source export archive.")
    }

    /** Writes (or overwrites) [fileName] in the local manga folder. */
    fun writeLocalMangaFile(chapter: ChapterEntry, fileName: String, bytes: ByteArray) {
        val mangaDir = localSourceMangaDirectory(chapter)
        val file = mangaDir.fastFindFile(fileName)
            ?: mangaDir.createFile("application/octet-stream", fileName)
            ?: error("Unable to create $fileName in the local source folder.")
        context.contentResolver.openOutputStream(file.uri, "wt").use { output ->
            requireNotNull(output) { "Unable to write $fileName." }
            output.write(bytes)
        }
    }

    /** Writes [fileName] into the local manga folder only if it doesn't exist yet. */
    fun writeLocalMangaFileIfMissing(
        chapter: ChapterEntry,
        fileName: String,
        bytes: () -> ByteArray?,
    ) {
        val mangaDir = localSourceMangaDirectory(chapter)
        if (mangaDir.fastFindFile(fileName) != null) return
        val content = bytes() ?: return
        val file = mangaDir.createFile("application/octet-stream", fileName) ?: return
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content) }
    }

    /** Mihon uses any `cover.*` image in the manga folder. */
    fun hasLocalCover(chapter: ChapterEntry): Boolean {
        return localSourceMangaDirectory(chapter).listFiles().any { file ->
            file.isFile && file.name.orEmpty().substringBeforeLast('.').equals("cover", ignoreCase = true)
        }
    }

    fun writeTranslation(
        chapter: ChapterEntry,
        translations: Map<String, PageTranslation>,
    ) {
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val translationsRoot = root.fastFindFile(TRANSLATIONS_DIR) ?: root.createDirectory(TRANSLATIONS_DIR)
        requireNotNull(translationsRoot) { "Unable to create translations directory." }

        val sourceDir = translationsRoot.fastFindFile(chapter.sourceName) ?: translationsRoot.createDirectory(chapter.sourceName)
        val mangaDir = sourceDir?.fastFindFile(chapter.mangaName) ?: sourceDir?.createDirectory(chapter.mangaName)
        requireNotNull(mangaDir) { "Unable to create manga translation directory." }

        val fileName = "${buildValidFilename(chapter.chapterBaseName)}.json"
        mangaDir.fastFindFile(fileName)?.delete()
        val file = mangaDir.createFile("application/json", fileName)
        requireNotNull(file) { "Unable to create translation JSON." }

        context.contentResolver.openOutputStream(file.uri, "wt").use { output: OutputStream? ->
            requireNotNull(output) { "Unable to open translation output stream." }
            output.write(Json.encodeToString(translations).toByteArray())
        }
    }

    /**
     * Load a previously saved translation sidecar for the given chapter.
     * Returns null if no sidecar exists.
     */
    fun readTranslation(chapter: ChapterEntry): Map<String, PageTranslation>? {
        val root = rootDirectory() ?: return null
        val translationsDir = root.fastFindFile(TRANSLATIONS_DIR) ?: return null
        val file = translationFileFor(
            translationsDir = translationsDir,
            sourceName = chapter.sourceName,
            mangaName = chapter.mangaName,
            chapterBaseName = chapter.chapterBaseName,
        ) ?: return null
        if (!file.exists()) return null
        return try {
            val jsonText = context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return null
            Json.decodeFromString<Map<String, PageTranslation>>(jsonText)
        } catch (_: Exception) {
            null
        }
    }

    private fun rootDirectory(): DocumentFile? {
        val rootUri = preferences.rootUri() ?: return null
        return DocumentFile.fromTreeUri(context, rootUri)
    }

    private fun translationFileFor(
        translationsDir: DocumentFile?,
        sourceName: String,
        mangaName: String,
        chapterBaseName: String,
    ): DocumentFile? {
        return translationsDir
            ?.fastFindFile(sourceName)
            ?.fastFindFile(mangaName)
            ?.fastFindFile("${buildValidFilename(chapterBaseName)}.json")
    }

    /** One child row of a SAF directory, read from a single cursor query. */
    private data class DocEntry(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val uri: Uri,
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    /** Lists the children of [parentDocumentId] with one provider query. */
    private fun listChildren(treeUri: Uri, parentDocumentId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val entries = ArrayList<DocEntry>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2).orEmpty()
                entries += DocEntry(
                    documentId = documentId,
                    name = name,
                    mimeType = mimeType,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                )
            }
        }
        return entries
    }

    /**
     * Like [DocumentFile.findFile] but matches the name with a single cursor query
     * instead of one getName() round-trip per child. Falls back to findFile() if the
     * provider query fails.
     */
    private fun DocumentFile.fastFindFile(name: String): DocumentFile? {
        val treeUri = preferences.rootUri() ?: return findFile(name)
        return runCatching {
            val match = listChildren(treeUri, DocumentsContract.getDocumentId(uri))
                .firstOrNull { it.name == name }
                ?: return@runCatching null
            // listFiles() is a single query that only builds URIs (no per-child lookups).
            listFiles().firstOrNull { it.uri == match.uri }
                ?: DocumentFile.fromSingleUri(context, match.uri)?.takeUnless { match.isDirectory }
        }.getOrElse { error ->
            Log.w(TAG, "Fast lookup of $name failed; falling back to findFile()", error)
            findFile(name)
        }
    }

    private fun isVisibleChapterContainer(file: DocEntry): Boolean {
        val name = file.name
        val isChapter = file.isDirectory || name.lowercase(Locale.ROOT).endsWith(".cbz")
        if (!isChapter) return false
        val baseName = name.removeSuffix(".cbz")
        // Mihon writes in-progress downloads to `<chapter>_tmp` / `<chapter>.cbz_tmp`
        // and ignores them; so do we. Hidden entries are never chapters. Copies and
        // backups left inside downloads/ by Honyuka 0.1.0 are skipped too.
        return !name.startsWith(".") &&
            !name.endsWith("_tmp") &&
            baseName != BACKUPS_DIR &&
            !baseName.endsWith(TRANSLATED_SUFFIX)
    }

    /** First page image of a folder chapter (looks one level into subfolders). */
    private fun firstImageUri(treeUri: Uri, directoryId: String, depth: Int): Uri? {
        val children = listChildren(treeUri, directoryId).sortedByNaturalName()
        children.firstOrNull { !it.isDirectory && isImageName(it.name) }?.let { return it.uri }
        if (depth >= 1) return null
        return children.asSequence()
            .filter { it.isDirectory }
            .mapNotNull { firstImageUri(treeUri, it.documentId, depth + 1) }
            .firstOrNull()
    }

    private fun List<DocEntry>.sortedByNaturalName(): List<DocEntry> =
        sortedWith { left, right -> naturalCompare(left.name, right.name) }

    /** `MangaDex (EN)` → `en`, `Source (ES-419)` → `es`; null when there is no tag. */
    private fun sourceLanguageTag(sourceFolderName: String): String? {
        val tag = SOURCE_LANGUAGE_REGEX.find(sourceFolderName)?.groupValues?.getOrNull(1) ?: return null
        return tag.lowercase(Locale.ROOT).takeUnless { it == "all" }
    }

    private fun isImageName(name: String?): Boolean {
        val lowered = name?.lowercase(Locale.ROOT) ?: return false
        return lowered.endsWith(".jpg") ||
            lowered.endsWith(".jpeg") ||
            lowered.endsWith(".png") ||
            lowered.endsWith(".webp") ||
            lowered.endsWith(".gif") ||
            lowered.endsWith(".heif") ||
            lowered.endsWith(".avif")
    }

    private fun localMangaDirName(mangaName: String): String = buildValidFilename("$mangaName (Honyuka)")

    /** Current export name first, then the `_translated` name used by older builds. */
    private fun localChapterFileNames(chapterBaseName: String): List<String> {
        val maxBaseBytes = MAX_FILE_NAME_BYTES - ".cbz".length - TRANSLATED_SUFFIX.length
        return listOf(
            "${buildValidFilename(chapterBaseName, maxBaseBytes)}.cbz",
            "${buildValidFilename("$chapterBaseName$TRANSLATED_SUFFIX", maxBaseBytes + TRANSLATED_SUFFIX.length)}.cbz",
        )
    }

    /** Mirrors Mihon's `DiskUtil.buildValidFilename`, including its UTF-8 byte limit. */
    private fun buildValidFilename(original: String, maxBytes: Int = MAX_FILE_NAME_BYTES): String {
        val trimmed = original.trim('.', ' ')
        if (trimmed.isEmpty()) return "(invalid)"
        val builder = StringBuilder(trimmed.length)
        trimmed.forEach { character ->
            val isAllowed = character !in setOf('"', '*', '/', ':', '<', '>', '?', '\\', '|') &&
                character.code !in 0..31 &&
                character.code != 127
            builder.append(if (isAllowed) character else '_')
        }
        return truncateToUtf8Bytes(builder.toString(), maxBytes).ifEmpty { "(invalid)" }
    }

    private fun truncateToUtf8Bytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var bytes = 0
        var end = 0
        while (end < value.length) {
            val codePoint = value.codePointAt(end)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return value.substring(0, end)
    }

    private companion object {
        const val TAG = "HonyukaStorage"
        val SOURCE_LANGUAGE_REGEX = Regex("""\(([A-Za-z]{2,3})(?:-[A-Za-z0-9]+)?\)\s*$""")
        const val DOWNLOADS_DIR = "downloads"
        const val LOCAL_SOURCE_DIR = "local"
        const val TRANSLATIONS_DIR = "translations"
        const val BACKUPS_DIR = "__honyuka_backup__"
        const val TRANSLATED_SUFFIX = "_translated"
        const val MAX_FILE_NAME_BYTES = 240
    }
}


private fun naturalCompare(left: String, right: String): Int {
    val leftParts = Regex("(\\d+|\\D+)").findAll(left).map { it.value }.toList()
    val rightParts = Regex("(\\d+|\\D+)").findAll(right).map { it.value }.toList()
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
