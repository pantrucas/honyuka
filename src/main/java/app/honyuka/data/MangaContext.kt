package app.honyuka.data

import java.util.Locale

/**
 * The per-manga context sheet: free text the user can edit (characters, gender, how they
 * talk, names that must not be translated). Translation runs append characters Gemini
 * learns under [AUTO_HEADER]; the user's own notes above it always take priority.
 */
object MangaContext {
    const val AUTO_HEADER = "== Personajes (automático) =="
    private const val MAX_AUTO_LINES = 60
    private const val MAX_LINE_LENGTH = 160

    /** Adds the characters in [entries] ("NAME — notes") that the sheet doesn't mention yet. */
    fun mergeCharacters(context: String, entries: Collection<String>): String {
        val lower = context.lowercase(Locale.ROOT)
        val existingAuto = context.substringAfter(AUTO_HEADER, "").lines().count { it.isNotBlank() }
        val added = mutableListOf<String>()
        for (raw in entries) {
            val line = raw.replace('\n', ' ').trim().take(MAX_LINE_LENGTH)
            val name = line.split('—', '-', ':', ';', ',', '(').first().trim()
            if (name.length < 2 || name.length > 40) continue
            if (lower.contains(name.lowercase(Locale.ROOT))) continue
            if (added.any { it.startsWith(name, ignoreCase = true) }) continue
            if (existingAuto + added.size >= MAX_AUTO_LINES) break
            added += line
        }
        if (added.isEmpty()) return context
        val base = context.trimEnd()
        val withHeader = if (AUTO_HEADER in base) base else "$base\n\n$AUTO_HEADER"
        return withHeader + "\n" + added.joinToString("\n")
    }

    /** A first sheet for mangas we already know, so the very first chapter is right. */
    fun starterFor(mangaName: String): String = when {
        mangaName.contains("Blue Exorcist", ignoreCase = true) ||
            mangaName.contains("Ao no Exorcist", ignoreCase = true) -> BLUE_EXORCIST
        else -> ""
    }

    private val BLUE_EXORCIST = """
Notas para traducir Blue Exorcist (español latino neutro):
- RIN OKUMURA — hombre; hijo de Satán; impulsivo, habla informal y directo.
- YUKIO OKUMURA — hombre; hermano gemelo de Rin.
- SHIEMI MORIYAMA — mujer; dulce y tímida; es la heredera de Shemihaza.
- IZUMO KAMIKI — mujer; orgullosa y seca.
- RYUJI SUGURO — hombre; sus amigos le dicen BON (apodo, no traducir).
- RENZO SHIMA — hombre; relajado y bromista.
- KONEKOMARU MIWA — hombre; educado.
- SHURA KIRIGAKURE — mujer; desenfadada.
- ARTHUR A. ANGEL — hombre; el Paladín; arrogante y teatral. Se escribe ARTHUR, sin tilde.
- LIGHTNING — hombre; apodo de Lewin Light; NUNCA traducirlo como «rayo».
- MEPHISTO PHELES / SAMAEL — hombre; Rey del Tiempo; mete palabras en alemán (DANKE, EINS, ZWEI, DREI, conjuros en alemán) que se dejan tal cual.
- AMAIMON — hombre; Rey de la Tierra; habla como niño caprichoso.
- LUCIFER — hombre; Rey de la Luz; comandante en jefe de los Illuminati.
- SATÁN — hombre; padre de los Reyes demonio y de Rin y Yukio.
- EGYN — Rey del Agua; Homare le habla de usted («SU MAJESTAD»).
- HOMARE TODO — hombre; científico de los Illuminati; habla formal.
- SAIO — título del Consorte Sagrado (esposo de Shemihaza); se deja como SAIO.
- JEREMIAH — hombre; director de los Grigori.
Términos: PALADIN → PALADÍN; MEISTER se deja; Knights of the True Cross → CABALLEROS DE LA CRUZ VERDADERA; Blue Night → LA NOCHE AZUL; ILLUMINATI, GRIGORI, SHEMIHAZA, GEHENNA, ASSIAH se dejan.
""".trim()
}
