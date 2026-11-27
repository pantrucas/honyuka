package app.honyuka.service

import android.graphics.RectF
import app.honyuka.export.RenderedChapterExporter.DrawnText
import app.honyuka.export.letterHeight
import app.honyuka.model.PageTranslation
import app.honyuka.model.TranslationBlock
import app.honyuka.translation.OcrLine
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Looks at OCR of a finished page for text that is still in the original language.
 *
 * - Next to a translated text drawn outside a bubble (lettering over artwork), it is a
 *   piece of the original the box didn't cover ("TO" above "THOSE PEOPLE."): that box
 *   grows to include it, so the next render wipes it.
 * - Away from every translation, it is a line OCR missed the first time: it becomes a new
 *   untranslated bubble for the reviewer (or Retry) to translate.
 *
 * Our own translations show up in the OCR too; lines whose words match what was drawn
 * there are skipped.
 */
internal object LeftoverCheck {
    private const val MAX_NEW_PER_PAGE = 6

    /** Returns how many bubbles were grown or added. */
    fun apply(page: PageTranslation, lines: List<OcrLine>, drawn: List<DrawnText>): Int {
        var changes = 0
        var added = 0
        for (line in lines) {
            val words = words(line.text)
            if (words.isEmpty()) continue
            if (words.size == 1 && words[0].length == 1 && words[0] != "I" && words[0] != "A") continue
            val rect = RectF(line.left, line.top, line.right, line.bottom)
            if (rect.width() < 2f || rect.height() < 2f) continue

            // Left as drawn on purpose (sound effects, names) or still waiting for a translation.
            val untouchable = page.blocks.any { block ->
                (block.mode == TranslationBlock.MODE_KEEP || block.needsTranslation()) &&
                    overlapShare(rect, block.rect(3f)) > 0.3f
            }
            if (untouchable) continue

            // Our own Spanish, read back.
            val ownText = drawn.filter { RectF.intersects(it.textRect, rect) }
            if (ownText.isNotEmpty() && looksLike(words, ownText.flatMap { words(it.text) })) continue

            val lineHeight = rect.height()
            val near = drawn.filter { RectF.intersects(it.area.expanded(lineHeight * 1.5f), rect) }
            if (near.isNotEmpty()) {
                // Inside a wiped bubble nothing can be left; around it, it's someone else's text.
                if (near.any { it.inBubble && it.area.contains(rect.centerX(), rect.centerY()) }) continue
                val boxed = near.filterNot { it.inBubble }
                if (boxed.isNotEmpty()) {
                    if (growNearestBlock(page, rect, boxed)) changes++
                    continue
                }
            }

            // Missed by the first OCR: add it as a bubble still to translate.
            val readable = words.any { it.length >= 3 && it.any { c -> c in "AEIOUY" } }
            if (!readable || added >= MAX_NEW_PER_PAGE) continue
            val overlapsExisting = page.blocks.any { overlapShare(rect, it.rect(2f)) > 0.2f }
            if (overlapsExisting) continue
            page.blocks += TranslationBlock(
                text = line.text,
                width = rect.width(),
                height = rect.height(),
                x = rect.left,
                y = rect.top,
                symHeight = rect.height(),
                symWidth = rect.height() * 0.6f,
                angle = 0f,
            )
            added++
            changes++
        }
        return changes
    }

    /** Grows the drawn text block closest to the leftover so its cleanup covers it. */
    private fun growNearestBlock(page: PageTranslation, rect: RectF, boxed: List<DrawnText>): Boolean {
        val candidates = page.blocks.filter { block ->
            block.mode != TranslationBlock.MODE_KEEP && !block.needsTranslation() &&
                boxed.any { RectF.intersects(it.area, block.rect(1f)) }
        }
        val block = candidates.minByOrNull { distance(it.rect(0f), rect) } ?: return false
        val glyph = letterHeight(block)
        // Much bigger lettering than the bubble's own, or far away: not part of it.
        if (rect.height() > max(glyph, 4f) * 2.5f) return false
        if (distance(block.rect(0f), rect) > max(glyph, rect.height()) * 1.5f) return false
        val left = min(block.x, rect.left)
        val top = min(block.y, rect.top)
        val right = max(block.x + block.width, rect.right)
        val bottom = max(block.y + block.height, rect.bottom)
        if (left == block.x && top == block.y && right == block.x + block.width && bottom == block.y + block.height) {
            return false
        }
        block.x = left
        block.y = top
        block.width = right - left
        block.height = bottom - top
        return true
    }

    private fun words(text: String): List<String> =
        Normalizer.normalize(text.uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .split(Regex("[^A-Z0-9]+"))
            .filter { it.isNotEmpty() }

    /** At least 60% of the line's words appear (allowing one OCR slip) in what we drew. */
    private fun looksLike(lineWords: List<String>, drawnWords: List<String>): Boolean {
        if (drawnWords.isEmpty()) return false
        val matched = lineWords.count { word ->
            drawnWords.any { it == word || (word.length >= 4 && editDistanceAtMostOne(it, word)) } ||
                drawnWords.joinToString("").contains(word) // words split by hyphenation
        }
        return matched * 10 >= lineWords.size * 6
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++; j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    private fun TranslationBlock.rect(pad: Float) = RectF(x - pad, y - pad, x + width + pad, y + height + pad)

    private fun RectF.expanded(amount: Float) = RectF(left - amount, top - amount, right + amount, bottom + amount)

    private fun overlapShare(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val area = a.width() * a.height()
        return if (area <= 0f) 0f else w * h / area
    }

    private fun distance(a: RectF, b: RectF): Float {
        val dx = max(0f, max(a.left - b.right, b.left - a.right))
        val dy = max(0f, max(a.top - b.bottom, b.top - a.bottom))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
