package hu.szfvbus.data.api

import hu.szfvbus.util.TimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Egy hír a MÁVINFORM-listából, mielőtt az oldalát beolvasnánk. */
data class MavinformItem(
    val path: String,
    val title: String,
    val validFrom: Long?,
    val validTo: Long?,
    val lastChanged: String,
)

/**
 * A MÁV-csoport forgalmi hírei (www.mavcsoport.hu/mavinform), amelyek a Volánbusz által üzemeltetett
 * székesfehérvári helyi buszokra is kiterjednek. Az oldalnak nincs API-ja, ezért a lapjait olvassuk be.
 */
class MavinformClient(private val http: OkHttpClient) {

    /** A Fejér megyei helyi buszok aktuális és közelgő hírei. */
    suspend fun list(): List<MavinformItem> = MavinformParser.parseList(get(LIST_URL))

    /** Egy hír szövege, ahogy az oldalon áll; lásd [MavinformParser.displayHtml]. */
    suspend fun body(path: String): String = MavinformParser.bodySection(get(BASE_URL + path))

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MÁVINFORM hiba: HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Üres válasz a MÁVINFORM-tól")
        }
    }

    companion object {
        const val BASE_URL = "https://www.mavcsoport.hu"
        private const val FEJER = "10847"
        const val LIST_URL = "$BASE_URL/mavinform?field_modalitas_value%5B%5D=helyibusz" +
            "&field_territorial_scope_target_id%5B%5D=$FEJER"
    }
}

object MavinformParser {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd. HH:mm")

    private val itemRegex = Regex("""<div class="custom-news-item">(.*?)(?=<div class="custom-news-item">|$)""", RegexOption.DOT_MATCHES_ALL)
    private val linkRegex = Regex("""<h3[^>]*field-content[^>]*>\s*<a href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val fromRegex = Regex("""field-date-from">\s*<span[^>]*date-display-single[^>]*>([^<]+)</span>""")
    private val toRegex = Regex("""news-date-to">\s*<span[^>]*date-display-single[^>]*>([^<]+)</span>""")
    private val changedRegex = Regex("""news-last-changed">.*?<span[^>]*field-content[^>]*>([^<]+)</span>""", RegexOption.DOT_MATCHES_ALL)

    fun parseList(html: String): List<MavinformItem> {
        // Az oldalsáv az egész ország friss híreit is mutatja; a lista a szűrőűrlap után kezdődik.
        val start = html.indexOf("view-display-id-mavinform_page").takeIf { it >= 0 } ?: 0
        return itemRegex.findAll(html.substring(start)).mapNotNull { m ->
            val block = m.groupValues[1]
            val link = linkRegex.find(block) ?: return@mapNotNull null
            MavinformItem(
                path = link.groupValues[1],
                title = htmlToText(link.groupValues[2]),
                validFrom = fromRegex.find(block)?.groupValues?.get(1)?.let(::parseDate),
                validTo = toRegex.find(block)?.groupValues?.get(1)?.let(::parseDate),
                lastChanged = changedRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty(),
            )
        }.toList()
    }

    fun bodySection(html: String): String {
        val start = html.indexOf("<div class=\"field-body\">")
        if (start < 0) return ""
        val end = html.indexOf("<div class=\"social\">", start).takeIf { it > 0 } ?: html.length
        return html.substring(start + "<div class=\"field-body\">".length, end)
    }

    /**
     * A szöveg arra szűkítve, amit az alkalmazás mutat: szöveg félkövér részekkel és felsorolásjelekkel,
     * képek nélkül.
     */
    fun displayHtml(body: String): String =
        body
            .replace(Regex("<img[^>]*>"), "")
            .replace(Regex("""<(p|div|h\d)[^>]*>(\s|&nbsp;|<br\s*/?>)*</\1>"""), "")
            .replace(Regex("""\s(style|class|title)="[^"]*""""), "")
            // A HTML-szövegátalakító nem jeleníti meg a listákat; a felsorolásjeleket kézzel tesszük ki.
            .replace(Regex("<li[^>]*>\\s*"), "<br>• ")
            .replace(Regex("</?(ul|ol|li)[^>]*>"), "")
            .replace(Regex("(</div>\\s*)+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Hogy egy hír Székesfehérvárról szól-e (a megyei listán Dunaújváros is szerepel). */
    fun isAboutTheCity(title: String, body: String): Boolean =
        title.contains("fehérvár", ignoreCase = true) ||
            (body.contains("Székesfehérvár", ignoreCase = true) && !title.contains("Dunaújváros", ignoreCase = true))

    private val boxRegex = Regex("""<div[^>]*bluebox[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val strongRegex = Regex("""<strong>\s*([0-9]{1,3}[A-Za-z]{0,2})\s*</strong>""")
    private val suffixRegex = Regex("""(?<![\p{L}\d.])([0-9]{1,3}[A-Za-z]{0,2})-(?:as|es|os|ös|ás)(?!\p{L})""")
    private val tokenRegex = Regex("""(?<![\p{L}\d.:])([0-9]{1,3}[A-Za-z]{0,2})(?![\p{L}\d:])""")

    /**
     * A hírben említett városi vonalak. A hírek egy dobozban sorolják fel őket
     * ("Érintett járatok: 14, 14G, 20"), félkövéren, vagy a szövegben ("a 16-os és 37-es autóbuszok").
     * Csak valódi vonalak nevei számítanak, így a dátumok és a házszámok kimaradnak.
     */
    fun affectedLines(bodyHtml: String, knownLines: Collection<String>): List<String> {
        val known = knownLines.associateBy { it.uppercase() }
        val found = LinkedHashSet<String>()
        fun add(token: String) {
            known[token.uppercase()]?.let(found::add)
        }
        boxRegex.findAll(bodyHtml).forEach { box ->
            val text = htmlToText(box.groupValues[1])
            // A helyközi vonalaknak saját dobozuk van.
            if (!text.contains("helyközi vonal", ignoreCase = true)) tokenRegex.findAll(text).forEach { add(it.groupValues[1]) }
        }
        strongRegex.findAll(bodyHtml).forEach { add(it.groupValues[1]) }
        suffixRegex.findAll(htmlToText(bodyHtml)).forEach { add(it.groupValues[1]) }
        return found.toList()
    }

    fun htmlToText(html: String): String = html
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&quot;", "\"")
        .replace("&ndash;", "–")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Egyszerű szöveg, amely megtartja a [html] bekezdéseit és sortöréseit. */
    fun htmlToLines(html: String): String =
        html.split(Regex("""<br\s*/?>|</p>|</div>|</h\d>"""))
            .map(::htmlToText)
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun parseDate(text: String): Long? = runCatching {
        LocalDateTime.parse(text.trim(), dateFormat).atZone(TimeUtil.ZONE).toEpochSecond()
    }.getOrNull()
}
