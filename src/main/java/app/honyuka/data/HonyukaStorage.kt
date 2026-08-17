package app.honyuka.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.honyuka.model.ChapterEntry
import app.honyuka.model.PageTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.OutputStream
import java.util.Locale

sealed interface ScanUpdate {
    data class Batch(
        val sourceName: String,
        val mangaName: String,
        val chapters: List<ChapterEntry>,
        val totalSoFar: Int,
        val progress: Float,
    ) : ScanUpdate

    data class Done(val total: Int) : ScanUpdate
}

class HonyukaStorage(
    private val context: Context,
    private val preferences: HonyukaPreferences,
) {

    fun saveRoot(uri: Uri) {
        preferences.saveRootUri(uri)
    }

    fun rootSummary(): String? = preferences.rootUri()?.toString()

    fun hasCompatibleRoot(): Boolean {
        val reader = treeReader() ?: return false
        return reader.listChildren(reader.rootDocumentId())
            .any { it.isDirectory && it.name == DOWNLOADS_DIR }
    }

    /**
     * Walks the reader downloads tree, emitting one batch per manga so the list can populate while
     * the scan is still running.
     *
     * Each directory is listed exactly once. Whether a chapter is already translated is answered
     * from two in-memory sets — its siblings in the manga folder, and the JSON files under
     * `translations/<source>/<manga>` — so the inner loop issues no queries at all. The total cost
     * is `3 + 2 * (sources + manga)` queries instead of growing with the chapter count.
     */
    fun scanChapters(): Flow<ScanUpdate> = flow {
        val reader = treeReader() ?: run {
            emit(ScanUpdate.Done(0))
            return@flow
        }

        val rootChildren = reader.listChildren(reader.rootDocumentId())
        val downloadsId = rootChildren
            .firstOrNull { it.isDirectory && it.name == DOWNLOADS_DIR }
            ?.documentId
            ?: run {
                emit(ScanUpdate.Done(0))
                return@flow
            }

        val translationSources = rootChildren
            .firstOrNull { it.isDirectory && it.name == TRANSLATIONS_DIR }
            ?.let { reader.listChildren(it.documentId) }
            ?.filter { it.isDirectory }
            ?.associate { it.name to it.documentId }
            .orEmpty()

        val sourceDirs = reader.listChildren(downloadsId)
            .filter { it.isDirectory && it.name.isNotBlank() }
            .sortedByNaturalName()

        var total = 0
        sourceDirs.forEachIndexed { sourceIndex, sourceDir ->
            val translatedMangaDirs = translationSources[sourceDir.name]
                ?.let { reader.listChildren(it) }
                ?.filter { it.isDirectory }
                ?.associate { it.name to it.documentId }
                .orEmpty()

            val mangaDirs = reader.listChildren(sourceDir.documentId)
                .filter { it.isDirectory && it.name.isNotBlank() }
                .sortedByNaturalName()

            mangaDirs.forEachIndexed { mangaIndex, mangaDir ->
                val children = reader.listChildren(mangaDir.documentId)
                val siblingNames = children.mapTo(HashSet(children.size)) { it.name }
                val translationFileNames = translatedMangaDirs[mangaDir.name]
                    ?.let { reader.listChildren(it) }
                    ?.mapTo(HashSet()) { it.name }
                    .orEmpty()

                var thumbnailAttempts = 0
                var thumbnailFound = false
                val chapters = children
                    .filter(::isVisibleChapterContainer)
                    .sortedByNaturalName()
                    .map { chapterEntry ->
                        val baseName = chapterEntry.name.removeSuffix(CBZ_EXTENSION)
                        val translatedName = translatedChapterName(baseName)
                        // The UI only ever renders one thumbnail per manga, so the first chapter
                        // that yields an image is enough. Empty folders are retried a few times.
                        val thumbnailUri = if (
                            chapterEntry.isDirectory &&
                            !thumbnailFound &&
                            thumbnailAttempts < MAX_THUMBNAIL_ATTEMPTS
                        ) {
                            thumbnailAttempts++
                            firstImageUri(reader, chapterEntry.documentId)?.also { thumbnailFound = true }
                        } else {
                            null
                        }
                        ChapterEntry(
                            sourceName = sourceDir.name,
                            mangaName = mangaDir.name,
                            chapterDisplayName = baseName,
                            chapterBaseName = baseName,
                            chapterUri = reader.documentUri(chapterEntry.documentId),
                            thumbnailUri = thumbnailUri,
                            isCbz = !chapterEntry.isDirectory,
                            translated = translatedName in siblingNames ||
                                "$translatedName$CBZ_EXTENSION" in siblingNames ||
                                "${buildValidFilename(baseName)}.json" in translationFileNames,
                        )
                    }

                total += chapters.size
                if (chapters.isNotEmpty()) {
                    emit(
                        ScanUpdate.Batch(
                            sourceName = sourceDir.name,
                            mangaName = mangaDir.name,
                            chapters = chapters,
                            totalSoFar = total,
                            progress = scanProgress(sourceIndex, sourceDirs.size, mangaIndex, mangaDirs.size),
                        ),
                    )
                }
            }
        }

        emit(ScanUpdate.Done(total))
    }.flowOn(Dispatchers.IO)

    private fun treeReader(): SafTreeReader? {
        val rootUri = preferences.rootUri() ?: return null
        return SafTreeReader(context.contentResolver, rootUri)
    }

    private fun scanProgress(
        sourceIndex: Int,
        sourceCount: Int,
        mangaIndex: Int,
        mangaCount: Int,
    ): Float {
        if (sourceCount == 0) return 1f
        val withinSource = if (mangaCount == 0) 1f else (mangaIndex + 1).toFloat() / mangaCount
        return ((sourceIndex + withinSource) / sourceCount).coerceIn(0f, 1f)
    }

    fun resolveChapterDocument(chapter: ChapterEntry): DocumentFile? {
        val root = rootDirectory() ?: return null
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
            ?: return null

        val candidateNames = buildList {
            add(chapter.chapterBaseName)
            if (chapter.isCbz) {
                add("${chapter.chapterBaseName}.cbz")
            }
        }

        return candidateNames
            .asSequence()
            .mapNotNull(mangaDir::findFile)
            .firstOrNull()
    }

    fun resolveInputChapterDocument(chapter: ChapterEntry): DocumentFile? {
        return resolveBackupChapterDocument(chapter) ?: resolveChapterDocument(chapter)
    }

    fun resolveOriginalChapterDocument(chapter: ChapterEntry): DocumentFile? {
        return resolveChapterDocument(chapter)
    }

    fun prepareTranslatedChapterDocument(chapter: ChapterEntry): DocumentFile {
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
        requireNotNull(mangaDir) { "Unable to locate manga downloads directory." }

        resolveTranslatedChapterDocument(chapter)?.let { existing ->
            deleteRecursively(existing)
        }

        val translatedName = translatedChapterName(chapter.chapterBaseName)
        return if (chapter.isCbz) {
            mangaDir.createFile("application/vnd.comicbook+zip", "$translatedName.cbz")
                ?: error("Unable to create translated chapter archive.")
        } else {
            mangaDir.createDirectory(translatedName)
                ?: error("Unable to create translated chapter folder.")
        }
    }

    fun prepareLocalSourceExportTarget(
        chapter: ChapterEntry,
    ): DocumentFile {
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val localRoot = root.findFile(LOCAL_SOURCE_DIR) ?: root.createDirectory(LOCAL_SOURCE_DIR)
        requireNotNull(localRoot) { "Unable to locate or create the local source root." }

        val mangaDirName = buildValidFilename("${chapter.mangaName} (Honyuka)")
        val mangaDir = localRoot.findFile(mangaDirName) ?: localRoot.createDirectory(mangaDirName)
        requireNotNull(mangaDir) { "Unable to create local source manga directory." }

        val chapterFileName = "${buildValidFilename("${chapter.chapterBaseName}$TRANSLATED_SUFFIX")}.cbz"
        mangaDir.findFile(chapterFileName)?.delete()
        return mangaDir.createFile("application/vnd.comicbook+zip", chapterFileName)
            ?: error("Unable to create local source export archive.")
    }

    fun ensureOriginalChapterBackup(chapter: ChapterEntry): DocumentFile {
        resolveBackupChapterDocument(chapter)?.let { return it }

        val liveChapter = requireNotNull(resolveChapterDocument(chapter)) {
            "Unable to locate the live chapter in downloads."
        }
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
        requireNotNull(mangaDir) { "Unable to locate manga downloads directory." }

        val backupRoot = mangaDir.findFile(BACKUPS_DIR) ?: mangaDir.createDirectory(BACKUPS_DIR)
        requireNotNull(backupRoot) { "Unable to create per-manga backup directory." }

        return if (chapter.isCbz) {
            val backupName = "${chapter.chapterBaseName}.cbz"
            val file = backupRoot.createFile("application/vnd.comicbook+zip", backupName)
                ?: error("Unable to create chapter backup archive.")
            copyFileContents(liveChapter, file)
            file
        } else {
            val folder = backupRoot.createDirectory(chapter.chapterBaseName)
                ?: error("Unable to create chapter backup folder.")
            copyDirectoryContents(liveChapter, folder)
            folder
        }
    }

    fun writeTranslation(
        chapter: ChapterEntry,
        translations: Map<String, PageTranslation>,
    ) {
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val translationsRoot = root.findFile(TRANSLATIONS_DIR) ?: root.createDirectory(TRANSLATIONS_DIR)
        requireNotNull(translationsRoot) { "Unable to create translations directory." }

        val sourceDir = translationsRoot.findFile(chapter.sourceName) ?: translationsRoot.createDirectory(chapter.sourceName)
        val mangaDir = sourceDir?.findFile(chapter.mangaName) ?: sourceDir?.createDirectory(chapter.mangaName)
        requireNotNull(mangaDir) { "Unable to create manga translation directory." }

        val fileName = "${buildValidFilename(chapter.chapterBaseName)}.json"
        mangaDir.findFile(fileName)?.delete()
        val file = mangaDir.createFile("application/json", fileName)
        requireNotNull(file) { "Unable to create translation JSON." }

        context.contentResolver.openOutputStream(file.uri, "wt").use { output: OutputStream? ->
            requireNotNull(output) { "Unable to open translation output stream." }
            output.write(Json.encodeToString(translations).toByteArray())
        }
    }

    private fun rootDirectory(): DocumentFile? {
        val rootUri = preferences.rootUri() ?: return null
        return DocumentFile.fromTreeUri(context, rootUri)
    }

    fun resolveTranslatedChapterDocument(chapter: ChapterEntry): DocumentFile? {
        return resolveTranslatedChapterDocument(
            sourceName = chapter.sourceName,
            mangaName = chapter.mangaName,
            chapterBaseName = chapter.chapterBaseName,
            isCbz = chapter.isCbz,
        )
    }

    private fun resolveTranslatedChapterDocument(
        sourceName: String,
        mangaName: String,
        chapterBaseName: String,
        isCbz: Boolean,
    ): DocumentFile? {
        val root = rootDirectory() ?: return null
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(sourceName)
            ?.findFile(mangaName)
            ?: return null

        val translatedName = translatedChapterName(chapterBaseName)
        val candidates = buildList {
            add(translatedName)
            if (isCbz) add("$translatedName.cbz")
        }
        return candidates.asSequence().mapNotNull(mangaDir::findFile).firstOrNull()
    }

    private fun resolveBackupChapterDocument(chapter: ChapterEntry): DocumentFile? {
        val root = rootDirectory() ?: return null
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
            ?.findFile(BACKUPS_DIR)
            ?: return null

        val backupNames = buildList {
            add(chapter.chapterBaseName)
            if (chapter.isCbz) add("${chapter.chapterBaseName}.cbz")
        }

        return backupNames
            .asSequence()
            .mapNotNull(mangaDir::findFile)
            .firstOrNull()
            ?: resolveLegacyBackupChapterDocument(root, chapter)
    }

    private fun resolveLegacyBackupChapterDocument(
        root: DocumentFile,
        chapter: ChapterEntry,
    ): DocumentFile? {
        val mangaDir = root.findFile(LEGACY_BACKUPS_DIR)
            ?.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
            ?: return null

        val backupNames = buildList {
            add(chapter.chapterBaseName)
            if (chapter.isCbz) add("${chapter.chapterBaseName}.cbz")
        }

        return backupNames
            .asSequence()
            .mapNotNull(mangaDir::findFile)
            .firstOrNull()
    }

    private fun copyDirectoryContents(source: DocumentFile, destination: DocumentFile) {
        source.listFiles().forEach { child ->
            when {
                child.isDirectory -> {
                    val targetDir = destination.findFile(child.name.orEmpty())
                        ?: destination.createDirectory(child.name.orEmpty())
                    requireNotNull(targetDir) { "Unable to create backup subdirectory ${child.name.orEmpty()}." }
                    copyDirectoryContents(child, targetDir)
                }
                child.isFile -> {
                    val targetFile = destination.createFile(child.type ?: "application/octet-stream", child.name.orEmpty())
                        ?: error("Unable to create backup file ${child.name.orEmpty()}.")
                    copyFileContents(child, targetFile)
                }
            }
        }
    }

    private fun copyFileContents(source: DocumentFile, destination: DocumentFile) {
        context.contentResolver.openInputStream(source.uri).use { input ->
            requireNotNull(input) { "Unable to open source file ${source.name.orEmpty()}." }
            context.contentResolver.openOutputStream(destination.uri, "wt").use { output ->
                requireNotNull(output) { "Unable to open destination file ${destination.name.orEmpty()}." }
                input.copyTo(output)
            }
        }
    }

    private fun deleteRecursively(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach(::deleteRecursively)
        }
        file.delete()
    }

    private fun isSupportedChapterContainer(entry: SafEntry): Boolean {
        if (entry.name.isBlank()) return false
        return entry.isDirectory || entry.name.lowercase(Locale.ROOT).endsWith(CBZ_EXTENSION)
    }

    private fun isVisibleChapterContainer(entry: SafEntry): Boolean {
        if (!isSupportedChapterContainer(entry)) return false
        val baseName = entry.name.removeSuffix(CBZ_EXTENSION)
        return baseName != BACKUPS_DIR && !baseName.endsWith(TRANSLATED_SUFFIX)
    }

    private fun firstImageUri(reader: SafTreeReader, documentId: String, depth: Int = 1): Uri? {
        val children = reader.listChildren(documentId).sortedByNaturalName()
        children.firstOrNull { !it.isDirectory && isImageName(it.name) }
            ?.let { return reader.documentUri(it.documentId) }
        if (depth <= 0) return null
        children.filter { it.isDirectory }.forEach { child ->
            firstImageUri(reader, child.documentId, depth - 1)?.let { return it }
        }
        return null
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

    private fun translatedChapterName(originalBaseName: String): String {
        return "$originalBaseName$TRANSLATED_SUFFIX"
    }

    private fun buildValidFilename(original: String): String {
        val trimmed = original.trim('.', ' ')
        if (trimmed.isEmpty()) return "(invalid)"
        val builder = StringBuilder(trimmed.length)
        trimmed.forEach { character ->
            val isAllowed = character !in setOf('"', '*', '/', ':', '<', '>', '?', '\\', '|') &&
                character.code !in 0..31 &&
                character.code != 127
            builder.append(if (isAllowed) character else '_')
        }
        return builder.toString().take(240)
    }

    private companion object {
        const val DOWNLOADS_DIR = "downloads"
        const val LOCAL_SOURCE_DIR = "local"
        const val TRANSLATIONS_DIR = "translations"
        const val BACKUPS_DIR = "__honyuka_backup__"
        const val LEGACY_BACKUPS_DIR = "honyuka_backups"
        const val TRANSLATED_SUFFIX = "_translated"
        const val CBZ_EXTENSION = ".cbz"
        const val MAX_THUMBNAIL_ATTEMPTS = 3
    }
}
