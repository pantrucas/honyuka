package app.honyuka.data

import android.content.Context
import android.net.Uri
import android.util.Log
import app.honyuka.model.ChapterEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Remembers the last completed scan so reopening the app shows the library immediately instead of
 * waiting on the reader storage. The cache is tied to the selected root, so picking a different
 * folder discards it.
 */
class ChapterCache(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(rootUri: String?): List<ChapterEntry>? {
        if (rootUri.isNullOrBlank() || !file.exists()) return null
        val cached = runCatching { json.decodeFromString<CachedScan>(file.readText()) }
            .onFailure { Log.w(TAG, "Unable to read the chapter cache", it) }
            .getOrNull()
            ?: return null
        if (cached.version != VERSION || cached.rootUri != rootUri) return null
        return cached.chapters.map { it.toChapterEntry() }
    }

    fun save(rootUri: String?, chapters: List<ChapterEntry>) {
        if (rootUri.isNullOrBlank()) return
        val payload = CachedScan(
            version = VERSION,
            rootUri = rootUri,
            chapters = chapters.map { CachedChapter.from(it) },
        )
        runCatching { file.writeText(json.encodeToString(payload)) }
            .onFailure { Log.w(TAG, "Unable to write the chapter cache", it) }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "chapter_scan_cache.json"
        const val VERSION = 1
        const val TAG = "Honyuka"
    }
}

@Serializable
private data class CachedScan(
    val version: Int,
    val rootUri: String,
    val chapters: List<CachedChapter>,
)

@Serializable
private data class CachedChapter(
    val sourceName: String,
    val mangaName: String,
    val chapterDisplayName: String,
    val chapterBaseName: String,
    val chapterUri: String,
    val thumbnailUri: String? = null,
    val isCbz: Boolean,
    val translated: Boolean,
) {
    fun toChapterEntry(): ChapterEntry = ChapterEntry(
        sourceName = sourceName,
        mangaName = mangaName,
        chapterDisplayName = chapterDisplayName,
        chapterBaseName = chapterBaseName,
        chapterUri = Uri.parse(chapterUri),
        thumbnailUri = thumbnailUri?.let(Uri::parse),
        isCbz = isCbz,
        translated = translated,
    )

    companion object {
        fun from(chapter: ChapterEntry): CachedChapter = CachedChapter(
            sourceName = chapter.sourceName,
            mangaName = chapter.mangaName,
            chapterDisplayName = chapter.chapterDisplayName,
            chapterBaseName = chapter.chapterBaseName,
            chapterUri = chapter.chapterUri.toString(),
            thumbnailUri = chapter.thumbnailUri?.toString(),
            isCbz = chapter.isCbz,
            translated = chapter.translated,
        )
    }
}
