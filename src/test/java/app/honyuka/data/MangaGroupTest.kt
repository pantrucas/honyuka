package app.honyuka.data

import android.content.Context
import app.honyuka.model.MangaGroup
import app.honyuka.model.SourceLanguage
import app.honyuka.model.groupFor
import app.honyuka.model.sourceLanguageFor
import app.honyuka.model.withMembers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers how groups are stored and how a chapter picks the language it gets translated from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MangaGroupTest {

    private lateinit var preferences: HonyukaPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        preferences = HonyukaPreferences(context)
    }

    @Test
    fun `groups survive a round trip through preferences`() {
        val groups = listOf(
            MangaGroup(
                id = "g1",
                name = "Japanese",
                sourceLanguageName = SourceLanguage.JAPANESE.name,
                mangaKeys = listOf("SourceA/Berserk", "SourceA/Vagabond"),
            ),
            MangaGroup(id = "g2", name = "Leftovers"),
        )

        preferences.saveMangaGroups(groups)

        assertEquals(groups, preferences.mangaGroups())
        assertEquals(SourceLanguage.JAPANESE, preferences.mangaGroups().first().sourceLanguage)
        assertNull(preferences.mangaGroups()[1].sourceLanguage)
    }

    @Test
    fun `an empty store reads back as no groups`() {
        assertTrue(preferences.mangaGroups().isEmpty())
    }

    @Test
    fun `language comes from the group, falling back to the global setting`() {
        val groups = listOf(
            MangaGroup(
                id = "g1",
                name = "Japanese",
                sourceLanguageName = SourceLanguage.JAPANESE.name,
                mangaKeys = listOf("SourceA/Berserk"),
            ),
            MangaGroup(id = "g2", name = "No language set", mangaKeys = listOf("SourceA/One Piece")),
        )

        // A group that names a language wins over the global setting.
        assertEquals(
            SourceLanguage.JAPANESE,
            groups.sourceLanguageFor("SourceA/Berserk", fallback = SourceLanguage.ENGLISH),
        )
        // A group without one falls back.
        assertEquals(
            SourceLanguage.ENGLISH,
            groups.sourceLanguageFor("SourceA/One Piece", fallback = SourceLanguage.ENGLISH),
        )
        // So does a manga in no group at all.
        assertEquals(
            SourceLanguage.KOREAN,
            groups.sourceLanguageFor("SourceA/Vagabond", fallback = SourceLanguage.KOREAN),
        )
    }

    @Test
    fun `groupFor finds the owning group`() {
        val groups = listOf(
            MangaGroup(id = "g1", name = "Japanese", mangaKeys = listOf("SourceA/Berserk")),
        )

        assertEquals("g1", groups.groupFor("SourceA/Berserk")?.id)
        assertNull(groups.groupFor("SourceA/Vagabond"))
    }

    @Test
    fun `assigning a manga takes it out of its previous group`() {
        val groups = listOf(
            MangaGroup(id = "g1", name = "Japanese", mangaKeys = listOf("SourceA/Berserk", "SourceA/Vagabond")),
            MangaGroup(id = "g2", name = "English", mangaKeys = emptyList()),
        )

        val moved = groups.withMembers("g2", listOf("SourceA/Berserk"))

        assertEquals(listOf("SourceA/Vagabond"), moved.first { it.id == "g1" }.mangaKeys)
        assertEquals(listOf("SourceA/Berserk"), moved.first { it.id == "g2" }.mangaKeys)
        // Exclusive membership is what keeps the resolved language unambiguous.
        assertEquals(1, moved.count { "SourceA/Berserk" in it.mangaKeys })
    }
}
