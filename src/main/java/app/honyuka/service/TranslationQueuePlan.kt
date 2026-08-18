package app.honyuka.service

import app.honyuka.model.ChapterEntry
import app.honyuka.model.MangaGroup

/**
 * Decides what a batch actually queues.
 *
 * Kept free of Android types so the selection rules can be tested directly rather than through the
 * service. Already-translated chapters are dropped: requeuing work that is done would burn API
 * quota for nothing.
 */
object TranslationQueuePlan {

    fun forManga(chapters: List<ChapterEntry>, mangaKey: String): List<ChapterEntry> {
        return chapters.filter { it.mangaKey == mangaKey && !it.translated }
    }

    fun forGroup(chapters: List<ChapterEntry>, group: MangaGroup?): List<ChapterEntry> {
        val keys = group?.mangaKeys?.toSet() ?: return emptyList()
        return chapters.filter { it.mangaKey in keys && !it.translated }
    }
}
