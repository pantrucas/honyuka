package app.honyuka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.honyuka.MainActivity
import app.honyuka.R
import app.honyuka.data.HonyukaPreferences
import app.honyuka.data.HonyukaStorage
import app.honyuka.data.ScanUpdate
import app.honyuka.export.RenderedChapterExporter
import app.honyuka.model.ChapterEntry
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationEngine
import app.honyuka.model.sourceLanguageFor
import app.honyuka.translation.TranslationPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs translations one at a time off an internal queue.
 *
 * The queue is what makes batch translation possible: a second start used to cancel whatever was
 * already running, so firing one intent per chapter would only ever have translated the last one.
 * Enqueuing now appends, and the service stops only once the queue drains.
 */
class TranslationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val queue = ArrayDeque<PendingChapter>()
    private var worker: Job? = null
    private var enqueued = 0
    private var completed = 0

    private data class PendingChapter(
        val chapter: ChapterEntry,
        val sourceLanguage: SourceLanguage,
        val settings: JobSettings,
    )

    private data class JobSettings(
        val fallbackSourceLanguage: SourceLanguage,
        val targetLanguage: TargetLanguage,
        val engine: TranslationEngine,
        val geminiApiKey: String,
        val geminiModelName: String,
        val deepLApiKey: String,
    )

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_CANCEL_QUEUE) {
            cancelQueue()
            return START_NOT_STICKY
        }

        // Must happen synchronously: resolving a batch takes a moment, and the system expects the
        // notification within a few seconds of startForegroundService().
        startForeground(NOTIFICATION_ID, buildNotification("Preparing", "Collecting chapters", 0))

        val settings = intent.toJobSettings()
        scope.launch {
            val pending = runCatching { resolvePending(intent, settings) }
                .onFailure { Log.e(TAG, "Unable to resolve the translation queue", it) }
                .getOrDefault(emptyList())

            if (pending.isEmpty()) {
                broadcastQueue(activeKey = null, finished = true)
                stopSelf()
                return@launch
            }

            synchronized(lock) {
                queue.addAll(pending)
                enqueued += pending.size
            }
            broadcastQueue(activeKey = null)
            startWorkerIfIdle()
        }

        return START_NOT_STICKY
    }

    private suspend fun resolvePending(intent: Intent, settings: JobSettings): List<PendingChapter> {
        val preferences = HonyukaPreferences(this)
        val groups = preferences.mangaGroups()

        val chapters = when (intent.action) {
            ACTION_ENQUEUE_MANGA -> {
                val mangaKey = intent.getStringExtra(EXTRA_MANGA_KEY) ?: return emptyList()
                TranslationQueuePlan.forManga(scanChapters(preferences), mangaKey)
            }

            ACTION_ENQUEUE_GROUP -> {
                val groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: return emptyList()
                val group = groups.firstOrNull { it.id == groupId } ?: return emptyList()
                TranslationQueuePlan.forGroup(scanChapters(preferences), group)
            }

            // A single chapter was asked for explicitly, so honour it even if already translated.
            else -> listOfNotNull(intent.toChapterEntry())
        }

        return chapters.map { chapter ->
            PendingChapter(
                chapter = chapter,
                sourceLanguage = groups.sourceLanguageFor(
                    mangaKey = chapter.mangaKey,
                    fallback = settings.fallbackSourceLanguage,
                ),
                settings = settings,
            )
        }
    }

    /**
     * Re-derives the chapter list here rather than shipping it in the Intent: a whole group would
     * approach the Binder transaction limit and throw TransactionTooLargeException.
     */
    private suspend fun scanChapters(preferences: HonyukaPreferences): List<ChapterEntry> {
        val storage = HonyukaStorage(this, preferences)
        val collected = mutableListOf<ChapterEntry>()
        storage.scanChapters().collect { update ->
            if (update is ScanUpdate.Batch) {
                collected += update.chapters
            }
        }
        return collected
    }

    private fun startWorkerIfIdle() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                broadcastQueue(activeKey = next.chapter.storageKey)
                runTranslation(next)
                synchronized(lock) { completed++ }
            }
            if (synchronized(lock) { queue.isEmpty() }) {
                finishQueue()
            }
        }
    }

    private fun finishQueue() {
        broadcastQueue(activeKey = null, finished = true)
        synchronized(lock) {
            enqueued = 0
            completed = 0
        }
        stopSelf()
    }

    private fun cancelQueue() {
        synchronized(lock) {
            queue.clear()
            enqueued = 0
            completed = 0
        }
        worker?.cancel()
        worker = null
        broadcastQueue(activeKey = null, finished = true)
        stopSelf()
    }

    private suspend fun runTranslation(pending: PendingChapter) {
        val chapter = pending.chapter
        val settings = pending.settings
        val preferences = HonyukaPreferences(this)
        val storage = HonyukaStorage(this, preferences)
        val pipeline = TranslationPipeline(this)
        val exporter = RenderedChapterExporter(this, storage)
        val position = synchronized(lock) { "${completed + 1}/$enqueued" }

        try {
            val chapterDocument = withContext(Dispatchers.IO) {
                storage.resolveOriginalChapterDocument(chapter)
            } ?: run {
                updateNotification("Error", "Could not open ${chapter.chapterDisplayName}", 0)
                broadcastResult(chapter, success = false, error = "Unable to open chapter")
                return
            }

            val translations = pipeline.translateChapter(
                chapter = chapter,
                chapterDocument = chapterDocument,
                sourceLanguage = pending.sourceLanguage,
                targetLanguage = settings.targetLanguage,
                translationEngine = settings.engine,
                geminiApiKey = settings.geminiApiKey,
                geminiModelName = settings.geminiModelName,
                deepLApiKey = settings.deepLApiKey,
            ) { title, detail, progress ->
                updateNotification("$title ($position)", detail, (progress * 100).toInt())
                broadcastProgress(chapter, title, detail, progress)
            }

            updateNotification("Saving translation ($position)", chapter.chapterDisplayName, 95)
            storage.writeTranslation(chapter, translations)

            updateNotification("Rendering chapter ($position)", chapter.chapterDisplayName, 97)
            exporter.exportTranslatedSibling(chapter, translations)

            updateNotification("Exporting to Local Source ($position)", chapter.chapterDisplayName, 99)
            exporter.exportToLocalSource(chapter, translations)

            updateNotification("Completed ($position)", chapter.chapterDisplayName, 100)
            broadcastResult(chapter, success = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // One bad chapter must not take the rest of the batch down with it.
            Log.e(TAG, "Translation failed for ${chapter.storageKey}", e)
            updateNotification("Translation failed", e.message ?: "Unknown error", 0)
            broadcastResult(chapter, success = false, error = e.message)
        }
    }

    private fun updateNotification(title: String, detail: String, progress: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, detail, progress))
    }

    private fun buildNotification(title: String, detail: String, progress: Int): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(progress < 100)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun broadcastProgress(chapter: ChapterEntry, title: String, detail: String, progress: Float) {
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STORAGE_KEY, chapter.storageKey)
            putExtra(EXTRA_PROGRESS_TITLE, title)
            putExtra(EXTRA_PROGRESS_DETAIL, detail)
            putExtra(EXTRA_PROGRESS_VALUE, progress)
        })
    }

    private fun broadcastResult(chapter: ChapterEntry, success: Boolean, error: String? = null) {
        sendBroadcast(Intent(ACTION_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_STORAGE_KEY, chapter.storageKey)
            putExtra(EXTRA_RESULT_SUCCESS, success)
            if (error != null) putExtra(EXTRA_RESULT_ERROR, error)
        })
    }

    private fun broadcastQueue(activeKey: String?, finished: Boolean = false) {
        val done: Int
        val total: Int
        synchronized(lock) {
            done = completed
            total = enqueued
        }
        sendBroadcast(Intent(ACTION_QUEUE).apply {
            setPackage(packageName)
            putExtra(EXTRA_QUEUE_DONE, done)
            putExtra(EXTRA_QUEUE_TOTAL, total)
            putExtra(EXTRA_QUEUE_FINISHED, finished)
            if (activeKey != null) putExtra(EXTRA_STORAGE_KEY, activeKey)
        })
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Translation Progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows translation progress for manga chapters"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranslationService"
        private const val CHANNEL_ID = "honyuka_translation"
        private const val NOTIFICATION_ID = 42

        const val ACTION_PROGRESS = "app.honyuka.TRANSLATION_PROGRESS"
        const val ACTION_RESULT = "app.honyuka.TRANSLATION_RESULT"
        const val ACTION_QUEUE = "app.honyuka.TRANSLATION_QUEUE"
        const val EXTRA_STORAGE_KEY = "storage_key"
        const val EXTRA_PROGRESS_TITLE = "progress_title"
        const val EXTRA_PROGRESS_DETAIL = "progress_detail"
        const val EXTRA_PROGRESS_VALUE = "progress_value"
        const val EXTRA_RESULT_SUCCESS = "result_success"
        const val EXTRA_RESULT_ERROR = "result_error"
        const val EXTRA_QUEUE_DONE = "queue_done"
        const val EXTRA_QUEUE_TOTAL = "queue_total"
        const val EXTRA_QUEUE_FINISHED = "queue_finished"

        private const val ACTION_TRANSLATE_CHAPTER = "app.honyuka.ENQUEUE_CHAPTER"
        private const val ACTION_ENQUEUE_MANGA = "app.honyuka.ENQUEUE_MANGA"
        private const val ACTION_ENQUEUE_GROUP = "app.honyuka.ENQUEUE_GROUP"
        private const val ACTION_CANCEL_QUEUE = "app.honyuka.CANCEL_QUEUE"

        private const val EXTRA_MANGA_KEY = "manga_key"
        private const val EXTRA_GROUP_ID = "group_id"
        private const val EXTRA_SOURCE_NAME = "source_name"
        private const val EXTRA_MANGA_NAME = "manga_name"
        private const val EXTRA_CHAPTER_DISPLAY = "chapter_display"
        private const val EXTRA_CHAPTER_BASE = "chapter_base"
        private const val EXTRA_CHAPTER_URI = "chapter_uri"
        private const val EXTRA_THUMBNAIL_URI = "thumbnail_uri"
        private const val EXTRA_IS_CBZ = "is_cbz"
        private const val EXTRA_TRANSLATED = "translated"
        private const val EXTRA_SOURCE_LANG = "source_lang"
        private const val EXTRA_TARGET_LANG = "target_lang"
        private const val EXTRA_ENGINE = "engine"
        private const val EXTRA_GEMINI_API_KEY = "gemini_api_key"
        private const val EXTRA_GEMINI_MODEL = "gemini_model"
        private const val EXTRA_DEEPL_API_KEY = "deepl_api_key"

        fun buildIntent(
            context: Context,
            chapter: ChapterEntry,
            sourceLanguage: SourceLanguage,
            targetLanguage: TargetLanguage,
            translationEngine: TranslationEngine,
            geminiApiKey: String,
            geminiModelName: String,
            deepLApiKey: String,
        ): Intent {
            return settingsIntent(
                context, ACTION_TRANSLATE_CHAPTER, sourceLanguage, targetLanguage,
                translationEngine, geminiApiKey, geminiModelName, deepLApiKey,
            ).apply {
                putExtra(EXTRA_SOURCE_NAME, chapter.sourceName)
                putExtra(EXTRA_MANGA_NAME, chapter.mangaName)
                putExtra(EXTRA_CHAPTER_DISPLAY, chapter.chapterDisplayName)
                putExtra(EXTRA_CHAPTER_BASE, chapter.chapterBaseName)
                putExtra(EXTRA_CHAPTER_URI, chapter.chapterUri.toString())
                putExtra(EXTRA_THUMBNAIL_URI, chapter.thumbnailUri?.toString())
                putExtra(EXTRA_IS_CBZ, chapter.isCbz)
                putExtra(EXTRA_TRANSLATED, chapter.translated)
            }
        }

        fun buildMangaIntent(
            context: Context,
            mangaKey: String,
            sourceLanguage: SourceLanguage,
            targetLanguage: TargetLanguage,
            translationEngine: TranslationEngine,
            geminiApiKey: String,
            geminiModelName: String,
            deepLApiKey: String,
        ): Intent {
            return settingsIntent(
                context, ACTION_ENQUEUE_MANGA, sourceLanguage, targetLanguage,
                translationEngine, geminiApiKey, geminiModelName, deepLApiKey,
            ).apply { putExtra(EXTRA_MANGA_KEY, mangaKey) }
        }

        fun buildGroupIntent(
            context: Context,
            groupId: String,
            sourceLanguage: SourceLanguage,
            targetLanguage: TargetLanguage,
            translationEngine: TranslationEngine,
            geminiApiKey: String,
            geminiModelName: String,
            deepLApiKey: String,
        ): Intent {
            return settingsIntent(
                context, ACTION_ENQUEUE_GROUP, sourceLanguage, targetLanguage,
                translationEngine, geminiApiKey, geminiModelName, deepLApiKey,
            ).apply { putExtra(EXTRA_GROUP_ID, groupId) }
        }

        fun buildCancelIntent(context: Context): Intent {
            return Intent(context, TranslationService::class.java).setAction(ACTION_CANCEL_QUEUE)
        }

        private fun settingsIntent(
            context: Context,
            action: String,
            sourceLanguage: SourceLanguage,
            targetLanguage: TargetLanguage,
            translationEngine: TranslationEngine,
            geminiApiKey: String,
            geminiModelName: String,
            deepLApiKey: String,
        ): Intent {
            return Intent(context, TranslationService::class.java).apply {
                setAction(action)
                putExtra(EXTRA_SOURCE_LANG, sourceLanguage.name)
                putExtra(EXTRA_TARGET_LANG, targetLanguage.name)
                putExtra(EXTRA_ENGINE, translationEngine.name)
                putExtra(EXTRA_GEMINI_API_KEY, geminiApiKey)
                putExtra(EXTRA_GEMINI_MODEL, geminiModelName)
                putExtra(EXTRA_DEEPL_API_KEY, deepLApiKey)
            }
        }

        private fun Intent.toJobSettings(): JobSettings {
            return JobSettings(
                fallbackSourceLanguage = getStringExtra(EXTRA_SOURCE_LANG)
                    ?.let { name -> SourceLanguage.entries.firstOrNull { it.name == name } }
                    ?: SourceLanguage.JAPANESE,
                targetLanguage = getStringExtra(EXTRA_TARGET_LANG)
                    ?.let { name -> TargetLanguage.entries.firstOrNull { it.name == name } }
                    ?: TargetLanguage.ENGLISH,
                engine = getStringExtra(EXTRA_ENGINE)
                    ?.let { name -> TranslationEngine.entries.firstOrNull { it.name == name } }
                    ?: TranslationEngine.GEMINI,
                geminiApiKey = getStringExtra(EXTRA_GEMINI_API_KEY).orEmpty(),
                geminiModelName = getStringExtra(EXTRA_GEMINI_MODEL).orEmpty(),
                deepLApiKey = getStringExtra(EXTRA_DEEPL_API_KEY).orEmpty(),
            )
        }

        private fun Intent.toChapterEntry(): ChapterEntry? {
            val sourceName = getStringExtra(EXTRA_SOURCE_NAME) ?: return null
            val mangaName = getStringExtra(EXTRA_MANGA_NAME) ?: return null
            val chapterDisplay = getStringExtra(EXTRA_CHAPTER_DISPLAY) ?: return null
            val chapterBase = getStringExtra(EXTRA_CHAPTER_BASE) ?: return null
            val chapterUri = getStringExtra(EXTRA_CHAPTER_URI)?.let { android.net.Uri.parse(it) } ?: return null
            val thumbnailUri = getStringExtra(EXTRA_THUMBNAIL_URI)?.let { android.net.Uri.parse(it) }
            return ChapterEntry(
                sourceName = sourceName,
                mangaName = mangaName,
                chapterDisplayName = chapterDisplay,
                chapterBaseName = chapterBase,
                chapterUri = chapterUri,
                thumbnailUri = thumbnailUri,
                isCbz = getBooleanExtra(EXTRA_IS_CBZ, false),
                translated = getBooleanExtra(EXTRA_TRANSLATED, false),
            )
        }
    }
}
