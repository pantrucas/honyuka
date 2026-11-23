package app.honyuka.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import app.honyuka.model.GeminiModelPreset
import app.honyuka.model.PageTranslation
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationBlock
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini translator that sends the page images together with the OCR boxes, so the
 * model can see which bubble is which, fix OCR mistakes, tell sound effects from
 * dialogue, and translate sentences that continue across several bubbles as one.
 *
 * Each bubble comes back with a mode:
 * - "tr": erase the original and draw the translation;
 * - "keep": leave the original art (drawn sound effects, names on their own, spell words);
 * - "erase": erase only (its words were moved into a neighbouring bubble).
 *
 * Pages are sent in small batches; progress is reported after every batch so the
 * caller can save it. When the model's daily quota runs out it switches once to
 * [GeminiModelPreset.QUOTA_FALLBACK].
 */
internal class GeminiVisionEngine(
    private val sourceLanguage: SourceLanguage,
    private val targetLanguage: TargetLanguage,
    private val apiKey: String,
    modelName: String,
    private val pageImage: (pageName: String) -> ByteArray?,
    /** Names and terms (source → target) shared across chapters of the same manga; updated in place. */
    private val glossary: MutableMap<String, String>,
    private val onBatchDone: (done: Int, total: Int) -> Unit,
) {
    private val originalModel = modelName.trim()
    private var modelName = originalModel
    private val recentLines = ArrayDeque<Pair<String, String>>()
    private val jpegCache = HashMap<String, String?>()
    /** Bubbles given a new translation in this run; checked once more by [proofread]. */
    private val translatedThisRun = mutableListOf<TranslationBlock>()

    /** Number of requests a run will make (without retries). */
    fun plan(pages: Map<String, PageTranslation>): Pair<Int, Int> {
        val batches = batches(pages)
        return batches.size to batches.sumOf { batch -> batch.sumOf { it.second.size } }
    }

    fun translate(pages: MutableMap<String, PageTranslation>) {
        require(apiKey.isNotBlank()) { "Gemini API key is required for the Gemini translator." }
        val batches = batches(pages)
        if (batches.isEmpty()) return
        onBatchDone(0, batches.size)

        // Already-translated lines give the model the story so far.
        pages.values.flatMap { it.blocks }
            .filter { !it.needsTranslation() && it.mode != TranslationBlock.MODE_ERASE }
            .takeLast(MAX_CONTEXT_LINES)
            .forEach { remember(it.text, it.translation) }

        var translated = 0
        var failure: Exception? = null
        for ((index, batch) in batches.withIndex()) {
            if (TranslationStop.requested) {
                failure = TranslationStoppedException()
                break
            }
            if (index > 0) Thread.sleep(INTER_REQUEST_DELAY_MS)
            try {
                translated += translateBatch(batch, pages)
                // A second pass for boxes the model skipped, so no original text is left visible.
                val missing = batch.mapNotNull { (name, blocks) ->
                    blocks.filter { it.needsTranslation() }.takeIf { it.isNotEmpty() }?.let { name to it }
                }
                if (missing.isNotEmpty()) {
                    Thread.sleep(INTER_REQUEST_DELAY_MS)
                    translated += translateBatch(missing, pages)
                }
            } catch (e: TranslationStoppedException) {
                failure = e
                break
            } catch (e: Exception) {
                failure = e
                break
            }
            onBatchDone(index + 1, batches.size)
        }

        if (failure == null && translatedThisRun.isNotEmpty()) proofread()

        failure?.let { error ->
            if (error is TranslationStoppedException || translated == 0) throw error
            throw IllegalStateException(
                "Gemini stopped after $translated bubbles: ${error.message} " +
                    "Progress was saved — tap Retry later and only the remaining bubbles will be sent.",
                error,
            )
        }
    }

    /** Pages that still have work, grouped into request-sized batches. */
    private fun batches(pages: Map<String, PageTranslation>): List<List<Pair<String, List<TranslationBlock>>>> {
        val pending = pages.entries
            .sortedWith { a, b -> naturalPageCompare(a.key, b.key) }
            .mapNotNull { (name, page) ->
                page.blocks.filter { it.needsTranslation() && it.text.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { name to it }
            }
        val result = mutableListOf<List<Pair<String, List<TranslationBlock>>>>()
        var current = mutableListOf<Pair<String, List<TranslationBlock>>>()
        var blocks = 0
        for (entry in pending) {
            if (current.isNotEmpty() && (current.size >= MAX_PAGES_PER_REQUEST || blocks + entry.second.size > MAX_BLOCKS_PER_REQUEST)) {
                result += current
                current = mutableListOf()
                blocks = 0
            }
            current += entry
            blocks += entry.second.size
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    /** Sends one batch (with images; text-only if that fails twice). Returns bubbles resolved. */
    private fun translateBatch(
        batch: List<Pair<String, List<TranslationBlock>>>,
        pages: Map<String, PageTranslation>,
    ): Int {
        val ids = LinkedHashMap<String, TranslationBlock>()
        batch.forEachIndexed { pageIndex, (_, blocks) ->
            blocks.forEachIndexed { blockIndex, block -> ids["${pageIndex + 1}.${blockIndex + 1}"] = block }
        }

        jpegCache.keys.retainAll(batch.map { it.first }.toSet())
        var lastError: Exception? = null
        var busyWaits = 0
        var attempt = 0
        while (attempt < 3) {
            val withImages = attempt < 2
            val model = modelName
            val raw = try {
                geminiGenerate(apiKey, model) { thinking -> buildPayload(batch, pages, withImages, thinking) }
            } catch (e: Exception) {
                if (TranslationStop.requested) throw TranslationStoppedException()
                if (isGeminiQuotaExhausted(e) && modelName != GeminiModelPreset.QUOTA_FALLBACK.modelName) {
                    Log.w(TAG, "$modelName busy or out of quota; switching to ${GeminiModelPreset.QUOTA_FALLBACK.modelName}")
                    modelName = GeminiModelPreset.QUOTA_FALLBACK.modelName
                    continue
                }
                if (isGeminiQuotaExhausted(e) && busyWaits < BUSY_WAITS) {
                    // Both models saturated: high demand is usually over within a minute or two.
                    busyWaits++
                    Log.w(TAG, "Gemini busy; waiting before retry $busyWaits/$BUSY_WAITS")
                    waitUnlessStopped((BUSY_WAIT_MS * busyWaits).coerceAtMost(120_000L))
                    modelName = originalModel
                    continue
                }
                throw e
            }
            attempt++
            val parsed = runCatching { parseResponse(raw) }.getOrElse {
                Log.w(TAG, "Unreadable Gemini response (attempt $attempt)", it)
                lastError = it as? Exception ?: IllegalStateException(it)
                null
            } ?: continue

            var resolved = 0
            for ((id, result) in parsed.lines) {
                val block = ids[id] ?: continue
                val (text, mode) = result
                // A one-word sound with no vowels (TMP, RMM, KSHK, SHH, HM?) reads the same in any
                // language and is usually drawn art: never repaint it, whatever the model says.
                val effectiveMode = if (mode != TranslationBlock.MODE_ERASE && isVowellessSound(block.text)) {
                    TranslationBlock.MODE_KEEP
                } else {
                    mode
                }
                when (effectiveMode) {
                    TranslationBlock.MODE_KEEP -> {
                        block.mode = TranslationBlock.MODE_KEEP
                        block.translation = block.text
                    }
                    TranslationBlock.MODE_ERASE -> {
                        block.mode = TranslationBlock.MODE_ERASE
                        block.translation = ""
                    }
                    else -> {
                        if (text.isBlank()) continue
                        // Same words in both languages (NO..., names like HOMARE...): leave the
                        // original lettering instead of flagging it as "still untranslated".
                        if (text.trim().equals(block.text.trim(), ignoreCase = true)) {
                            block.mode = TranslationBlock.MODE_KEEP
                            block.translation = block.text
                            resolved++
                            continue
                        }
                        block.mode = ""
                        block.translation = text.trim()
                        remember(block.text, block.translation)
                        translatedThisRun += block
                    }
                }
                resolved++
            }
            parsed.names.forEach { (source, target) ->
                if (glossary.size < MAX_GLOSSARY || source in glossary) glossary[source] = target
            }
            return resolved
        }
        lastError?.let { Log.w(TAG, "Batch skipped after retries", it) }
        return 0
    }

    private fun remember(source: String, target: String) {
        recentLines.addLast(source.replace('\n', ' ') to target.replace('\n', ' '))
        while (recentLines.size > MAX_CONTEXT_LINES) recentLines.removeFirst()
    }

    private fun buildPayload(
        batch: List<Pair<String, List<TranslationBlock>>>,
        pages: Map<String, PageTranslation>,
        withImages: Boolean,
        thinking: JSONObject?,
    ): JSONObject {
        val parts = JSONArray()
        if (glossary.isNotEmpty()) {
            parts.put(textPart("GLOSSARY (always use these renderings):\n" +
                glossary.entries.joinToString("\n") { "${it.key} = ${it.value}" }))
        }
        if (recentLines.isNotEmpty()) {
            parts.put(textPart("STORY SO FAR (previous bubbles, for context only):\n" +
                recentLines.joinToString("\n") { "${it.first} → ${it.second}" }))
        }
        var total = 0
        batch.forEachIndexed { pageIndex, (pageName, blocks) ->
            val page = pages[pageName]
            val width = page?.imgWidth?.takeIf { it > 0f } ?: blocks.maxOf { it.x + it.width }
            val height = page?.imgHeight?.takeIf { it > 0f } ?: blocks.maxOf { it.y + it.height }
            val boxes = JSONArray()
            blocks.forEachIndexed { blockIndex, block ->
                boxes.put(
                    JSONArray()
                        .put("${pageIndex + 1}.${blockIndex + 1}")
                        .put(block.text.replace('\n', ' ').trim())
                        .put(((block.x + block.width / 2f) / width * 100f).roundToInt())
                        .put(((block.y + block.height / 2f) / height * 100f).roundToInt()),
                )
            }
            total += blocks.size
            parts.put(textPart("PAGE ${pageIndex + 1} boxes [id, ocr text, x%, y%]:\n$boxes"))
            if (withImages) {
                jpegCache.getOrPut(pageName) { pageJpeg(pageName) }?.let { jpeg ->
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", "image/jpeg").put("data", jpeg),
                        ),
                    )
                }
            }
        }
        parts.put(textPart("Return all $total ids."))

        val lineSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("id", JSONObject().put("type", "STRING"))
                    .put("text", JSONObject().put("type", "STRING"))
                    .put("mode", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("tr", "keep", "erase")))),
            )
            .put("required", JSONArray(listOf("id", "text", "mode")))
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("lines", JSONObject().put("type", "ARRAY").put("items", lineSchema))
                    .put("names", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))),
            )
            .put("required", JSONArray(listOf("lines", "names")))

        val generationConfig = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", schema)
        thinking?.let { generationConfig.put("thinkingConfig", it) }

        return JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(textPart(systemPrompt()))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", generationConfig)
            .put("safetySettings", geminiSafetySettings())
    }

    private fun pageJpeg(pageName: String): String? = runCatching {
        val bytes = pageImage(pageName) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = MAX_IMAGE_SIDE.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "Could not attach page $pageName", it) }.getOrNull()

    private fun systemPrompt(): String {
        val target = targetDescription(targetLanguage)
        return """
You are a professional manga translator and letterer. Translate ${sourceLanguage.label} manga pages into $target.
You get each page image plus the OCR text boxes found on it as [id, ocr text, x%, y%] (x/y = box centre as a percentage of page width/height). OCR is noisy: always read the real text from the image.

For EVERY id return {"id", "text", "mode"}:
- "tr": text = the translation to letter in that box.
- "keep": leave the original art untouched. Use it for sound effects drawn as art outside bubbles (e.g. ZM, KRAK, RMM, TMP), a character name or nickname shown alone, invented spell/language words, page numbers, credits names, and unreadable OCR garbage. text = original.
- "erase": when a box's words were merged into another id's translation, and for scanlation watermarks/URLs; text = "".

Translation rules:
- Read each page in reading order and use the image to know which boxes belong to the same bubble or the same sentence. If a sentence continues across several bubbles, translate it as one sentence and split it across those ids in the same order, so each part reads naturally in its bubble. Never leave part of a sentence untranslated.
- Translate the meaning in context, like an official release. Natural, fluent dialogue that matches each character's tone.
- Never invent words, idioms or sayings. For an idiom use a real, widely known equivalent in the target language, or state the meaning plainly.
- Keep names, nicknames (e.g. LIGHTNING) and invented terms as in the glossary; translate titles and ranks (e.g. PALADIN → PALADÍN) consistently.
- Short sounds inside speech bubbles (sighs, laughs, gasps, "UM", "HUH?") are dialogue: use the natural target-language equivalent (e.g. HA HA → JA JA, SIGH → SUSPIRO, HUH? → ¿EH?, UM → EMM).
- Keep each translation about as long as the original: bubbles are small.
- Write everything in UPPERCASE like manga lettering, with fully correct spelling and accents.
- Return every id exactly once.

Also return "names": proper names, nicknames and recurring terms seen on these pages as "SOURCE = TARGET" (e.g. "LIGHTNING = LIGHTNING", "ARCH KNIGHT = CABALLERO ARCO").
""".trim()
    }

    private data class Parsed(val lines: Map<String, Pair<String, String>>, val names: Map<String, String>)

    private fun parseResponse(raw: String): Parsed {
        var text = raw.trim()
        if (text.startsWith("```")) text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object in Gemini response" }
        val root = JSONObject(text.substring(start, end + 1))
        val lines = LinkedHashMap<String, Pair<String, String>>()
        val array = root.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            lines[id] = item.optString("text", "") to item.optString("mode", "tr").trim().lowercase(Locale.ROOT)
        }
        require(lines.isNotEmpty()) { "Gemini returned no lines" }
        val names = LinkedHashMap<String, String>()
        val nameArray = root.optJSONArray("names") ?: JSONArray()
        for (i in 0 until nameArray.length()) {
            val entry = nameArray.optString(i)
            val parts = entry.split('=', limit = 2).map { it.trim() }
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty() && parts[0].length <= 40) {
                names[parts[0].uppercase(Locale.ROOT)] = parts[1]
            }
        }
        return Parsed(lines, names)
    }

    private fun textPart(text: String) = JSONObject().put("text", text)

    /**
     * Final spelling/sense check on the new lines, text only (cheap): fixes typos and
     * non-existent words the first pass sometimes produces (e.g. "MENIDO" for "TENIDO").
     * Never fails the run — if Gemini can't answer, the first-pass text stays.
     */
    private fun proofread() {
        translatedThisRun.chunked(PROOFREAD_LINES_PER_REQUEST).forEach { chunk ->
            if (TranslationStop.requested) return
            Thread.sleep(INTER_REQUEST_DELAY_MS)
            val lines = JSONArray()
            chunk.forEachIndexed { i, block ->
                lines.put(JSONArray().put("$i").put(block.text.replace('\n', ' ').trim()).put(block.translation))
            }
            val fixSchema = JSONObject().put("type", "OBJECT").put(
                "properties",
                JSONObject().put("id", JSONObject().put("type", "STRING")).put("text", JSONObject().put("type", "STRING")),
            ).put("required", JSONArray(listOf("id", "text")))
            val schema = JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("fixes", JSONObject().put("type", "ARRAY").put("items", fixSchema)))
                .put("required", JSONArray(listOf("fixes")))
            val prompt = """
You proofread a manga translation into ${targetDescription(targetLanguage)}.
Each line is [id, original ${sourceLanguage.label} (OCR, may be noisy), translation]. Consecutive lines are consecutive bubbles.
Return ONLY lines that have a real error: a misspelled or non-existent word (e.g. MENIDO instead of TENIDO), a missing accent or ¿ ¡, a wrong meaning, an invented saying, or words left in the original language. Keep the same length and style, in UPPERCASE. Names and sound effects stay as they are. If nothing is wrong return an empty list.
""".trim()
            val raw = runCatching {
                geminiGenerate(apiKey, modelName) { thinking ->
                    val config = JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema)
                    thinking?.let { config.put("thinkingConfig", it) }
                    JSONObject()
                        .put("system_instruction", JSONObject().put("parts", JSONArray().put(textPart(prompt))))
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(textPart(lines.toString())))))
                        .put("generationConfig", config)
                        .put("safetySettings", geminiSafetySettings())
                }
            }.onFailure { Log.w(TAG, "Proofreading skipped", it) }.getOrNull() ?: return
            runCatching {
                val body = raw.trim().let { it.substring(it.indexOf('{'), it.lastIndexOf('}') + 1) }
                val fixes = JSONObject(body).optJSONArray("fixes") ?: JSONArray()
                for (i in 0 until fixes.length()) {
                    val fix = fixes.optJSONObject(i) ?: continue
                    val block = chunk.getOrNull(fix.optString("id").trim().toIntOrNull() ?: continue) ?: continue
                    val text = fix.optString("text").trim()
                    if (text.isNotEmpty() && text != block.text.trim()) {
                        Log.i(TAG, "Proofread: ${block.translation} → $text")
                        block.translation = text
                    }
                }
            }.onFailure { Log.w(TAG, "Unreadable proofreading answer", it) }
        }
    }

    /** Sleeps in short steps so the Stop button still works during long waits. */
    private fun waitUnlessStopped(ms: Long) {
        var left = ms
        while (left > 0) {
            if (TranslationStop.requested) throw TranslationStoppedException()
            Thread.sleep(minOf(left, 1_000L))
            left -= 1_000L
        }
    }

    companion object {
        private val SOUND_TOKEN = Regex("""^[A-Za-z]{2,10}[-!?.…]*$""")
        private val VOWEL = Regex("[AEIOUYaeiouy]") // Y too: WHY, MY, TRY are words

        /** "TMP", "RMM", "BMP-", "HM?": a single short word without vowels. */
        internal fun isVowellessSound(text: String): Boolean {
            val t = text.trim()
            return SOUND_TOKEN.matches(t) && !VOWEL.containsMatchIn(t)
        }

        private const val TAG = "HonyukaVision"
        private const val MAX_PAGES_PER_REQUEST = 4
        private const val MAX_BLOCKS_PER_REQUEST = 70
        private const val MAX_IMAGE_SIDE = 1280
        private const val MAX_CONTEXT_LINES = 25
        private const val MAX_GLOSSARY = 120
        private const val INTER_REQUEST_DELAY_MS = 4_000L
        private const val PROOFREAD_LINES_PER_REQUEST = 200
        private const val BUSY_WAITS = 5
        private const val BUSY_WAIT_MS = 30_000L

        /** Style guidance per target language; Spanish is neutral Latin American. */
        fun targetDescription(target: TargetLanguage): String = when (target) {
            TargetLanguage.SPANISH ->
                "neutral Latin American Spanish, as in Latin American manga editions: understood in every " +
                    "Latin American country. Use \"ustedes\" (never \"vosotros\"). Avoid Spain-only words and phrasing " +
                    "(vale, tío, molar, largarse, guay, coger, \"se me da bien\") and country-specific slang " +
                    "(ni modo, chido, güey, cachai, boludo, bacán). Correct accents (Á É Í Ó Ú Ñ Ü) and opening ¿ and ¡ marks are mandatory"
            TargetLanguage.PORTUGUESE -> "Brazilian Portuguese"
            else -> target.label
        }

        private fun naturalPageCompare(a: String, b: String): Int {
            val na = Regex("\\d+").find(a)?.value?.toLongOrNull()
            val nb = Regex("\\d+").find(b)?.value?.toLongOrNull()
            return if (na != null && nb != null && na != nb) na.compareTo(nb) else a.compareTo(b)
        }
    }
}
