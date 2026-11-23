package app.honyuka.data

import android.content.Context
import android.net.Uri
import app.honyuka.model.GeminiModelPreset
import app.honyuka.model.LocalBackend
import app.honyuka.model.LocalModel
import app.honyuka.model.ThemeMode
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationEngine

class HonyukaPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("honyuka_prefs", Context.MODE_PRIVATE)

    fun rootUri(): Uri? = prefs.getString(KEY_ROOT_URI, null)?.let(Uri::parse)

    fun saveRootUri(uri: Uri) {
        prefs.edit().putString(KEY_ROOT_URI, uri.toString()).apply()
    }

    fun sourceLanguage(): SourceLanguage {
        val saved = prefs.getString(KEY_SOURCE_LANGUAGE, SourceLanguage.JAPANESE.name)
        return SourceLanguage.entries.firstOrNull { it.name == saved } ?: SourceLanguage.JAPANESE
    }

    fun saveSourceLanguage(language: SourceLanguage) {
        prefs.edit().putString(KEY_SOURCE_LANGUAGE, language.name).apply()
    }

    /** All selected "From" languages (at least one). Falls back to the old single choice. */
    fun sourceLanguages(): Set<SourceLanguage> {
        val saved = prefs.getString(KEY_SOURCE_LANGUAGES, null)
            ?.split(',')
            ?.mapNotNull { name -> SourceLanguage.entries.firstOrNull { it.name == name.trim() } }
            ?.toSet()
            .orEmpty()
        return saved.ifEmpty { setOf(sourceLanguage()) }
    }

    /** Saves the set; the primary (used for untagged sources) is the first one in list order. */
    fun saveSourceLanguages(languages: Set<SourceLanguage>) {
        if (languages.isEmpty()) return
        val ordered = SourceLanguage.entries.filter { it in languages }
        prefs.edit()
            .putString(KEY_SOURCE_LANGUAGES, ordered.joinToString(",") { it.name })
            .putString(KEY_SOURCE_LANGUAGE, ordered.first().name)
            .apply()
    }

    fun targetLanguage(): TargetLanguage {
        val saved = prefs.getString(KEY_TARGET_LANGUAGE, TargetLanguage.ENGLISH.name)
        return TargetLanguage.entries.firstOrNull { it.name == saved } ?: TargetLanguage.ENGLISH
    }

    fun saveTargetLanguage(language: TargetLanguage) {
        prefs.edit().putString(KEY_TARGET_LANGUAGE, language.name).apply()
    }

    fun translationEngine(): TranslationEngine {
        val saved = prefs.getString(KEY_TRANSLATION_ENGINE, TranslationEngine.GEMINI.name)
        return TranslationEngine.entries.firstOrNull { it.name == saved } ?: TranslationEngine.GEMINI
    }

    fun saveTranslationEngine(engine: TranslationEngine) {
        prefs.edit().putString(KEY_TRANSLATION_ENGINE, engine.name).apply()
    }

    fun geminiModelName(): String {
        val saved = prefs.getString(KEY_GEMINI_MODEL_NAME, null)?.trim()
        // One-time migration: move users off presets from older builds. Done once so a
        // model the user later picks from the fetched list is never overridden.
        if (!prefs.getBoolean(KEY_MODEL_PRESETS_MIGRATED, false)) {
            prefs.edit().putBoolean(KEY_MODEL_PRESETS_MIGRATED, true).apply()
            if (saved != null && saved in GeminiModelPreset.RETIRED_MODEL_NAMES) {
                saveGeminiModelName(GeminiModelPreset.DEFAULT.modelName)
                return GeminiModelPreset.DEFAULT.modelName
            }
        }
        // v0.4: Flash reads the page images far better than Flash-Lite. Move users who were
        // on the old Flash-Lite default once; Flash-Lite is still used when Flash's quota runs out.
        if (!prefs.getBoolean(KEY_MODEL_FLASH_MIGRATED, false)) {
            prefs.edit().putBoolean(KEY_MODEL_FLASH_MIGRATED, true).apply()
            if (saved == null || saved == GeminiModelPreset.FLASH_LITE_LATEST.modelName) {
                saveGeminiModelName(GeminiModelPreset.DEFAULT.modelName)
                return GeminiModelPreset.DEFAULT.modelName
            }
        }
        return saved?.takeIf { it.isNotBlank() } ?: GeminiModelPreset.DEFAULT.modelName
    }

    fun availableGeminiModels(): List<String> {
        return prefs.getString(KEY_GEMINI_MODEL_LIST, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    fun saveAvailableGeminiModels(models: List<String>) {
        prefs.edit().putString(KEY_GEMINI_MODEL_LIST, models.joinToString("\n")).apply()
    }

    fun saveGeminiModelName(modelName: String) {
        prefs.edit().putString(KEY_GEMINI_MODEL_NAME, modelName.trim()).apply()
    }

    fun geminiApiKey(): String = prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty()

    fun saveGeminiApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    fun deepLApiKey(): String = prefs.getString(KEY_DEEPL_API_KEY, "").orEmpty()

    fun saveDeepLApiKey(apiKey: String) {
        prefs.edit().putString(KEY_DEEPL_API_KEY, apiKey.trim()).apply()
    }

    /** Hide source folders tagged with another language, e.g. `(ES)` when translating from English. */
    fun filterSourcesByLanguage(): Boolean = prefs.getBoolean(KEY_FILTER_SOURCES_BY_LANGUAGE, true)

    fun saveFilterSourcesByLanguage(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FILTER_SOURCES_BY_LANGUAGE, enabled).apply()
    }

    /** When Gemini fails (quota, key…), finish the remaining lines with Google Translate. */
    fun googleFallback(): Boolean = prefs.getBoolean(KEY_GOOGLE_FALLBACK, false)

    fun saveGoogleFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GOOGLE_FALLBACK, enabled).apply()
    }

    // ── Auto-translate (fork) ──────────────────────────────────────────────

    /** "sourceName/mangaName" keys of mangas the user opted in to auto-translate. Empty by default. */
    fun autoTranslateMangas(): Set<String> =
        prefs.getStringSet(KEY_AUTO_MANGAS, emptySet()).orEmpty().toSet()

    fun setAutoTranslateManga(mangaKey: String, enabled: Boolean) {
        val updated = autoTranslateMangas().let { if (enabled) it + mangaKey else it - mangaKey }
        prefs.edit().putStringSet(KEY_AUTO_MANGAS, HashSet(updated)).apply()
    }

    fun autoTranslateEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_ENABLED, false)

    fun saveAutoTranslateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
    }

    /** Human-readable summary of the last automatic run, e.g. "23 Nov 05:10 — Translated 2 chapters". */
    fun lastAutoRun(): String? = prefs.getString(KEY_AUTO_LAST_RUN, null)

    fun saveLastAutoRun(summary: String) {
        prefs.edit().putString(KEY_AUTO_LAST_RUN, summary).apply()
    }

    /** Chapter storage keys that failed in auto mode → time of failure (ms). */
    fun autoFailures(): Map<String, Long> =
        prefs.getStringSet(KEY_AUTO_FAILURES, emptySet()).orEmpty().mapNotNull { entry ->
            val time = entry.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            entry.substringAfter('|') to time
        }.toMap()

    fun saveAutoFailures(failures: Map<String, Long>) {
        prefs.edit()
            .putStringSet(KEY_AUTO_FAILURES, HashSet(failures.map { (key, time) -> "$time|$key" }))
            .apply()
    }

    // ── Original (upstream) Honyuka update check (fork) ────────────────────

    fun upstreamLastCheckMs(): Long = prefs.getLong(KEY_UPSTREAM_LAST_CHECK, 0L)

    /** Commits in the original Honyuka that this fork doesn't have yet (-1 = never checked). */
    fun upstreamAheadBy(): Int = prefs.getInt(KEY_UPSTREAM_AHEAD, -1)

    fun upstreamLatestMessage(): String? = prefs.getString(KEY_UPSTREAM_MESSAGE, null)

    fun upstreamLatestTag(): String? = prefs.getString(KEY_UPSTREAM_TAG, null)

    fun saveUpstreamStatus(checkedAtMs: Long, aheadBy: Int, latestMessage: String?, latestTag: String?) {
        prefs.edit()
            .putLong(KEY_UPSTREAM_LAST_CHECK, checkedAtMs)
            .putInt(KEY_UPSTREAM_AHEAD, aheadBy)
            .putString(KEY_UPSTREAM_MESSAGE, latestMessage)
            .putString(KEY_UPSTREAM_TAG, latestTag)
            .apply()
    }

    fun upstreamNotifiedSha(): String? = prefs.getString(KEY_UPSTREAM_NOTIFIED_SHA, null)

    fun upstreamNotifiedTag(): String? = prefs.getString(KEY_UPSTREAM_NOTIFIED_TAG, null)

    fun saveUpstreamNotified(sha: String?, tag: String?) {
        prefs.edit()
            .putString(KEY_UPSTREAM_NOTIFIED_SHA, sha)
            .putString(KEY_UPSTREAM_NOTIFIED_TAG, tag)
            .apply()
    }

    fun localModel(): LocalModel = enumPref(KEY_LOCAL_MODEL, LocalModel.DEFAULT)

    fun saveLocalModel(model: LocalModel) {
        prefs.edit().putString(KEY_LOCAL_MODEL, model.name).apply()
    }

    fun localBackend(): LocalBackend = enumPref(KEY_LOCAL_BACKEND, LocalBackend.AUTO)

    fun saveLocalBackend(backend: LocalBackend) {
        prefs.edit().putString(KEY_LOCAL_BACKEND, backend.name).apply()
    }

    fun themeMode(): ThemeMode = enumPref(KEY_THEME_MODE, ThemeMode.AUTO)

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    /** DownloadManager id of an in-progress model download, or -1. */
    fun localModelDownloadId(model: LocalModel): Long = prefs.getLong(KEY_DOWNLOAD_ID_PREFIX + model.name, -1L)

    fun saveLocalModelDownloadId(model: LocalModel, id: Long) {
        prefs.edit().putLong(KEY_DOWNLOAD_ID_PREFIX + model.name, id).apply()
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T {
        val saved = prefs.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == saved } ?: default
    }

    private companion object {
        const val KEY_ROOT_URI = "root_uri"
        const val KEY_SOURCE_LANGUAGE = "source_language"
        const val KEY_SOURCE_LANGUAGES = "source_languages"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_TRANSLATION_ENGINE = "translation_engine"
        const val KEY_GEMINI_MODEL_NAME = "gemini_model_name"
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_GEMINI_MODEL_LIST = "gemini_model_list"
        const val KEY_MODEL_PRESETS_MIGRATED = "model_presets_migrated_v2"
        const val KEY_MODEL_FLASH_MIGRATED = "model_flash_default_v4"
        const val KEY_LOCAL_MODEL = "local_model"
        const val KEY_LOCAL_BACKEND = "local_backend"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DOWNLOAD_ID_PREFIX = "local_model_download_"
        const val KEY_DEEPL_API_KEY = "deepl_api_key"
        const val KEY_FILTER_SOURCES_BY_LANGUAGE = "filter_sources_by_language"
        const val KEY_GOOGLE_FALLBACK = "google_fallback"
        const val KEY_AUTO_MANGAS = "auto_translate_mangas"
        const val KEY_AUTO_ENABLED = "auto_translate_enabled"
        const val KEY_AUTO_LAST_RUN = "auto_translate_last_run"
        const val KEY_AUTO_FAILURES = "auto_translate_failures"
        const val KEY_UPSTREAM_LAST_CHECK = "upstream_last_check"
        const val KEY_UPSTREAM_AHEAD = "upstream_ahead_by"
        const val KEY_UPSTREAM_MESSAGE = "upstream_latest_message"
        const val KEY_UPSTREAM_TAG = "upstream_latest_tag"
        const val KEY_UPSTREAM_NOTIFIED_SHA = "upstream_notified_sha"
        const val KEY_UPSTREAM_NOTIFIED_TAG = "upstream_notified_tag"
    }
}
