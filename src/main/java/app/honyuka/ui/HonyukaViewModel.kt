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
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationEngine
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
        ),
    )
    val state: StateFlow<HonyukaUiState> = _state.asStateFlow()
    private val appContext = getApplication<Application>()

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val title = intent.getStringExtra(TranslationService.EXTRA_PROGRESS_TITLE) ?: return
            val detail = intent.getStringExtra(TranslationService.EXTRA_PROGRESS_DETAIL) ?: return
            val progress = intent.getFloatExtra(TranslationService.EXTRA_PROGRESS_VALUE, 0f)
            _state.update {
                it.copy(activeJob = ActiveJob(title, detail, progress))
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
                        isBusy = false,
                        activeChapterKey = null,
                        activeJob = ActiveJob("Completed", "Translation saved for $storageKey", 1f),
                        message = null,
                    )
                }
                val chapters = _state.value.chapters
                viewModelScope.launch(Dispatchers.IO) {
                    chapterCache.save(storage.rootSummary(), chapters)
                }
            } else {
                Toast.makeText(appContext, error ?: "Translation failed", Toast.LENGTH_LONG).show()
                _state.update {
                    it.copy(
                        isBusy = false,
                        activeChapterKey = null,
                        activeJob = null,
                        message = error ?: "Translation failed.",
                    )
                }
            }
        }
    }

    init {
        val progressFilter = IntentFilter(TranslationService.ACTION_PROGRESS)
        val resultFilter = IntentFilter(TranslationService.ACTION_RESULT)
        ContextCompat.registerReceiver(appContext, progressReceiver, progressFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(appContext, resultReceiver, resultFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onCleared() {
        appContext.unregisterReceiver(progressReceiver)
        appContext.unregisterReceiver(resultReceiver)
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
        val currentState = state.value
        val translationEngine = currentState.translationEngine
        val selectedApiKey = when (translationEngine) {
            TranslationEngine.GEMINI -> currentState.geminiApiKey
            TranslationEngine.DEEPL -> currentState.deepLApiKey
            else -> ""
        }
        if (translationEngine.requiresApiKey && selectedApiKey.isBlank()) {
            val message = "Add a ${translationEngine.label} API key in Settings before using this translator."
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            _state.update { it.copy(message = message) }
            return
        }

        Log.d(TAG, "Starting translation service for ${chapter.storageKey}")
        Toast.makeText(appContext, "Starting translation for ${chapter.chapterDisplayName}", Toast.LENGTH_SHORT).show()

        _state.update {
            it.copy(
                isBusy = true,
                message = null,
                activeChapterKey = chapter.storageKey,
                activeJob = ActiveJob(
                    title = "Starting translation",
                    detail = chapter.chapterDisplayName,
                    progress = 0.02f,
                ),
            )
        }

        val serviceIntent = TranslationService.buildIntent(
            context = appContext,
            chapter = chapter,
            sourceLanguage = currentState.sourceLanguage,
            targetLanguage = currentState.targetLanguage,
            translationEngine = translationEngine,
            geminiApiKey = currentState.geminiApiKey,
            geminiModelName = currentState.geminiModelName,
            deepLApiKey = currentState.deepLApiKey,
        )
        ContextCompat.startForegroundService(appContext, serviceIntent)
    }

    private companion object {
        const val TAG = "Honyuka"
        const val MISSING_DOWNLOADS_MESSAGE =
            "The selected folder is missing a `downloads` directory. " +
                "Pick the reader storage root, not an individual chapter folder."
    }
}
