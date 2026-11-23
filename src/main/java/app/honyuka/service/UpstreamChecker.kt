package app.honyuka.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.honyuka.R
import app.honyuka.data.HonyukaPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Tells the user when the original Honyuka (PrajasW/honyuka) gets changes this fork
 * doesn't have yet. Uses the public GitHub API without a token; any error (offline,
 * rate limit, 404) is ignored silently.
 */
object UpstreamChecker {
    private const val TAG = "HonyukaUpstream"
    private const val COMPARE_URL =
        "https://api.github.com/repos/pantrucas/honyuka/compare/master...PrajasW:honyuka:master"
    private const val RELEASE_URL = "https://api.github.com/repos/PrajasW/honyuka/releases/latest"
    const val COMMITS_PAGE = "https://github.com/PrajasW/honyuka/commits/master"
    private const val CHANNEL_ID = "honyuka_updates"
    private const val NOTIFICATION_ID = 44
    private const val PERIODIC = "upstream-check"
    private const val APP_START_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    data class Status(val aheadBy: Int, val latestMessage: String?, val latestTag: String?)

    /** Daily background check, always on (independent of auto-translate). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpstreamCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Whether the app-start check should run (at most once every 6 hours). */
    fun isDue(preferences: HonyukaPreferences): Boolean =
        System.currentTimeMillis() - preferences.upstreamLastCheckMs() > APP_START_INTERVAL_MS

    /** Checks GitHub, saves the result and notifies once per new upstream state. Null on any error. */
    suspend fun check(context: Context): Status? = withContext(Dispatchers.IO) {
        try {
            val preferences = HonyukaPreferences(context)
            val compare = getJson(COMPARE_URL) ?: return@withContext null
            val aheadBy = compare["ahead_by"]?.jsonPrimitive?.intOrNull ?: return@withContext null
            val newest = (compare["commits"] as? JsonArray)?.lastOrNull() as? JsonObject
            val newestSha = newest?.get("sha")?.jsonPrimitive?.contentOrNull
            val newestMessage = (newest?.get("commit") as? JsonObject)
                ?.get("message")?.jsonPrimitive?.contentOrNull
                ?.lineSequence()?.firstOrNull()?.trim()
            val tag = getJson(RELEASE_URL)?.get("tag_name")?.jsonPrimitive?.contentOrNull

            preferences.saveUpstreamStatus(
                checkedAtMs = System.currentTimeMillis(),
                aheadBy = aheadBy,
                latestMessage = if (aheadBy > 0) newestMessage else null,
                latestTag = tag,
            )

            val notifiedSha = preferences.upstreamNotifiedSha()
            // null = never checked before: take the current release as the baseline silently.
            val notifiedTag = preferences.upstreamNotifiedTag()
            val newCommits = aheadBy > 0 && newestSha != null && newestSha != notifiedSha
            val newRelease = tag != null && notifiedTag != null && tag != notifiedTag
            if (newCommits || newRelease) {
                notify(context, aheadBy.takeIf { newCommits } ?: 0, newestMessage, tag?.takeIf { newRelease })
            }
            preferences.saveUpstreamNotified(
                sha = if (aheadBy > 0) newestSha else notifiedSha,
                tag = tag ?: notifiedTag ?: "",
            )
            Status(aheadBy, newestMessage, tag)
        } catch (e: Exception) {
            Log.i(TAG, "Upstream check failed: ${e.message}")
            null
        }
    }

    private fun getJson(url: String): JsonObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "honyuka-turbo")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "GET $url -> HTTP ${connection.responseCode}")
                null
            } else {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                json.parseToJsonElement(body).jsonObject
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun notify(context: Context, newCommits: Int, latestMessage: String?, newTag: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Honyuka updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Changes in the original Honyuka app"
                },
            )
        }
        val text = buildList {
            if (newCommits > 0) {
                add(
                    (if (newCommits == 1) "1 cambio nuevo" else "$newCommits cambios nuevos") +
                        (latestMessage?.let { ": $it" } ?: ""),
                )
            }
            if (newTag != null) add("Nueva versión $newTag")
        }.joinToString("\n")
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(Intent.ACTION_VIEW, Uri.parse(COMMITS_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Honyuka original tiene novedades")
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
}

class UpstreamCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UpstreamChecker.check(applicationContext)
        return Result.success()
    }
}
