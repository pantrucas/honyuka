package app.honyuka.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
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
 * caller can save it. The chosen model is never swapped for a weaker one: when Google is
 * busy the run waits and retries, and if it still can't continue it stops with progress saved.
 * [review] then looks at the rendered pages to fix what the first pass got wrong.
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
    /** The manga's context sheet (characters, gender, speech, terms); see MangaContext. */
    private val mangaContext: String = "",
) {
    /** Characters the model described on these pages ("NAME — notes"), to add to the sheet. */
    val learnedCharacters = LinkedHashSet<String>()

    private val modelName = modelName.trim()
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
        var attempt = 0
        while (attempt < 3) {
            val withImages = attempt < 2
            val raw = generateWaitingIfBusy { thinking -> buildPayload(batch, pages, withImages, thinking) }
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
            learnedCharacters += parsed.characters
            parsed.names.forEach { (source, target) ->
                if (glossary.size < MAX_GLOSSARY || source in glossary) glossary[source] = target
            }
            batch.forEach { (name, _) -> pages[name]?.let { absorbErasedPieces(it) } }
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
        if (mangaContext.isNotBlank()) {
            parts.put(textPart("MANGA CONTEXT (from the reader — always follow it):\n${mangaContext.take(MAX_CONTEXT_CHARS)}"))
        }
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
                    .put("names", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")))
                    .put("characters", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))),
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

    private fun pageJpeg(pageName: String): String? =
        pageImage(pageName)?.let { jpegBase64(it, pageName) }

    /** Downscaled base64 JPEG of any page image, small enough to send to Gemini. */
    private fun jpegBase64(bytes: ByteArray, pageName: String): String? = runCatching {
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
- MANGA CONTEXT (when given) is written by the reader and always wins: follow its genders, nicknames, terms and speech styles. Get each speaker's gender right in every gendered word (LOCO/LOCA, MISMO/MISMA, LISTO/LISTA); if the context doesn't say, look at who is speaking in the image. Keep tú/usted consistent between the same two characters.
- Keep names, nicknames (e.g. LIGHTNING) and invented terms as in the context and glossary; translate titles and ranks (e.g. PALADIN → PALADÍN) consistently. Never add accents to foreign names (ARTHUR, not ÁRTHUR).
- Laughs and short interjections inside speech bubbles are dialogue: use the natural equivalent (HA HA → JA JA, HUH? → ¿EH?, UM → EMM, OOPS → UPS). Breathing, crying and effort noises (HUFF, HFF, PANT, GASP, SIGH, SOB, NGH, GRR) are "keep".
- Spells, chants and words in an invented or third language (e.g. German) are "keep", or copied syllable by syllable when the sentence around them is translated. Quotes are straight double quotes "…", never « ».
- If two ids are pieces of the same bubble, put the whole text in the first id and mark the others "erase". Never repeat the same words in two ids.
- Keep each translation about as long as the original: bubbles are small.
- Write everything in UPPERCASE like manga lettering, with fully correct spelling and accents. Every ? and ! needs its opening ¿ or ¡ (¡¿…?! when both).
- Return every id exactly once.

Also return:
- "names": proper names, nicknames and recurring terms seen on these pages as "SOURCE = TARGET" (e.g. "LIGHTNING = LIGHTNING", "ARCH KNIGHT = ARCH KNIGHT", "PALADIN = PALADÍN").
- "characters": named characters who speak on these pages and are NOT yet described in MANGA CONTEXT, as "NAME — man/woman/unknown; one short note on how they talk and whom they address formally". Empty list if none.
""".trim()
    }

    private data class Parsed(
        val lines: Map<String, Pair<String, String>>,
        val names: Map<String, String>,
        val characters: List<String>,
    )

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
        val characters = mutableListOf<String>()
        val characterArray = root.optJSONArray("characters") ?: JSONArray()
        for (i in 0 until characterArray.length()) {
            characterArray.optString(i).trim().takeIf { it.length in 3..200 }?.let { characters += it }
        }
        return Parsed(lines, names, characters)
    }

    private fun textPart(text: String) = JSONObject().put("text", text)

    private fun contextNote(): String =
        if (mangaContext.isBlank()) "" else "\n\nMANGA CONTEXT (from the reader — always follow it):\n${mangaContext.take(MAX_CONTEXT_CHARS)}"

    /**
     * A box marked "erase" because its words were moved into a neighbour leaves that
     * neighbour too small for the whole sentence: grow the neighbour over both boxes.
     */
    private fun absorbErasedPieces(page: PageTranslation) {
        val erased = page.blocks.filter { it.mode == TranslationBlock.MODE_ERASE && it.text.isNotBlank() }
        for (piece in erased) {
            val gap = max(piece.symHeight, 8f) * 2.5f
            val host = page.blocks
                .filter { it.mode == "" && it.translation.isNotBlank() }
                .filter {
                    it.x - gap <= piece.x + piece.width && piece.x - gap <= it.x + it.width &&
                        it.y - gap <= piece.y + piece.height && piece.y - gap <= it.y + it.height
                }
                .minByOrNull { kotlin.math.abs((it.y + it.height / 2) - (piece.y + piece.height / 2)) }
                ?: continue
            val left = minOf(host.x, piece.x)
            val top = minOf(host.y, piece.y)
            host.width = max(host.x + host.width, piece.x + piece.width) - left
            host.height = max(host.y + host.height, piece.y + piece.height) - top
            host.x = left
            host.y = top
        }
    }

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
Return ONLY lines that have a real error: a misspelled or non-existent word (e.g. MENIDO instead of TENIDO, ÉRANOS), a missing accent or ¿ ¡, a wrong meaning, an invented saying, a Spain-only or country-specific word, a wrong gender for the speaker, or words left in the original language. Keep the same length and style, in UPPERCASE. Names, spells and sound effects stay as they are. If nothing is wrong return an empty list.
""".trim() + contextNote()
            val raw = runCatching {
                generateWaitingIfBusy { thinking ->
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

    /**
     * Looks at each finished page (original next to the translated render) and fixes what
     * the first pass got wrong: English left over, lines in the wrong bubble, repeated
     * text, wrong gender, typos, and English text the OCR never found (added as new boxes).
     * Never fails the run: if Gemini can't answer or Stop is pressed, it simply ends.
     * Returns the number of changes made.
     */
    fun review(
        pages: MutableMap<String, PageTranslation>,
        rendered: (pageName: String, page: PageTranslation) -> ByteArray?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        if (apiKey.isBlank()) return 0
        val names = pages.keys.sortedWith { a, b -> naturalPageCompare(a, b) }
        val batches = names.chunked(REVIEW_PAGES_PER_REQUEST)
        var changes = 0
        onProgress(0, batches.size)
        for ((index, batch) in batches.withIndex()) {
            if (TranslationStop.requested) break
            try {
                if (index > 0) waitUnlessStopped(INTER_REQUEST_DELAY_MS)
                changes += reviewBatch(batch, pages, rendered)
            } catch (e: TranslationStoppedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Review skipped for ${batch.joinToString()}", e)
                if (isGeminiDailyQuotaUsedUp(e)) break
            }
            onProgress(index + 1, batches.size)
        }
        Log.i(TAG, "Review made $changes changes")
        return changes
    }

    private fun reviewBatch(
        batch: List<String>,
        pages: MutableMap<String, PageTranslation>,
        rendered: (pageName: String, page: PageTranslation) -> ByteArray?,
    ): Int {
        val parts = JSONArray()
        val ids = LinkedHashMap<String, TranslationBlock>()
        val sizes = HashMap<String, Pair<Float, Float>>()
        batch.forEachIndexed { pageIndex, pageName ->
            val page = pages.getOrPut(pageName) { PageTranslation() }
            val original = pageImage(pageName) ?: return@forEachIndexed
            val size = page.imgWidth.takeIf { it > 0f }?.let { it to page.imgHeight }
                ?: imageSize(original) ?: return@forEachIndexed
            sizes[pageName] = size
            val (width, height) = size
            val boxes = JSONArray()
            page.blocks.forEachIndexed { blockIndex, block ->
                if (block.text.isBlank()) return@forEachIndexed
                val id = "${pageIndex + 1}.${blockIndex + 1}"
                ids[id] = block
                val shown = when {
                    block.mode == TranslationBlock.MODE_KEEP -> "(kept as drawn)"
                    block.mode == TranslationBlock.MODE_ERASE -> "(erased)"
                    block.needsTranslation() -> "(NOT TRANSLATED)"
                    else -> block.translation.replace('\n', ' ').trim()
                }
                boxes.put(
                    JSONArray().put(id)
                        .put(block.text.replace('\n', ' ').trim())
                        .put(shown)
                        .put(((block.x + block.width / 2f) / width * 100f).roundToInt())
                        .put(((block.y + block.height / 2f) / height * 100f).roundToInt()),
                )
            }
            parts.put(textPart("PAGE ${pageIndex + 1} boxes [id, ocr ${sourceLanguage.label} text, current translation, x%, y%]:\n$boxes"))
            parts.put(textPart("PAGE ${pageIndex + 1} — ORIGINAL:"))
            jpegBase64(original, pageName)?.let { parts.put(imagePart(it)) }
            val after = runCatching { rendered(pageName, page) }.getOrNull()
            parts.put(textPart("PAGE ${pageIndex + 1} — TRANSLATED (as the reader will see it):"))
            after?.let { jpegBase64(it, pageName) }?.let { parts.put(imagePart(it)) }
        }
        if (sizes.isEmpty()) return 0

        val fixSchema = JSONObject().put("type", "OBJECT").put(
            "properties",
            JSONObject()
                .put("id", JSONObject().put("type", "STRING"))
                .put("text", JSONObject().put("type", "STRING"))
                .put("mode", JSONObject().put("type", "STRING").put("enum", JSONArray(listOf("tr", "keep", "erase")))),
        ).put("required", JSONArray(listOf("id", "text", "mode")))
        val missingSchema = JSONObject().put("type", "OBJECT").put(
            "properties",
            JSONObject()
                .put("page", JSONObject().put("type", "INTEGER"))
                .put("source", JSONObject().put("type", "STRING"))
                .put("text", JSONObject().put("type", "STRING"))
                .put("x", JSONObject().put("type", "NUMBER"))
                .put("y", JSONObject().put("type", "NUMBER"))
                .put("w", JSONObject().put("type", "NUMBER"))
                .put("h", JSONObject().put("type", "NUMBER")),
        ).put("required", JSONArray(listOf("page", "source", "text", "x", "y", "w", "h")))
        val schema = JSONObject().put("type", "OBJECT").put(
            "properties",
            JSONObject()
                .put("fixes", JSONObject().put("type", "ARRAY").put("items", fixSchema))
                .put("missing", JSONObject().put("type", "ARRAY").put("items", missingSchema)),
        ).put("required", JSONArray(listOf("fixes", "missing")))

        val raw = generateWaitingIfBusy { thinking ->
            val config = JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema)
            thinking?.let { config.put("thinkingConfig", it) }
            JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(textPart(reviewPrompt()))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", config)
                .put("safetySettings", geminiSafetySettings())
        }
        val body = raw.trim().let { it.substring(it.indexOf('{'), it.lastIndexOf('}') + 1) }
        val root = JSONObject(body)
        var changes = 0

        val fixes = root.optJSONArray("fixes") ?: JSONArray()
        for (i in 0 until fixes.length()) {
            val fix = fixes.optJSONObject(i) ?: continue
            val block = ids[fix.optString("id").trim()] ?: continue
            val text = fix.optString("text").trim()
            when (fix.optString("mode").trim().lowercase(Locale.ROOT)) {
                TranslationBlock.MODE_KEEP -> {
                    if (block.mode == TranslationBlock.MODE_KEEP) continue
                    block.mode = TranslationBlock.MODE_KEEP
                    block.translation = block.text
                }
                TranslationBlock.MODE_ERASE -> {
                    if (block.mode == TranslationBlock.MODE_ERASE) continue
                    block.mode = TranslationBlock.MODE_ERASE
                    block.translation = ""
                }
                else -> {
                    if (text.isEmpty() || (block.mode == "" && text == block.translation.trim())) continue
                    if (text.equals(block.text.trim(), ignoreCase = true)) {
                        block.mode = TranslationBlock.MODE_KEEP
                        block.translation = block.text
                    } else {
                        block.mode = ""
                        block.translation = text
                    }
                }
            }
            Log.i(TAG, "Review fix: ${block.text.replace('\n', ' ')} → ${block.mode.ifEmpty { block.translation }}")
            changes++
        }

        val added = HashMap<String, Int>()
        val missing = root.optJSONArray("missing") ?: JSONArray()
        for (i in 0 until missing.length()) {
            val item = missing.optJSONObject(i) ?: continue
            val pageName = batch.getOrNull(item.optInt("page", 0) - 1) ?: continue
            val (width, height) = sizes[pageName] ?: continue
            val page = pages[pageName] ?: continue
            val text = item.optString("text").trim()
            val source = item.optString("source").trim()
            if (text.isEmpty() || source.isEmpty() || text.equals(source, ignoreCase = true)) continue
            if ((added[pageName] ?: 0) >= MAX_MISSING_PER_PAGE) continue
            val w = item.optDouble("w", 0.0).toFloat()
            val h = item.optDouble("h", 0.0).toFloat()
            val x = item.optDouble("x", -1.0).toFloat()
            val y = item.optDouble("y", -1.0).toFloat()
            if (w !in 1f..60f || h !in 1f..60f || x < 0f || y < 0f || x + w > 101f || y + h > 101f) continue
            val box = TranslationBlock(
                text = source,
                translation = text,
                width = w / 100f * width,
                height = h / 100f * height,
                x = x / 100f * width,
                y = y / 100f * height,
                symHeight = typicalLetterHeight(page, h / 100f * height),
                symWidth = 0f,
                angle = 0f,
            ).also { it.symWidth = it.symHeight * 0.6f }
            // A box over an existing one means the model just re-read that bubble.
            if (page.blocks.any { overlapShare(box, it) > 0.25f }) continue
            if (page.imgWidth <= 0f) {
                page.imgWidth = width
                page.imgHeight = height
            }
            page.blocks += box
            added[pageName] = (added[pageName] ?: 0) + 1
            Log.i(TAG, "Review added on $pageName: $source → $text")
            changes++
        }

        if (changes > 0) batch.forEach { name -> pages[name]?.let { absorbErasedPieces(it) } }
        return changes
    }

    private fun reviewPrompt(): String = """
You check a finished manga translation from ${sourceLanguage.label} into ${targetDescription(targetLanguage)}, like a careful editor before publishing.
For each page you get: the list of text boxes [id, ocr ${sourceLanguage.label} text, current translation, x%, y%], the ORIGINAL page and the TRANSLATED page as the reader will see it.
Compare both images and return ONLY real problems:
- "fixes": boxes whose translation is wrong. {"id", "text": the corrected full text, "mode"}.
  - Mode "tr" with the new text for: ${sourceLanguage.label} words still visible in the translated page, "(NOT TRANSLATED)" boxes, a line placed in the wrong bubble or wrong order, the same words repeated in two boxes, wrong gender for the speaker, misspelled or non-existent words, missing accents or opening ¿ ¡, lowercase text, Spain-only words, wrong meaning.
  - Mode "erase" for a box whose words already appear in another box of the same bubble.
  - Mode "keep" for breathing/crying/effort noises (HUFF, HFF, PANT, GASP, SIGH, SOB, NGH, GRR), drawn sound effects, spells and names on their own that were wrongly translated.
- "missing": ${sourceLanguage.label} dialogue or narration that is still visible in the TRANSLATED page and has NO box in the list (the OCR missed it). {"page": 1 or 2, "source": the original words, "text": the translation, "x", "y": top-left corner, "w", "h": size — all as % of the page width/height, tightly around those words}.
Ignore: sound effects drawn as art, Japanese/Korean/Chinese characters, page numbers, scanlation credits and watermarks, and anything that is already fine. Do not rephrase lines that are correct; do not change style for taste.
All text in UPPERCASE with correct spelling; every ? and ! needs its opening ¿ ¡; quotes are straight "…". If nothing is wrong, return empty lists.
""".trim() + contextNote()

    private fun imagePart(jpeg: String) =
        JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", jpeg))

    private fun imageSize(bytes: ByteArray): Pair<Float, Float>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth.toFloat() to bounds.outHeight.toFloat() else null
    }

    /** Letter height for a new box: the page's usual size, or a guess from the box. */
    private fun typicalLetterHeight(page: PageTranslation, boxHeight: Float): Float {
        val heights = page.blocks.map { it.symHeight }.filter { it > 0f }.sorted()
        return heights.getOrNull(heights.size / 2) ?: (boxHeight / 3f).coerceAtLeast(10f)
    }

    /** Share of [a]'s area covered by [b]. */
    private fun overlapShare(a: TranslationBlock, b: TranslationBlock): Float {
        val w = minOf(a.x + a.width, b.x + b.width) - max(a.x, b.x)
        val h = minOf(a.y + a.height, b.y + b.height) - max(a.y, b.y)
        if (w <= 0f || h <= 0f) return 0f
        return (w * h) / (a.width * a.height).coerceAtLeast(1f)
    }

    /**
     * One request with the chosen model. When Google is overloaded (503) or rate-limits per
     * minute (429), waits and tries the same model again — never a weaker one, so the
     * quality stays the same. A used-up daily quota or a bad key fails at once.
     */
    private fun generateWaitingIfBusy(payloadFor: (thinking: JSONObject?) -> JSONObject): String {
        var busyWaits = 0
        while (true) {
            try {
                return geminiGenerate(apiKey, modelName, payloadFor)
            } catch (e: Exception) {
                if (TranslationStop.requested) throw TranslationStoppedException()
                if (!isGeminiBusy(e) || busyWaits >= BUSY_WAITS) throw e
                busyWaits++
                Log.w(TAG, "Gemini busy; waiting before retry $busyWaits/$BUSY_WAITS")
                waitUnlessStopped((BUSY_WAIT_MS * busyWaits).coerceAtMost(120_000L))
            }
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
        private const val MAX_CONTEXT_CHARS = 6_000
        private const val INTER_REQUEST_DELAY_MS = 4_000L
        private const val PROOFREAD_LINES_PER_REQUEST = 200
        private const val BUSY_WAITS = 5
        private const val BUSY_WAIT_MS = 30_000L
        private const val REVIEW_PAGES_PER_REQUEST = 2
        private const val MAX_MISSING_PER_PAGE = 6

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
