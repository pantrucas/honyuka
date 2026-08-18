package app.honyuka.ui

import app.honyuka.model.ChapterEntry
import app.honyuka.model.MangaGroup
import java.util.Locale

const val UNGROUPED_TITLE = "Ungrouped"

data class MangaNode(
    val sourceName: String,
    val name: String,
    val thumbnailChapter: ChapterEntry?,
    val chapters: List<ChapterEntry>,
) {
    val key: String = "$sourceName/$name"
    val translatedCount: Int = chapters.count { it.translated }
    val pendingCount: Int = chapters.size - translatedCount
}

/**
 * One block of the library list. A [group] of null means the manga are unassigned; a [title] of
 * null means the block is rendered without a header, which is what happens when the user has not
 * created any group yet.
 */
data class GroupSection(
    val group: MangaGroup?,
    val title: String?,
    val manga: List<MangaNode>,
) {
    val pendingCount: Int = manga.sumOf { it.pendingCount }
    val chapterCount: Int = manga.sumOf { it.chapters.size }
}

/**
 * Collapses chapters straight into manga, dropping the source (extension) level the list used to
 * navigate through. Manga are keyed by source and name, so two extensions carrying the same title
 * stay separate entries rather than being merged into one.
 */
fun List<ChapterEntry>.toMangaNodes(): List<MangaNode> {
    return groupBy { it.mangaKey }
        .map { (_, mangaChapters) ->
            val first = mangaChapters.first()
            MangaNode(
                sourceName = first.sourceName,
                name = first.mangaName,
                thumbnailChapter = mangaChapters.firstOrNull { it.thumbnailUri != null }
                    ?: mangaChapters.firstOrNull(),
                chapters = mangaChapters.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.chapterDisplayName },
                ),
            )
        }
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { node: MangaNode -> node.name }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { node: MangaNode -> node.sourceName },
        )
}

/**
 * Splits the manga into one section per group, in the order the user created them, followed by an
 * "Ungrouped" section. Groups the user made but has not filled yet are still listed, so they stay
 * visible and reachable.
 */
fun List<MangaNode>.toGroupSections(groups: List<MangaGroup>): List<GroupSection> {
    if (groups.isEmpty()) {
        return if (isEmpty()) emptyList() else listOf(GroupSection(null, null, this))
    }

    val sections = groups.map { group ->
        val keys = group.mangaKeys.toSet()
        GroupSection(group = group, title = group.name, manga = filter { it.key in keys })
    }
    val assigned = groups.flatMapTo(mutableSetOf()) { it.mangaKeys }
    val ungrouped = filterNot { it.key in assigned }
    return if (ungrouped.isEmpty()) sections else sections + GroupSection(null, UNGROUPED_TITLE, ungrouped)
}

/**
 * Names carried by more than one manga. The source is only worth showing on those cards — the
 * whole point of the change was to stop putting extensions in the user's way.
 */
fun List<MangaNode>.duplicatedNames(): Set<String> {
    return groupingBy { it.name.lowercase(Locale.ROOT) }
        .eachCount()
        .filterValues { it > 1 }
        .keys
}

fun Set<String>.containsName(name: String): Boolean = name.lowercase(Locale.ROOT) in this
