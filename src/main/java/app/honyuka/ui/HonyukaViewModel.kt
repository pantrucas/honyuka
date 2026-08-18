package app.honyuka.ui

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.honyuka.data.ChapterCache
import app.honyuka.data.HonyukaPreferences
import app.honyuka.data.HonyukaStorage
import app.honyuka.data.ScanUpdate
import app.honyuka.model.ActiveJob
import app.honyuka.model.ChapterEntry
import app.honyuka.model.HonyukaUiState
import app.honyuka.model.MangaGroup
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationEngine
import app.honyuka.model.sourceLanguageFor
import app.honyuka.model.withMembers
import app.honyuka.service.TranslationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class HonyukaViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val preferences = HonyukaPreferences(application)
    private val storage = HonyukaStorage(application, preferences)
    private val chapterCache = ChapterCache(application)
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(
        HonyukaUiState(
            rootSummary = storage.rootSummary(),
            // Reading the tree is a ContentProvider query, so it is resolved off the main thread
            // in refresh(). Assume a saved root is usable until the scan says otherwise.
            hasCompatibleRoot = storage.rootSummary() != null,
            sourceLanguage = preferences.sourceLanguage(),
            targetLanguage = preferences.targetLanguage(),
            translationEngine = preferences.translationEngine(),
            geminiModelName = preferences.geminiModelName(),
            geminiApiKey = preferences.geminiApiKey(),
            deepLApiKey = preferences.deepLApiKey(),
            groups = preferences.mangaGroups(),
        ),
    )
    val state: StateFlow<HonyukaUiState> = _state.asStateFlow()
    private val appContext = getApplication<Application>()

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val title = intent.getStringExtra(TranslationService.EXTRA_PROGRESS_TITLE) ?: return
            val detail = intent.getStringExtra(TranslationService.EXTRA_PROGRESS_DETAIL) ?: return
            val progress = intent.getFloatExtra(TranslationService.EXTRA_PROGRESS_VALUE, 0f)
            val storageKey = intent.getStringExtra(TranslationService.EXTRA_STORAGE_KEY)
            _state.update {
                it.copy(
                    activeJob = ActiveJob(title, detail, progress),
                    activeChapterKey = storageKey ?: it.activeChapterKey,
                )
            }
        }
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(TranslationService.EXTRA_RESULT_SUCCESS, false)
            val error = intent.getStringExtra(TranslationService.EXTRA_RESULT_ERROR)
            val storageKey = intent.getStringExtra(TranslationService.EXTRA_STORAGE_KEY).orEmpty()

            if (success) {
                Toast.makeText(appContext, "Translation completed", Toast.LENGTH_SHORT).show()
                // The translated output is filtered out of the listing, so the only thing the scan
                // would discover is this chapter's flag. Flipping it in place avoids a full rescan.
                _state.update { current ->
                    current.copy(
                        chapters = current.chapters.map { chapter ->
                            if (chapter.storageKey == storageKey) chapter.copy(translated = true) else chapter
                        },
                        message = null,
                    )
                }
                val chapters = _state.value.chapters
                viewModelScope.launch(Dispatchers.IO) {
                    chapterCache.save(storage.rootSummary(), chapters)
                }
            } else {
                Toast.makeText(appContext, error ?: "Translation failed", Toast.LENGTH_LONG).show()
                _state.update { it.copy(message = error ?: "Translation failed.") }
            }
        }
    }

    /**
     * Owns [HonyukaUiState.isBusy] for the whole batch. Letting each chapter result clear it would
     * re-enable every button halfway through a queue.
     */
    private val queueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val done = intent.getIntExtra(TranslationService.EXTRA_QUEUE_DONE, 0)
            val total = intent.getIntExtra(TranslationService.EXTRA_QUEUE_TOTAL, 0)
            val finished = intent.getBooleanExtra(TranslationService.EXTRA_QUEUE_FINISHED, false)
            val activeKey = intent.getStringExtra(TranslationService.EXTRA_STORAGE_KEY)

            _state.update { current ->
                if (finished) {
                    current.copy(
                        isBusy = false,
                        activeChapterKey = null,
                        activeJob = null,
                        queueDone = 0,
                        queueTotal = 0,
                        message = if (total == 0) "Nothing left to translate there." else current.message,
                    )
                } else {
                    current.copy(
                        isBusy = true,
                        queueDone = done,
                        queueTotal = total,
                        activeChapterKey = activeKey ?: current.activeChapterKey,
                    )
                }
            }
        }
    }

    init {
        val progressFilter = IntentFilter(TranslationService.ACTION_PROGRESS)
        val resultFilter = IntentFilter(TranslationService.ACTION_RESULT)
        val queueFilter = IntentFilter(TranslationService.ACTION_QUEUE)
        ContextCompat.registerReceiver(appContext, progressReceiver, progressFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(appContext, resultReceiver, resultFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(appContext, queueReceiver, queueFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onCleared() {
        appContext.unregisterReceiver(progressReceiver)
        appContext.unregisterReceiver(resultReceiver)
        appContext.unregisterReceiver(queueReceiver)
        super.onCleared()
    }

    fun onRootSelected(uri: android.net.Uri) {
        storage.saveRoot(uri)
        chapterCache.clear()
        _state.update {
            it.copy(
                rootSummary = storage.rootSummary(),
                chapters = emptyList(),
                message = null,
            )
        }
        refresh(force = true)
    }

    fun onSourceLanguageSelected(language: SourceLanguage) {
        preferences.saveSourceLanguage(language)
        _state.update { it.copy(sourceLanguage = language) }
    }

    fun onTargetLanguageSelected(language: TargetLanguage) {
        preferences.saveTargetLanguage(language)
        _state.update { it.copy(targetLanguage = language) }
    }

    fun onTranslationEngineSelected(engine: TranslationEngine) {
        preferences.saveTranslationEngine(engine)
        _state.update { it.copy(translationEngine = engine) }
    }

    fun onGeminiModelNameChanged(modelName: String) {
        preferences.saveGeminiModelName(modelName)
        _state.update { it.copy(geminiModelName = modelName) }
    }

    fun onGeminiApiKeyChanged(apiKey: String) {
        preferences.saveGeminiApiKey(apiKey)
        _state.update { it.copy(geminiApiKey = apiKey) }
    }

    fun onDeepLApiKeyChanged(apiKey: String) {
        preferences.saveDeepLApiKey(apiKey)
        _state.update { it.copy(deepLApiKey = apiKey) }
    }

    fun refresh(force: Boolean = false) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            Log.d(TAG, "Refreshing chapter list (force=$force)")
            val rootUri = storage.rootSummary()

            if (!force) {
                val cached = withContext(Dispatchers.IO) { chapterCache.load(rootUri) }
                if (!cached.isNullOrEmpty()) {
                    _state.update { it.copy(chapters = cached, message = null) }
                }
            }

            // Starting from an empty list, batches are published as they arrive so the user sees
            // chapters within the first second. With a list already on screen — restored from cache
            // or left by a previous scan — the rescan accumulates quietly and swaps at the end, so
            // the library never shrinks and regrows.
            val publishIncrementally = _state.value.chapters.isEmpty()
            val scanTitle = if (publishIncrementally) "Scanning downloads" else "Updating library"
            _state.update {
                it.copy(
                    isBusy = true,
                    message = null,
                    activeJob = ActiveJob(scanTitle, "Opening reader storage", 0f),
                )
            }

            val collected = mutableListOf<ChapterEntry>()
            try {
                val compatibleRoot = withContext(Dispatchers.IO) { storage.hasCompatibleRoot() }
                _state.update { it.copy(hasCompatibleRoot = compatibleRoot) }

                storage.scanChapters().collect { update ->
                    when (update) {
                        is ScanUpdate.Batch -> {
                            collected += update.chapters
                            val published = if (publishIncrementally) collected.toList() else null
                            _state.update {
                                it.copy(
                                    chapters = published ?: it.chapters,
                                    activeJob = ActiveJob(
                                        title = scanTitle,
                                        detail = "${update.sourceName} / ${update.mangaName} · " +
                                            "${update.totalSoFar} chapters found",
                                        progress = update.progress,
                                    ),
                                )
                            }
                        }

                        is ScanUpdate.Done -> {
                            _state.update {
                                it.copy(
                                    chapters = collected.toList(),
                                    isBusy = false,
                                    activeJob = null,
                                    activeChapterKey = null,
                                    message = if (compatibleRoot) null else MISSING_DOWNLOADS_MESSAGE,
                                )
                            }
                            withContext(Dispatchers.IO) { chapterCache.save(rootUri, collected) }
                            Log.d(TAG, "Scan finished with ${update.total} chapters")
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        activeJob = null,
                        activeChapterKey = null,
                        message = error.message ?: "Unable to scan the selected root.",
                    )
                }
                Log.e(TAG, "Refresh failed", error)
            }
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun translateChapter(chapter: ChapterEntry) {
        val current = state.value
        missingApiKeyMessage(current)?.let { message ->
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            _state.update { it.copy(message = message) }
            return
        }

        Log.d(TAG, "Enqueuing translation for ${chapter.storageKey}")
        Toast.makeText(appContext, "Starting translation for ${chapter.chapterDisplayName}", Toast.LENGTH_SHORT).show()

        _state.update {
            it.copy(
                isBusy = true,
                message = null,
                activeChapterKey = chapter.storageKey,
                activeJob = ActiveJob("Starting translation", chapter.chapterDisplayName, 0.02f),
            )
        }

        startQueue(
            TranslationService.buildIntent(
                context = appContext,
                chapter = chapter,
                sourceLanguage = current.groups.sourceLanguageFor(chapter.mangaKey, current.sourceLanguage),
                targetLanguage = current.targetLanguage,
                translationEngine = current.translationEngine,
                geminiApiKey = current.geminiApiKey,
                geminiModelName = current.geminiModelName,
                deepLApiKey = current.deepLApiKey,
            ),
        )
    }

    fun translateManga(mangaKey: String) {
        val current = state.value
        missingApiKeyMessage(current)?.let { message ->
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            _state.update { it.copy(message = message) }
            return
        }

        val pending = pendingCountForManga(mangaKey)
        if (pending == 0) {
            Toast.makeText(appContext, "Nothing left to translate here", Toast.LENGTH_SHORT).show()
            return
        }

        announceQueue(pending)
        startQueue(
            TranslationService.buildMangaIntent(
                context = appContext,
                mangaKey = mangaKey,
                sourceLanguage = current.groups.sourceLanguageFor(mangaKey, current.sourceLanguage),
                targetLanguage = current.targetLanguage,
                translationEngine = current.translationEngine,
                geminiApiKey = current.geminiApiKey,
                geminiModelName = current.geminiModelName,
                deepLApiKey = current.deepLApiKey,
            ),
        )
    }

    fun translateGroup(groupId: String) {
        val current = state.value
        missingApiKeyMessage(current)?.let { message ->
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            _state.update { it.copy(message = message) }
            return
        }

        val group = current.groups.firstOrNull { it.id == groupId } ?: return
        val pending = pendingCountForGroup(groupId)
        if (pending == 0) {
            Toast.makeText(appContext, "Nothing left to translate in ${group.name}", Toast.LENGTH_SHORT).show()
            return
        }

        announceQueue(pending)
        startQueue(
            TranslationService.buildGroupIntent(
                context = appContext,
                groupId = groupId,
                sourceLanguage = group.sourceLanguage ?: current.sourceLanguage,
                targetLanguage = current.targetLanguage,
                translationEngine = current.translationEngine,
                geminiApiKey = current.geminiApiKey,
                geminiModelName = current.geminiModelName,
                deepLApiKey = current.deepLApiKey,
            ),
        )
    }

    fun cancelQueue() {
        // Plain startService: the cancel path never calls startForeground, so starting it as a
        // foreground service would trip the "did not start in time" crash.
        appContext.startService(TranslationService.buildCancelIntent(appContext))
        _state.update {
            it.copy(
                isBusy = false,
                activeJob = null,
                activeChapterKey = null,
                queueDone = 0,
                queueTotal = 0,
            )
        }
    }

    /** Chapters still untranslated, which is what a batch would actually queue. */
    fun pendingCountForManga(mangaKey: String): Int =
        state.value.chapters.count { it.mangaKey == mangaKey && !it.translated }

    fun pendingCountForGroup(groupId: String): Int {
        val keys = state.value.groups.firstOrNull { it.id == groupId }?.mangaKeys?.toSet()
            ?: return 0
        return state.value.chapters.count { it.mangaKey in keys && !it.translated }
    }

    fun createGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        persistGroups(state.value.groups + MangaGroup(id = UUID.randomUUID().toString(), name = trimmed))
    }

    fun updateGroup(
        groupId: String,
        name: String,
        sourceLanguage: SourceLanguage?,
        mangaKeys: List<String>,
    ) {
        val renamed = state.value.groups.map { group ->
            if (group.id == groupId) {
                group.copy(
                    name = name.trim().ifEmpty { group.name },
                    sourceLanguageName = sourceLanguage?.name,
                )
            } else {
                group
            }
        }
        // withMembers keeps membership exclusive, so a manga moved here leaves its old group.
        persistGroups(renamed.withMembers(groupId, mangaKeys))
    }

    fun deleteGroup(groupId: String) {
        persistGroups(state.value.groups.filterNot { it.id == groupId })
    }

    private fun persistGroups(groups: List<MangaGroup>) {
        preferences.saveMangaGroups(groups)
        _state.update { it.copy(groups = groups) }
    }

    private fun announceQueue(pending: Int) {
        _state.update {
            it.copy(
                isBusy = true,
                message = null,
                queueDone = 0,
                queueTotal = pending,
                activeJob = ActiveJob("Queuing translations", "$pending chapters pending", 0f),
            )
        }
    }

    private fun startQueue(serviceIntent: Intent) {
        ContextCompat.startForegroundService(appContext, serviceIntent)
    }

    private fun missingApiKeyMessage(current: HonyukaUiState): String? {
        val engine = current.translationEngine
        val key = when (engine) {
            TranslationEngine.GEMINI -> current.geminiApiKey
            TranslationEngine.DEEPL -> current.deepLApiKey
            else -> ""
        }
        return when {
            engine.requiresApiKey && key.isBlank() ->
                "Add a ${engine.label} API key in Settings before using this translator."
            else -> null
        }
    }

    private companion object {
        const val TAG = "Honyuka"
        const val MISSING_DOWNLOADS_MESSAGE =
            "The selected folder is missing a `downloads` directory. " +
                "Pick the reader storage root, not an individual chapter folder."
    }
}
