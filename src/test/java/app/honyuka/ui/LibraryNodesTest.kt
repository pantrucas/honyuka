package app.honyuka.ui

import android.net.Uri
import app.honyuka.model.ChapterEntry
import app.honyuka.model.MangaGroup
import app.honyuka.model.SourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the library list shape: extensions no longer form a navigation level, and manga are laid
 * out under the groups the user made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryNodesTest {

    @Test
    fun `manga nodes drop the extension level and sort by title`() {
        val nodes = listOf(
            chapter("SourceB", "Vagabond", "Ch 1"),
            chapter("SourceA", "Berserk", "Ch 2"),
            chapter("SourceA", "Berserk", "Ch 1"),
            chapter("SourceC", "One Piece", "Ch 1"),
        ).toMangaNodes()

        assertEquals(listOf("Berserk", "One Piece", "Vagabond"), nodes.map { it.name })
        assertEquals(2, nodes.first { it.name == "Berserk" }.chapters.size)
        assertEquals("SourceA/Berserk", nodes.first { it.name == "Berserk" }.key)
    }

    @Test
    fun `the same title in two extensions stays two entries`() {
        val nodes = listOf(
            chapter("SourceA", "Berserk", "Ch 1"),
            chapter("SourceB", "Berserk", "Ch 1"),
            chapter("SourceA", "Vagabond", "Ch 1"),
        ).toMangaNodes()

        assertEquals(3, nodes.size)
        assertEquals(listOf("SourceA/Berserk", "SourceB/Berserk"), nodes.filter { it.name == "Berserk" }.map { it.key })

        // Only the ambiguous title is worth labelling with its extension.
        val duplicated = nodes.duplicatedNames()
        assertTrue(duplicated.containsName("Berserk"))
        assertFalse(duplicated.containsName("Vagabond"))
    }

    @Test
    fun `pending counts ignore chapters already translated`() {
        val node = listOf(
            chapter("SourceA", "Berserk", "Ch 1", translated = true),
            chapter("SourceA", "Berserk", "Ch 2"),
            chapter("SourceA", "Berserk", "Ch 3"),
        ).toMangaNodes().single()

        assertEquals(3, node.chapters.size)
        assertEquals(1, node.translatedCount)
        assertEquals(2, node.pendingCount)
    }

    @Test
    fun `sections follow group order and unassigned manga land in Ungrouped`() {
        val nodes = listOf(
            chapter("SourceA", "Berserk", "Ch 1"),
            chapter("SourceA", "One Piece", "Ch 1"),
            chapter("SourceA", "Vagabond", "Ch 1"),
        ).toMangaNodes()

        val groups = listOf(
            MangaGroup(id = "g1", name = "Japanese", sourceLanguageName = SourceLanguage.JAPANESE.name, mangaKeys = listOf("SourceA/Berserk")),
            MangaGroup(id = "g2", name = "English", sourceLanguageName = SourceLanguage.ENGLISH.name, mangaKeys = listOf("SourceA/One Piece")),
        )

        val sections = nodes.toGroupSections(groups)

        assertEquals(listOf("Japanese", "English", UNGROUPED_TITLE), sections.map { it.title })
        assertEquals(listOf("Berserk"), sections[0].manga.map { it.name })
        assertEquals(listOf("One Piece"), sections[1].manga.map { it.name })
        assertEquals(listOf("Vagabond"), sections[2].manga.map { it.name })
        assertNull(sections[2].group)
        assertEquals(SourceLanguage.JAPANESE, sections[0].group?.sourceLanguage)
    }

    @Test
    fun `a group the user has not filled yet still shows up`() {
        val nodes = listOf(chapter("SourceA", "Berserk", "Ch 1")).toMangaNodes()
        val groups = listOf(MangaGroup(id = "g1", name = "Korean"))

        val sections = nodes.toGroupSections(groups)

        assertEquals(listOf("Korean", UNGROUPED_TITLE), sections.map { it.title })
        assertTrue(sections[0].manga.isEmpty())
        assertEquals(0, sections[0].pendingCount)
    }

    @Test
    fun `without groups the list is one unlabelled section`() {
        val nodes = listOf(
            chapter("SourceA", "Berserk", "Ch 1"),
            chapter("SourceA", "Vagabond", "Ch 1"),
        ).toMangaNodes()

        val sections = nodes.toGroupSections(emptyList())

        assertEquals(1, sections.size)
        assertNull(sections.single().title)
        assertEquals(2, sections.single().manga.size)
    }

    @Test
    fun `the Ungrouped section is dropped once everything is assigned`() {
        val nodes = listOf(chapter("SourceA", "Berserk", "Ch 1")).toMangaNodes()
        val groups = listOf(MangaGroup(id = "g1", name = "Japanese", mangaKeys = listOf("SourceA/Berserk")))

        val sections = nodes.toGroupSections(groups)

        assertEquals(listOf("Japanese"), sections.map { it.title })
    }

    @Test
    fun `section totals add up across its manga`() {
        val nodes = listOf(
            chapter("SourceA", "Berserk", "Ch 1", translated = true),
            chapter("SourceA", "Berserk", "Ch 2"),
            chapter("SourceA", "Vagabond", "Ch 1"),
        ).toMangaNodes()
        val groups = listOf(
            MangaGroup(id = "g1", name = "Japanese", mangaKeys = listOf("SourceA/Berserk", "SourceA/Vagabond")),
        )

        val section = nodes.toGroupSections(groups).single()

        assertEquals(2, section.manga.size)
        assertEquals(3, section.chapterCount)
        assertEquals(2, section.pendingCount)
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
