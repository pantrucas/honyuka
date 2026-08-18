package app.honyuka.service

import android.net.Uri
import app.honyuka.model.ChapterEntry
import app.honyuka.model.MangaGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers what a batch actually queues — the part that decides how much API quota a tap spends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationQueuePlanTest {

    private val library = listOf(
        chapter("SourceA", "Berserk", "Ch 1", translated = true),
        chapter("SourceA", "Berserk", "Ch 2"),
        chapter("SourceA", "Berserk", "Ch 3"),
        chapter("SourceA", "Vagabond", "Ch 1"),
        chapter("SourceB", "One Piece", "Ch 1"),
        chapter("SourceB", "One Piece", "Ch 2", translated = true),
    )

    @Test
    fun `a manga batch skips chapters already translated`() {
        val queued = TranslationQueuePlan.forManga(library, "SourceA/Berserk")

        assertEquals(listOf("Ch 2", "Ch 3"), queued.map { it.chapterDisplayName })
        assertTrue(queued.none { it.translated })
    }

    @Test
    fun `a manga batch ignores other manga sharing the title elsewhere`() {
        val queued = TranslationQueuePlan.forManga(library, "SourceB/One Piece")

        assertEquals(listOf("Ch 1"), queued.map { it.chapterDisplayName })
        assertTrue(queued.all { it.sourceName == "SourceB" })
    }

    @Test
    fun `an unknown manga queues nothing`() {
        assertTrue(TranslationQueuePlan.forManga(library, "SourceA/Missing").isEmpty())
    }

    @Test
    fun `a group batch gathers pending chapters across all its manga`() {
        val group = MangaGroup(
            id = "g1",
            name = "Japanese",
            mangaKeys = listOf("SourceA/Berserk", "SourceA/Vagabond"),
        )

        val queued = TranslationQueuePlan.forGroup(library, group)

        assertEquals(3, queued.size)
        assertEquals(setOf("SourceA/Berserk", "SourceA/Vagabond"), queued.map { it.mangaKey }.toSet())
        assertTrue(queued.none { it.translated })
    }

    @Test
    fun `an empty or missing group queues nothing`() {
        assertTrue(TranslationQueuePlan.forGroup(library, MangaGroup(id = "g1", name = "Empty")).isEmpty())
        assertTrue(TranslationQueuePlan.forGroup(library, null).isEmpty())
    }

    @Test
    fun `a fully translated manga queues nothing`() {
        val done = listOf(chapter("SourceA", "Done", "Ch 1", translated = true))

        assertTrue(TranslationQueuePlan.forManga(done, "SourceA/Done").isEmpty())
    }

    private fun chapter(
        source: String,
        manga: String,
        name: String,
        translated: Boolean = false,
    ): ChapterEntry = ChapterEntry(
        sourceName = source,
        mangaName = manga,
        chapterDisplayName = name,
        chapterBaseName = name,
        chapterUri = Uri.parse("content://test/$source/$manga/$name"),
        thumbnailUri = null,
        isCbz = true,
        translated = translated,
    )
}
