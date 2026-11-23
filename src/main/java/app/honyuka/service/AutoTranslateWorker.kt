package app.honyuka.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.honyuka.MainActivity
import app.honyuka.R
import app.honyuka.data.HonyukaPreferences
import app.honyuka.data.HonyukaStorage
import app.honyuka.model.ChapterEntry
import app.honyuka.translation.TranslationStop
import app.honyuka.translation.TranslationStoppedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Schedules / cancels the automatic translation work. */
object AutoTranslateScheduler {
    private const val PERIODIC = "auto-translate"
    private const val CONTINUE = "auto-translate-continue"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Turns the every-12-hours run on or off. Safe to call on every app start. */
    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<AutoTranslateWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(CONTINUE)
        }
    }

    /** One run as soon as the constraints allow ("Run now"). */
    fun runNow(context: Context) = enqueueOnce(context, delayMinutes = 0, policy = ExistingWorkPolicy.KEEP)

    /** Picks up the remaining chapters after a run used up its time slice. */
    fun continueLater(context: Context) =
        enqueueOnce(context, delayMinutes = 1, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueueOnce(context: Context, delayMinutes: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AutoTranslateWorker>()
            .setConstraints(constraints())
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CONTINUE, policy, request)
    }
}

/**
 * Translates new, untranslated chapters of the mangas the user opted in to, one at a time,
 * with the same settings as a manual run. Runs in the background without a foreground
 * service, so each run stops starting new chapters after [TIME_BUDGET_MS] and hands the
 * rest to a follow-up run.
 */
class AutoTranslateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val preferences = HonyukaPreferences(context)

    override suspend fun doWork(): Result {
        if (TranslationService.runStatus.value != null || running.value) {
            Log.i(TAG, "Skipped: a translation is already running")
            return Result.success()
        }
        val optedIn = preferences.autoTranslateMangas()
        if (optedIn.isEmpty()) {
            record("No manga has auto-translate turned on", notify = false)
            return Result.success()
        }

        running.value = true
        try {
            return translatePending(optedIn)
        } finally {
            running.value = false
        }
    }

    private suspend fun translatePending(optedIn: Set<String>): Result {
        val startedAt = System.currentTimeMillis()
        val storage = HonyukaStorage(applicationContext, preferences)
        val chapters = withContext(Dispatchers.IO) {
            storage.scanChapters(
                onlyLanguages = if (preferences.filterSourcesByLanguage()) preferences.sourceLanguages() else null,
            )
        }

        // Forget failures older than 11 h so they are retried on the next 12 h run, but
        // not by the immediate follow-up runs (which would loop on the same broken chapter).
        val failures = preferences.autoFailures()
            .filterValues { startedAt - it < FAILURE_RETRY_AFTER_MS }
            .toMutableMap()

        val pending = chapters.filter { chapter ->
            !chapter.translated &&
                "${chapter.sourceName}/${chapter.mangaName}" in optedIn &&
                chapter.storageKey !in failures
        }
        if (pending.isEmpty()) {
            preferences.saveAutoFailures(failures)
            record("Nothing new to translate", notify = false)
            return Result.success()
        }

        val translator = ChapterTranslator(applicationContext)
        val done = mutableListOf<ChapterEntry>()
        val warnings = mutableListOf<String>()
        var failure: String? = null
        var fatal = false
        var leftover = false

        for (chapter in pending) {
            if (System.currentTimeMillis() - startedAt > TIME_BUDGET_MS) {
                leftover = true
                break
            }
            if (TranslationService.runStatus.value != null) {
                // The user started a manual translation meanwhile; let it have the API quota.
                leftover = true
                break
            }
            TranslationStop.reset()
            try {
                val outcome = translator.run(
                    chapter = chapter,
                    settings = TranslationSettings.fromPreferences(preferences, chapter),
                    requireConfirmation = false,
                    failIfNothingTranslated = true,
                ) { _, _, _ -> }
                if (outcome is ChapterRunOutcome.Completed) {
                    done += chapter
                    failures.remove(chapter.storageKey)
                    if (outcome.untranslatedBlocks > 0) {
                        warnings += "${chapter.chapterDisplayName}: ${outcome.untranslatedBlocks} " +
                            "of ${outcome.totalBlocks} bubbles left untranslated"
                    }
                }
            } catch (e: CancellationException) {
                if (!isStopped) {
                    // An internal timeout, not WorkManager: count it as this chapter failing.
                    failure = "${chapter.mangaName} ${chapter.chapterDisplayName}: ${e.message ?: "timed out"}"
                    failures[chapter.storageKey] = System.currentTimeMillis()
                    continue
                }
                // WorkManager stopped us (time limit, constraints lost). Finished parts are saved
                // in the sidecar, so the follow-up run resumes where this one ended.
                preferences.saveAutoFailures(failures)
                AutoTranslateScheduler.continueLater(applicationContext)
                record(summary(done, "interrupted by the system", leftover = true), notify = done.isNotEmpty())
                throw e
            } catch (e: TranslationStoppedException) {
                failure = "stopped"
                break
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "Auto-translate failed for ${chapter.storageKey}", e)
                failure = "${chapter.mangaName} ${chapter.chapterDisplayName}: $message"
                if (isFatal(message)) {
                    fatal = true
                    break
                }
                failures[chapter.storageKey] = System.currentTimeMillis()
            }
        }

        preferences.saveAutoFailures(failures)
        if (leftover && !fatal) AutoTranslateScheduler.continueLater(applicationContext)

        val text = buildString {
            append(summary(done, failure, leftover && !fatal))
            if (warnings.isNotEmpty()) append("\n").append(warnings.joinToString("\n"))
        }
        record(text, notify = done.isNotEmpty() || failure != null)
        return Result.success()
    }

    /** Errors that would fail every remaining chapter too. */
    private fun isFatal(message: String): Boolean {
        val lower = message.lowercase(Locale.ROOT)
        return ("quota exhausted" in lower && !preferences.googleFallback()) ||
            "invalid gemini api key" in lower ||
            "authentication failed" in lower ||
            "api key" in lower && "missing" in lower
    }

    private fun summary(done: List<ChapterEntry>, failure: String?, leftover: Boolean): String = buildString {
        if (done.isNotEmpty()) {
            append("Translated ${done.size} chapter")
            if (done.size > 1) append("s")
            append(": ")
            append(
                done.groupBy { it.mangaName }.entries.joinToString("; ") { (manga, list) ->
                    "$manga ${list.joinToString(", ") { shortChapterName(it.chapterDisplayName) }}"
                },
            )
        } else {
            append("No chapter translated")
        }
        if (failure != null) append(" — failed: ").append(failure)
        if (leftover) append(" (more pending, continuing shortly)")
    }

    private fun record(text: String, notify: Boolean) {
        val time = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        preferences.saveLastAutoRun("$time — $text")
        lastRun.value = "$time — $text"
        if (notify) notifyUser(text)
    }

    private fun notifyUser(text: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Auto-translate", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Results of the automatic translation of new chapters"
                },
            )
        }
        val open = PendingIntent.getActivity(
            applicationContext,
            1,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = text.lineSequence().first().substringBefore(" — ").substringBefore(":")
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    companion object {
        private const val TAG = "HonyukaAuto"
        private const val CHANNEL_ID = "honyuka_auto"
        private const val NOTIFICATION_ID = 43
        private const val TIME_BUDGET_MS = 7 * 60 * 1000L
        private const val FAILURE_RETRY_AFTER_MS = 11 * 60 * 60 * 1000L

        /** True while an automatic run is translating (manual runs wait for it). */
        val running = MutableStateFlow(false)

        /** Latest summary, so an open Settings screen refreshes by itself. */
        val lastRun = MutableStateFlow<String?>(null)

        /** "Kimetsu_Ch. 172" → "Ch. 172"-ish short label for notifications. */
        fun shortChapterName(name: String): String {
            val match = Regex("""(?i)(ch(apter|\.)?|cap(í|i)tulo|ep(isode)?\.?)\s*([\d.]+)""").find(name)
            return match?.groupValues?.lastOrNull()?.takeIf { it.isNotBlank() } ?: name.substringAfterLast('_')
        }
    }
}
