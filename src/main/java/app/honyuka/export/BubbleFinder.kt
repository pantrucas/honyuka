package app.honyuka.export

import android.graphics.Bitmap
import app.honyuka.model.TranslationBlock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A white speech bubble found around some OCR text.
 *
 * Coordinates are in page pixels; masks are `width * height`, row-major, starting at
 * ([left], [top]). [inside] is the bubble's light area plus the lettering it encloses;
 * [paint] is what may be repainted to wipe the lettering (inside minus a 1 px rim
 * against the outline, minus any drawing that sits inside the bubble).
 */
internal class Bubble(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val inside: BooleanArray,
    val paint: BooleanArray,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    fun contains(x: Int, y: Int): Boolean {
        val lx = x - left
        val ly = y - top
        return lx in 0 until width && ly in 0 until height && inside[ly * width + lx]
    }

    /** True when most of the rectangle lies inside this bubble. */
    fun holds(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        var hits = 0
        var total = 0
        val stepX = max(1f, (x1 - x0) / 8f)
        val stepY = max(1f, (y1 - y0) / 8f)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                total++
                if (contains(x.toInt(), y.toInt())) hits++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && hits * 2 >= total
    }

    /**
     * Grows the rectangle (page coords) one pixel per side at a time while the new edge
     * stays inside the bubble, giving the largest box around the text that still fits in it.
     */
    fun growInside(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        var l = x0.coerceIn(left, right - 1)
        var t = y0.coerceIn(top, bottom - 1)
        var r = x1.coerceIn(l + 1, right)
        var b = y1.coerceIn(t + 1, bottom)
        fun rowInside(y: Int, from: Int, to: Int): Boolean {
            if (y < top || y >= bottom) return false
            var bad = 0
            val allowed = (to - from) / 30
            for (x in from until to) if (!contains(x, y) && ++bad > allowed) return false
            return true
        }
        fun colInside(x: Int, from: Int, to: Int): Boolean {
            if (x < left || x >= right) return false
            var bad = 0
            val allowed = (to - from) / 30
            for (y in from until to) if (!contains(x, y) && ++bad > allowed) return false
            return true
        }
        var grew = true
        while (grew) {
            grew = false
            if (colInside(l - 1, t, b)) { l--; grew = true }
            if (colInside(r, t, b)) { r++; grew = true }
            if (rowInside(t - 1, l, r)) { t--; grew = true }
            if (rowInside(b, l, r)) { b++; grew = true }
        }
        return intArrayOf(l, t, r, b)
    }
}

/**
 * Finds the white speech bubble around a piece of OCR text by flood-filling the light
 * pixels from the text outwards until the dark outline stops it.
 *
 * OCR often reads only some lines of a bubble ("IN YET ANOTHER … EXORCISTS" without the
 * "BATTLE BETWEEN" in the middle). Knowing the whole bubble lets the renderer wipe every
 * letter in it and use its full width, and lets OCR pieces of one bubble be joined.
 *
 * Gives up (returns null) for text drawn over artwork, on dark or textured backgrounds,
 * and for open bubbles that leak into the page: then the old box-based cleanup is used.
 */
internal object BubbleFinder {
    /** Bubble paper is at least this light (0–255). */
    private const val LIGHT = 200

    /** A bubble never covers more than this share of the page. */
    private const val MAX_PAGE_SHARE = 0.10f
    private const val MAX_WIDTH_SHARE = 0.5f
    private const val MAX_HEIGHT_SHARE = 0.45f

    /** Light area + enclosed letters must fill this much of the bubble's bounding box. */
    private const val MIN_FILL_RATIO = 0.45f

    /** Shapes inside the bubble taller than this many letter heights are drawings, not text. */
    private const val MAX_TEXT_HEIGHT_IN_GLYPHS = 2.5f

    fun luminance(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return pixels
    }

    /**
     * @param lum page luminance from [luminance]
     * @param x0 text box, page pixels
     * @param glyph height of one original letter, page pixels
     */
    fun find(
        lum: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        glyph: Float,
    ): Bubble? {
        val maxArea = (pageWidth.toLong() * pageHeight * MAX_PAGE_SHARE).toInt()
        val seen = BooleanArray(pageWidth * pageHeight)
        val queue = IntArray(maxArea + 1)
        var head = 0
        var tail = 0
        val sx0 = x0.toInt().coerceIn(0, pageWidth - 1)
        val sy0 = y0.toInt().coerceIn(0, pageHeight - 1)
        val sx1 = x1.roundToInt().coerceIn(sx0 + 1, pageWidth)
        val sy1 = y1.roundToInt().coerceIn(sy0 + 1, pageHeight)
        for (y in sy0 until sy1) {
            for (x in sx0 until sx1) {
                val i = y * pageWidth + x
                if (lum[i] >= LIGHT && !seen[i]) {
                    if (tail >= maxArea) return null
                    seen[i] = true
                    queue[tail++] = i
                }
            }
        }
        if (tail == 0) return null

        var minX = pageWidth
        var minY = pageHeight
        var maxX = -1
        var maxY = -1
        while (head < tail) {
            val i = queue[head++]
            val x = i % pageWidth
            val y = i / pageWidth
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            // Four neighbours: left, right, up, down.
            for (n in 0 until 4) {
                val nx = when (n) { 0 -> x - 1; 1 -> x + 1; else -> x }
                val ny = when (n) { 2 -> y - 1; 3 -> y + 1; else -> y }
                if (nx < 0 || ny < 0 || nx >= pageWidth || ny >= pageHeight) continue
                val ni = ny * pageWidth + nx
                if (seen[ni] || lum[ni] < LIGHT) continue
                if (tail >= maxArea) return null // leaked into the page: not a closed bubble
                seen[ni] = true
                queue[tail++] = ni
            }
        }

        val width = maxX - minX + 1
        val height = maxY - minY + 1
        if (width > pageWidth * MAX_WIDTH_SHARE || height > pageHeight * MAX_HEIGHT_SHARE) return null
        // Touching the page edge means the light area runs off the page (gutter, sky…).
        if (minX <= 0 || minY <= 0 || maxX >= pageWidth - 1 || maxY >= pageHeight - 1) return null

        val fill = BooleanArray(width * height)
        for (y in 0 until height) {
            val row = (minY + y) * pageWidth + minX
            for (x in 0 until width) fill[y * width + x] = seen[row + x]
        }

        // Dark shapes the light area surrounds (letters) = not fill, not reachable from the border.
        val outside = BooleanArray(width * height)
        val q = IntArray(width * height)
        head = 0
        tail = 0
        fun pushOutside(i: Int) {
            if (!fill[i] && !outside[i]) {
                outside[i] = true
                q[tail++] = i
            }
        }
        for (x in 0 until width) {
            pushOutside(x)
            pushOutside((height - 1) * width + x)
        }
        for (y in 0 until height) {
            pushOutside(y * width)
            pushOutside(y * width + width - 1)
        }
        while (head < tail) {
            val i = q[head++]
            val x = i % width
            val y = i / width
            if (x > 0) pushOutside(i - 1)
            if (x < width - 1) pushOutside(i + 1)
            if (y > 0) pushOutside(i - width)
            if (y < height - 1) pushOutside(i + width)
        }
        val inside = BooleanArray(width * height)
        var insideCount = 0
        for (i in inside.indices) {
            inside[i] = fill[i] || !outside[i]
            if (inside[i]) insideCount++
        }
        if (insideCount < width * height * MIN_FILL_RATIO) return null

        // Enclosed shapes taller than a few letters are drawings inside the bubble: keep them.
        val art = BooleanArray(width * height)
        val label = BooleanArray(width * height)
        val maxTextHeight = max(glyph, 4f) * MAX_TEXT_HEIGHT_IN_GLYPHS
        for (start in inside.indices) {
            if (fill[start] || !inside[start] || label[start]) continue
            head = 0
            tail = 0
            label[start] = true
            q[tail++] = start
            var top = height
            var bottom = -1
            while (head < tail) {
                val i = q[head++]
                val x = i % width
                val y = i / width
                top = min(top, y)
                bottom = max(bottom, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val ni = ny * width + nx
                    if (!label[ni] && !fill[ni] && inside[ni]) {
                        label[ni] = true
                        q[tail++] = ni
                    }
                }
            }
            if (bottom - top + 1 > maxTextHeight) {
                for (k in 0 until tail) art[q[k]] = true
            }
        }

        val paint = BooleanArray(width * height)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (!inside[i] || !inside[i - 1] || !inside[i + 1] || !inside[i - width] || !inside[i + width]) continue
                if (art[i] || art[i - 1] || art[i + 1] || art[i - width] || art[i + width]) continue
                paint[i] = true
            }
        }
        return Bubble(minX, minY, width, height, inside, paint)
    }
}

/** Height of one original capital letter, robust to a tiny first symbol such as "…". */
internal fun letterHeight(block: TranslationBlock): Float {
    val lines = block.text.count { it == '\n' } + 1
    val perLine = block.height / lines
    return max(block.symHeight, perLine * 0.7f).coerceAtMost(perLine.coerceAtLeast(1f))
}

/** One piece right under the other (small gap) and overlapping sideways. */
internal fun stackedClosely(a: TranslationBlock, b: TranslationBlock): Boolean {
    val glyph = max(letterHeight(a), letterHeight(b))
    val (upper, lower) = if (a.y <= b.y) a to b else b to a
    val gap = lower.y - (upper.y + upper.height)
    if (gap > glyph * 2.5f) return false
    val overlapX = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
    return overlapX > -glyph
}

/** Finds each text's bubble once per page; texts in the same bubble get the same object. */
internal class BubbleCache(
    private val lum: IntArray,
    private val pageWidth: Int,
    private val pageHeight: Int,
) {
    private val found = mutableListOf<Bubble>()
    private val misses = HashSet<List<Float>>()

    fun of(block: TranslationBlock): Bubble? {
        val x1 = block.x + block.width
        val y1 = block.y + block.height
        found.firstOrNull { it.holds(block.x, block.y, x1, y1) }?.let { return it }
        val key = listOf(block.x, block.y, x1, y1)
        if (key in misses) return null
        val bubble = runCatching {
            BubbleFinder.find(lum, pageWidth, pageHeight, block.x, block.y, x1, y1, letterHeight(block))
        }.getOrNull()?.takeIf { it.holds(block.x, block.y, x1, y1) }
        if (bubble == null) misses += key else found += bubble
        return bubble
    }
}

/**
 * Joins pieces that sit in the same bubble right above/below each other, repeatedly,
 * using [merge] (which must put the upper piece first).
 */
internal fun mergeSameBubble(
    blocks: List<TranslationBlock>,
    bubbles: BubbleCache,
    merge: (TranslationBlock, TranslationBlock) -> TranslationBlock,
): MutableList<TranslationBlock> {
    val result = blocks.toMutableList()
    var changed = result.size > 1
    while (changed) {
        changed = false
        loop@ for (i in result.indices) {
            val bubble = bubbles.of(result[i]) ?: continue
            for (j in result.indices) {
                if (i == j || bubbles.of(result[j]) !== bubble) continue
                if (!stackedClosely(result[i], result[j])) continue
                val (upper, lower) = if (result[i].y <= result[j].y) result[i] to result[j] else result[j] to result[i]
                val merged = merge(upper, lower)
                val (first, second) = if (i < j) i to j else j to i
                result.removeAt(second)
                result[first] = merged
                changed = true
                break@loop
            }
        }
    }
    return result
}
