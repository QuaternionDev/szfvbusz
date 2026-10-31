package hu.szfvbus.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.szfvbus.data.model.Alert
import hu.szfvbus.util.TimeUtil
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Forgalmi változások, amelyek a [line] vonalat (egy vonal rövid nevét) említik. */
fun List<Alert>.forLine(line: String?): List<Alert> =
    if (line == null) emptyList() else filter { a -> a.lines.any { it.equals(line, ignoreCase = true) } }

private val alertDate = DateTimeFormatter.ofPattern("yyyy.MM.dd. HH:mm")

/** "2026.10.26. 08:00 – 2026.10.31. 23:59", vagy null, ha nincs dátum. */
fun Alert.period(): String? {
    if (start == null && end == null) return null
    fun format(sec: Long) = alertDate.format(Instant.ofEpochSecond(sec).atZone(TimeUtil.ZONE))
    return listOfNotNull(start?.let(::format), end?.let(::format)).joinToString(" – ")
}

private data class BannerColors(val container: Color, val content: Color, val accent: Color)

@Composable
private fun bannerColors(): BannerColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        BannerColors(Color(0xFF3B2716), Color(0xFFFFE0B2), Color(0xFFFFA040))
    } else {
        BannerColors(Color(0xFFFFF3E0), Color(0xFF5D2A00), Color(0xFFE65100))
    }

/**
 * Figyelmeztetés, ha a járat élő adata gyanúsan sokkal a menetrend előtt jár: a busz valószínűleg még
 * el sem indult erre a járatra (lásd [hu.szfvbus.data.model.EARLY_LOGIN_SEC]).
 */
@Composable
fun EarlyLoginWarning(modifier: Modifier = Modifier) {
    val colors = bannerColors()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "LEHET, HOGY HIBÁS AZ ÉLŐ ADAT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
                Text(
                    "Ez a busz jóval a menetrend előtt jár, ezért valószínűleg még el sem indult erre a járatra: " +
                        "a sofőr korábban jelentkezett be, a busz pedig még az előző útját teszi meg, vagy a " +
                        "végállomáson áll. Az élő idők és a busz helyzete most félrevezetők, a menetrend szerinti " +
                        "időkkel számolj.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** A forgalmi változások borostyán kiemelőszíne. */
@Composable
fun alertAccent(): Color = bannerColors().accent

/**
 * Borostyán sáv egy vonal forgalmi változásairól. Összecsukva az első változást nevezi meg; koppintásra
 * kinyílnak a részletek. A vonal megállói a menetrend szerintiek maradnak; a sáv írja le az eltérést.
 */
@Composable
fun AlertBanner(alerts: List<Alert>, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val colors = bannerColors()
    val context = LocalContext.current
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (alerts.size == 1) "FORGALMI VÁLTOZÁS" else "${alerts.size} FORGALMI VÁLTOZÁS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.accent,
                    )
                    Text(
                        alerts.first().header,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Kevesebb" else "Részletek",
                    tint = colors.accent,
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(top = 8.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    alerts.forEachIndexed { index, alert ->
                        if (index > 0) {
                            HorizontalDivider(color = colors.accent.copy(alpha = 0.3f))
                            Text(alert.header, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        alert.period()?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.content.copy(alpha = 0.75f))
                        }
                        if (alert.description.isNotEmpty()) {
                            Text(AnnotatedString.fromHtml(alert.description), style = MaterialTheme.typography.bodySmall)
                        }
                        alert.url?.takeIf { it.startsWith("http") }?.let { url ->
                            TextButton(
                                onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) { Text("Teljes hír megnyitása", color = colors.accent) }
                        }
                    }
                    Text(
                        "A megállók a menetrend szerint látszanak; az eltéréseket a fenti hír írja le.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.content.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** Kis „Forgalmi változás” jelölés az érintett vonalak listasoraiban. */
@Composable
fun AlertTag(modifier: Modifier = Modifier) {
    val colors = bannerColors()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            "Forgalmi változás",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
        )
    }
}

/** Sáv az érvényben lévő változások számával; a forgalmi hírekhez vezet. */
@Composable
fun AlertSummaryBanner(alerts: List<Alert>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    val colors = bannerColors()
    val lines = alerts.flatMap { it.lines }.distinct()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${alerts.size} forgalmi változás",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (lines.isNotEmpty()) {
                    Text(
                        "Érintett vonalak: ${lines.sortedWith(hu.szfvbus.util.LineNameComparator).joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.accent)
        }
    }
}
