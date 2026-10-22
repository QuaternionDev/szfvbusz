package hu.szfvbus.ui.notice

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hu.szfvbus.data.model.LaunchNotice
import hu.szfvbus.ui.common.alertAccent
import hu.szfvbus.ui.common.period
import hu.szfvbus.util.LineNameComparator

/** A térképen és az élő követésről szóló figyelmeztetésen használt sárga. */
val LiveCoverageYellow = Color(0xFFFFC107)
val OnLiveCoverageYellow = Color(0xFF3A2C00)

/** A figyelmeztetés címke- és ikonszíne; világos témán a sárga helyett sötétebb borostyán, hogy olvasható maradjon. */
@Composable
private fun liveCoverageAccent(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) LiveCoverageYellow else Color(0xFF8A6100)

/** Egy bekezdés vége, amelyet újabb bekezdés követ. */
private val paragraphBreak = Regex("""</p>\s*(?=<(p|div|h\d)[\s>])""")

private const val LIVE_COVERAGE_TITLE ="Nem minden busz követhető még élőben"

private const val LIVE_COVERAGE_BODY =
    "<p>A fehérvári helyi buszokra <b>most szerelik fel a fedélzeti egységeket (OBU)</b>, amelyek GPS " +
        "alapján küldik a busz helyzetét. A város tájékoztatása szerint (2026. október 7.) " +
        "<b>nagyjából egy hónapon belül minden helyi busz megkapja</b>.</p>" +
        "<p>Addig <b>nem minden busz látszik élőben</b>. Ami a térképen látszik, az valós helyzet. Ha egy " +
        "járat nem látszik, attól még <b>közlekedhet</b>: ilyenkor a menetrend szerinti időket mutatjuk, " +
        "ÉLŐ jelzés nélkül.</p>" +
        "<p>A rendszer élesítése után minden járat helyzete és késése megjelenik a MÁV-csoport " +
        "utastájékoztatásában, így ebben az alkalmazásban is.</p>"

/** A Székesfehérvár Városportál cikke az OBU-k felszereléséről. */
private const val OBU_ARTICLE_URL =
    "https://www.szekesfehervar.hu/obu-a-fehervari-buszokon-ujabb-lepes-az-utasbarat-kozossegi-kozlekedes-erdekeben"

/** Hibajelzéshez és a jogtulajdonosok megkereséseihez. */
const val CONTACT_EMAIL = "szfvbuszapp@quaternion.hu"

const val UNOFFICIAL_TITLE = "Nem hivatalos alkalmazás"

private const val UNOFFICIAL_BODY =
    "<p>Az SzfvBusz <b>független projekt</b>: nem a MÁV-csoport vagy a Volánbusz terméke, és nem áll " +
        "kapcsolatban velük.</p>" +
        "<p>Az adatokat a MÁV-csoport nyilvános utazástervezőjéből tölti le. Ezek eltérhetnek a valóságtól, " +
        "ezért utazás előtt érdemes a <b>hivatalos forrásokat</b> is megnézni.</p>" +
        "<p>Hibát találtál, vagy kérdésed van? Írj a <b>$CONTACT_EMAIL</b> címre. Ezt a címet a Névjegyben " +
        "is megtalálod.</p>"

/** Kis sárga felkiáltójel a térképen, mindig látható; az élő követésről szóló figyelmeztetést nyitja meg. */
@Composable
fun LiveCoverageMark(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // A jel kicsi, de a koppintható terület a szokásos 48 dp.
    Box(modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = LiveCoverageYellow,
            contentColor = OnLiveCoverageYellow,
            shadowElevation = 4.dp,
            modifier = Modifier.size(30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PriorityHigh, contentDescription = LIVE_COVERAGE_TITLE, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Teljes képernyős figyelmeztetés: ikon, cím, görgethető szöveg, alatta „Ne mutasd újra” és
 * „Rendben”. A [position] / [total] számláló több figyelmeztetésnél mutatja, hányadiknál tart a sor.
 */
@Composable
fun LaunchNoticeDialog(
    notice: LaunchNotice,
    position: Int,
    total: Int,
    canHide: Boolean,
    onOk: () -> Unit,
    onHide: () -> Unit,
) {
    val context = LocalContext.current
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.86f
    val icon: ImageVector
    val iconBackground: Color
    val iconTint: Color
    val kicker: String
    val accent: Color
    val title: String
    val body: String
    val details: List<String>
    val url: String?
    var linkText = "Teljes hír megnyitása"
    when (notice) {
        LaunchNotice.LiveCoverage -> {
            icon = Icons.Default.PriorityHigh
            iconBackground = LiveCoverageYellow
            iconTint = OnLiveCoverageYellow
            kicker = "ÉLŐ KÖVETÉS"
            accent = liveCoverageAccent()
            title = LIVE_COVERAGE_TITLE
            body = LIVE_COVERAGE_BODY
            details = emptyList()
            url = OBU_ARTICLE_URL
            linkText = "A város cikke az OBU-król"
        }
        LaunchNotice.Unofficial -> {
            icon = Icons.Default.Info
            accent = MaterialTheme.colorScheme.primary
            iconBackground = accent
            iconTint = MaterialTheme.colorScheme.onPrimary
            kicker = "FIGYELEM"
            title = UNOFFICIAL_TITLE
            body = UNOFFICIAL_BODY
            details = emptyList()
            url = "mailto:$CONTACT_EMAIL"
            linkText = "E-mail írása"
        }
        is LaunchNotice.TrafficChange -> {
            val alert = notice.alert
            accent = alertAccent()
            icon = Icons.Default.Warning
            iconBackground = accent
            iconTint = MaterialTheme.colorScheme.surfaceContainerHigh
            kicker = "FORGALMI VÁLTOZÁS"
            title = alert.header
            body = alert.description
            details = listOfNotNull(
                alert.lines.takeIf { it.isNotEmpty() }
                    ?.sortedWith(LineNameComparator)
                    ?.joinToString(", ", prefix = "Érintett vonalak: "),
                alert.period(),
            )
            url = alert.url?.takeIf { it.startsWith("http") }
        }
    }
    val html = remember(body) {
        // A Compose HTML-átalakítója nem tesz üres sort a bekezdések közé; a hosszabb szöveg így tagoltabb.
        val text = AnnotatedString.fromHtml(body.replace(paragraphBreak, "</p><br>"))
        text.subSequence(0, text.text.trimEnd().length)
    }

    Dialog(
        onDismissRequest = onOk,
        // A háttérre koppintás ne zárja be véletlenül; a vissza gomb a „Rendben”-nel egyezik.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(max = maxHeight),
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 20.dp)) {
                Box(Modifier.fillMaxWidth().height(20.dp)) {
                    if (total > 1) {
                        Text(
                            "$position / $total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = iconBackground,
                    contentColor = iconTint,
                    modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    kicker,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    details.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(html, style = MaterialTheme.typography.bodyLarge)
                    if (url != null) {
                        TextButton(
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text(linkText, color = accent) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (canHide) {
                    OutlinedButton(onClick = onHide, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Ne mutasd újra")
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Button(onClick = onOk, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Rendben")
                }
            }
        }
    }
}
