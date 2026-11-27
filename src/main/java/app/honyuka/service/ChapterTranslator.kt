package app.honyuka.service

import android.content.Context
import android.os.PowerManager
import android.util.Log
import app.honyuka.data.HonyukaPreferences
import app.honyuka.data.HonyukaStorage
import app.honyuka.data.MangaContext
import app.honyuka.export.RenderedChapterExporter
import app.honyuka.model.ChapterEntry
import app.honyuka.model.LocalBackend
import app.honyuka.model.LocalModel
import app.honyuka.model.PageTranslation
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationBlock
import app.honyuka.model.TranslationEngine
import app.honyuka.translation.GeminiVisionEngine
import app.honyuka.translation.PageLineReader
import app.honyuka.translation.TranslationStop
import app.honyuka.translation.TranslationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything a translation run needs besides the chapter itself. */
data class TranslationSettings(
    val sourceLanguage: SourceLanguage,
    val targetLanguage: TargetLanguage,
    val engine: TranslationEngine,
    val geminiApiKey: String,
    val geminiModelName: String,
    val deepLApiKey: String,
    val localModel: LocalModel = LocalModel.DEFAULT,
    val localBackend: LocalBackend = LocalBackend.AUTO,
    val googleFallback: Boolean = false,
) {
    companion object {
        /** Current saved settings, with the source language picked for this chapter. */
        fun fromPreferences(preferences: HonyukaPreferences, chapter: ChapterEntry): TranslationSettings =
            TranslationSettings(
                sourceLanguage = SourceLanguage.forChapter(
                    chapter.sourceName,
                    preferences.sourceLanguages(),
                    preferences.sourceLanguage(),
                ),
                targetLanguage = preferences.targetLanguage(),
                engine = preferences.translationEngine(),
                geminiApiKey = preferences.geminiApiKey(),
                geminiModelName = preferences.geminiModelName(),
                deepLApiKey = preferences.deepLApiKey(),
                localModel = preferences.localModel(),
                localBackend = preferences.localBackend(),
                googleFallback = preferences.googleFallback(),
            )
    }
}

sealed interface ChapterRunOutcome {
    /** Exported to the Local Source. [untranslatedBlocks] are still in the original language. */
    data class Completed(
        val warning: String?,
        val untranslatedBlocks: Int,
        val totalBlocks: Int,
    ) : ChapterRunOutcome

    /** Gemini run that needs the user's OK before spending [chunks] requests. */
    data class NeedsConfirmation(val chunks: Int, val blocks: Int) : ChapterRunOutcome
}

/**
 * Per-chapter core shared by [TranslationService] (manual runs) and [AutoTranslateWorker]:
 * OCR → translate → save sidecar → export to Mihon's Local Source.
 * Errors are thrown; the caller decides how to report them.
 */
class ChapterTranslator(private val context: Context) {

    private val preferences = HonyukaPreferences(context)
    private val storage = HonyukaStorage(context, preferences)
    private val pipeline = TranslationPipeline(context)
    private val exporter = RenderedChapterExporter(context, storage)

    suspend fun run(
        chapter: ChapterEntry,
        settings: TranslationSettings,
        rerenderOnly: Boolean = false,
        fromScratch: Boolean = false,
        /** Only run the visual review on the saved translation, then re-render. */
        reviewOnly: Boolean = false,
        requireConfirmation: Boolean = false,
        failIfNothingTranslated: Boolean = false,
        onChunkSaved: (done: Int, total: Int) -> Unit = { _, _ -> },
        onProgress: (title: String, detail: String, progress: Float) -> Unit,
    ): ChapterRunOutcome {
        val chapterDocument = withContext(Dispatchers.IO) {
            storage.resolveOriginalChapterDocument(chapter)
        } ?: error("Unable to open chapter ${chapter.chapterDisplayName}")

        if (fromScratch && !rerenderOnly && !reviewOnly) {
            // Keep the old result as `.json.bak`; the run below then redoes OCR and translation.
            withContext(Dispatchers.IO) { storage.backupTranslation(chapter) }
        }
        val saved = storage.readTranslation(chapter)
        var translations = saved
        val warnings = mutableListOf<String>()
        var untranslated = 0
        val hasValidSidecar = !saved.isNullOrEmpty() && saved.values.any { it.blocks.isNotEmpty() }

        if (rerenderOnly || reviewOnly) {
            onProgress("Loading translations", "Reading saved translation data", 0.1f)
            if (!hasValidSidecar) error("No saved translation found for this chapter. Translate it first.")
            if (reviewOnly) {
                if (settings.engine != TranslationEngine.GEMINI || settings.geminiApiKey.isBlank()) {
                    error("Review needs the Gemini translator and its API key.")
                }
                @Suppress("UNCHECKED_CAST")
                val editable = translations as MutableMap<String, PageTranslation>
                checkLeftovers(chapter, chapterDocument, settings, editable, onProgress)
                reviewPages(chapter, chapterDocument, settings, editable, onProgress)
                untranslated = translations.values.sumOf { page -> page.blocks.count { it.needsTranslation() } }
            }
        } else {
            if (!hasValidSidecar) {
                translations = pipeline.extractText(
                    chapter = chapter,
                    chapterDocument = chapterDocument,
                    sourceLanguage = settings.sourceLanguage,
                    onProgress = onProgress,
                )
                // Save the sidecar immediately to cache the OCR results
                storage.writeTranslation(chapter, translations)
            }

            val needsTranslation = translations!!.values.any { page ->
                page.blocks.any { it.needsTranslation() }
            }

            if (needsTranslation) {
                if (settings.engine == TranslationEngine.GEMINI && requireConfirmation) {
                    // Only lines that will really be sent are counted (skips translated,
                    // duplicate, watermark and remembered lines).
                    val (chunks, blocks) = pipeline.estimateGeminiChunks(
                        pages = translations,
                        sourceLanguage = settings.sourceLanguage,
                        targetLanguage = settings.targetLanguage,
                        geminiModelName = settings.geminiModelName,
                    )
                    if (chunks > 0) return ChapterRunOutcome.NeedsConfirmation(chunks, blocks)
                }

                // Gemini sees each page image; other engines only need the text.
                val pageImage = if (settings.engine == TranslationEngine.GEMINI) {
                    withContext(Dispatchers.IO) { pipeline.pageImageLoader(chapter, chapterDocument) }
                } else {
                    null
                }
                val glossary = storage.readGlossary(chapter.mangaName)
                val mangaContext = storage.readMangaContext(chapter.mangaName)
                val learnedCharacters = mutableListOf<String>()

                @Suppress("UNCHECKED_CAST")
                val mutableTranslations = translations as MutableMap<String, PageTranslation>
                // On-device inference can take many minutes; keep the CPU awake meanwhile.
                val wakeLock = if (settings.engine == TranslationEngine.ON_DEVICE) {
                    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Honyuka:OnDeviceTranslation")
                        .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
                } else {
                    null
                }
                try {
                    pipeline.translateText(
                        pageTranslations = mutableTranslations,
                        sourceLanguage = settings.sourceLanguage,
                        targetLanguage = settings.targetLanguage,
                        translationEngine = settings.engine,
                        geminiApiKey = settings.geminiApiKey,
                        geminiModelName = settings.geminiModelName,
                        deepLApiKey = settings.deepLApiKey,
                        localModel = settings.localModel,
                        localBackend = settings.localBackend,
                        googleFallback = settings.googleFallback,
                        pageImage = pageImage,
                        glossary = glossary,
                        mangaContext = mangaContext,
                        learnedCharacters = learnedCharacters,
                        proofread = !preferences.reviewPages(),
                        // Save after every request so a later failure never loses paid-for work.
                        onChunkSaved = { done, total ->
                            if (done > 0) storage.writeTranslation(chapter, mutableTranslations)
                            onChunkSaved(done, total)
                        },
                        onProgress = onProgress,
                    )?.let { fallbackNotice -> warnings += fallbackNotice }
                } finally {
                    wakeLock?.takeIf { it.isHeld }?.release()
                    if (settings.engine == TranslationEngine.GEMINI) {
                        storage.writeGlossary(chapter.mangaName, glossary)
                        // Saved even with nothing new, so the starter sheet becomes editable.
                        storage.writeMangaContext(
                            chapter.mangaName,
                            MangaContext.mergeCharacters(mangaContext, learnedCharacters),
                        )
                    }
                }
                onProgress("Saving translation", chapter.chapterDisplayName, 0.95f)
                storage.writeTranslation(chapter, translations)

                // Free and offline: find English the boxes didn't cover, before Gemini's review.
                checkLeftovers(chapter, chapterDocument, settings, mutableTranslations, onProgress, pageImage)

                if (settings.engine == TranslationEngine.GEMINI && preferences.reviewPages()) {
                    reviewPages(chapter, chapterDocument, settings, mutableTranslations, onProgress, pageImage)
                }

                // Never call a run "complete" while bubbles are still in the original language.
                untranslated = translations.values.sumOf { page -> page.blocks.count { it.needsTranslation() } }
                val total = translations.values.sumOf { it.blocks.size }
                if (failIfNothingTranslated && total > 0 && untranslated >= total) {
                    // Don't export an untranslated copy: it would look "done" and never be retried.
                    error(warnings.firstOrNull() ?: "Nothing was translated (all $total bubbles unchanged).")
                }
                if (untranslated > 0) {
                    warnings += "$untranslated of ${translations.values.sumOf { it.blocks.size }} bubbles are " +
                        "still in the original language. " +
                        "Tap Retry to redo only those."
                }
            }
        }

        onProgress("Rendering chapter", "Writing pages to the Local Source", 0.97f)
        exporter.exportToLocalSource(
            chapter = chapter,
            translations = translations!!,
            translatorLabel = if (rerenderOnly) null else settings.engine.label,
            targetLanguage = if (rerenderOnly) null else settings.targetLanguage,
        )

        return ChapterRunOutcome.Completed(
            warning = warnings.joinToString("\n").ifBlank { null },
            untranslatedBlocks = untranslated,
            totalBlocks = translations.values.sumOf { it.blocks.size },
        )
    }

    /**
     * Gemini looks at every translated page next to the original and fixes leftovers.
     * Saves the result; never throws for Gemini problems (the translation stays as it was).
     */
    private suspend fun reviewPages(
        chapter: ChapterEntry,
        chapterDocument: androidx.documentfile.provider.DocumentFile,
        settings: TranslationSettings,
        translations: MutableMap<String, PageTranslation>,
        onProgress: (title: String, detail: String, progress: Float) -> Unit,
        loadedImages: ((String) -> ByteArray?)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val pageImage = loadedImages ?: pipeline.pageImageLoader(chapter, chapterDocument)
        val engine = GeminiVisionEngine(
            sourceLanguage = settings.sourceLanguage,
            targetLanguage = settings.targetLanguage,
            apiKey = settings.geminiApiKey,
            modelName = settings.geminiModelName,
            pageImage = pageImage,
            glossary = storage.readGlossary(chapter.mangaName),
            onBatchDone = { _, _ -> },
            mangaContext = storage.readMangaContext(chapter.mangaName),
        )
        val changes = engine.review(
            pages = translations,
            rendered = { name, page -> pageImage(name)?.let { exporter.previewPage(it, name, page) } },
            onProgress = { done, total ->
                onProgress("Reviewing pages", "${chapter.chapterDisplayName}: part $done of $total", 0.95f)
            },
        )
        if (changes > 0) storage.writeTranslation(chapter, translations)
        changes
    }

    /**
     * Renders every translated page, reads it again with the phone's OCR and fixes what is
     * still in the original language (see [LeftoverCheck]). Costs no Gemini requests.
     * Never fails the run: any problem just skips the check.
     */
    private suspend fun checkLeftovers(
        chapter: ChapterEntry,
        chapterDocument: androidx.documentfile.provider.DocumentFile,
        settings: TranslationSettings,
        translations: MutableMap<String, PageTranslation>,
        onProgress: (title: String, detail: String, progress: Float) -> Unit,
        loadedImages: ((String) -> ByteArray?)? = null,
    ): Int = withContext(Dispatchers.IO) {
        var changes = 0
        runCatching {
            val pageImage = loadedImages ?: pipeline.pageImageLoader(chapter, chapterDocument)
            val pages = translations.entries.filter { (_, page) ->
                page.blocks.any { it.mode != TranslationBlock.MODE_KEEP && !it.needsTranslation() }
            }
            PageLineReader(settings.sourceLanguage).use { reader ->
                pages.forEachIndexed { index, (name, page) ->
                    if (TranslationStop.requested) return@use
                    onProgress(
                        "Checking pages",
                        "${chapter.chapterDisplayName}: page ${index + 1} of ${pages.size}",
                        0.95f,
                    )
                    val original = pageImage(name) ?: return@forEachIndexed
                    // Twice at most: a grown box can uncover the next leftover line.
                    for (pass in 0 until 2) {
                        val (bytes, drawn) = exporter.previewWithText(original, name, page)
                        val fixed = LeftoverCheck.apply(page, reader.read(bytes, name), drawn)
                        changes += fixed
                        if (fixed == 0) break
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Leftover check skipped", it) }
        if (changes > 0) storage.writeTranslation(chapter, translations)
        changes
    }

    private companion object {
        const val TAG = "ChapterTranslator"
        const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }
}
