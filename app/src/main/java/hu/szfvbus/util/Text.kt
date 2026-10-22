package hu.szfvbus.util

import java.text.Normalizer

private const val CITY_PREFIX = "Székesfehérvár, "
private val diacritics = "\\p{Mn}+".toRegex()
private val spaces = "\\s+".toRegex()

private val hungarian = java.util.Locale.forLanguageTag("hu")

/**
 * "Székesfehérvár, Országzászló tér" -> "Országzászló tér",
 * "Székesfehérvár, vasútállomás" -> "Vasútállomás". A más települések megállói megtartják az előtagot.
 */
fun cleanStopName(name: String?): String {
    if (name == null) return ""
    val trimmed = name.replace(spaces, " ").trim()
    if (!trimmed.startsWith(CITY_PREFIX)) return trimmed
    return trimmed.removePrefix(CITY_PREFIX).replaceFirstChar { it.titlecase(hungarian) }
}

/**
 * Egy állomás kocsiállásai: "12. kocsiállás", "11. és 12. kocsiállás", "A kocsiállás".
 * Null, ha egy sem ismert.
 */
fun platformText(codes: List<String>): String? {
    if (codes.isEmpty()) return null
    // A számok magyarul sorszámnevek, ponttal; a betűk maradnak, ahogy vannak.
    val named = codes.map { if (it.all(Char::isDigit)) "$it." else it }
    val joined = if (named.size == 1) named[0] else named.dropLast(1).joinToString(", ") + " és " + named.last()
    // A nem törő szóköz egy sorban tartja a "14." és a "kocsiállás" szót.
    return "$joined kocsiállás"
}

/** Kisbetűs, ékezet nélküli alak a kereséshez ("Csapó utca" -> "csapo utca"). */
fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(diacritics, "").lowercase().trim()

/**
 * Természetes sorrend a vonalszámokhoz: 10 < 11 < 11A < 12 < 12Y < 100. A nem számos nevek a végére kerülnek,
 * ábécérendben.
 */
object LineNameComparator : Comparator<String> {
    private val pattern = "^(\\d+)(.*)$".toRegex()

    override fun compare(a: String, b: String): Int {
        val ma = pattern.find(a)
        val mb = pattern.find(b)
        return when {
            ma != null && mb != null -> {
                val na = ma.groupValues[1].toInt()
                val nb = mb.groupValues[1].toInt()
                if (na != nb) na.compareTo(nb) else ma.groupValues[2].compareTo(mb.groupValues[2])
            }
            ma != null -> -1
            mb != null -> 1
            else -> a.compareTo(b)
        }
    }
}
