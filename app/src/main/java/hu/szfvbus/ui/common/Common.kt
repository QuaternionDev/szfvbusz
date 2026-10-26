package hu.szfvbus.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import hu.szfvbus.AppContainer
import hu.szfvbus.SzfvBusApp
import hu.szfvbus.data.DevTools
import hu.szfvbus.data.LineColors
import hu.szfvbus.data.model.Departure
import hu.szfvbus.ui.theme.LocalTransitColors
import hu.szfvbus.util.TimeUtil
import hu.szfvbus.util.platformText
import kotlinx.coroutines.delay

/** Egy aszinkron betöltés eredménye, ahogy egy képernyő mutatja. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Success<T>(val data: T) : Load<T>
    data class Error(val message: String) : Load<Nothing>
}

fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Ismeretlen hiba"

inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { create((this[APPLICATION_KEY] as SzfvBusApp).container) }
}

/** Az aktuális idő epoch másodpercben, rendszeresen frissítve, hogy a visszaszámlálások pontosak maradjanak. */
@Composable
fun rememberNow(intervalMs: Long = 10_000): Long {
    var now by remember { mutableLongStateOf(TimeUtil.nowSec()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            now = TimeUtil.nowSec()
            delay(intervalMs)
        }
    }
    return now
}

fun lineColorOf(line: String, isLocal: Boolean): Color = Color(LineColors.forLine(line, isLocal))

@Composable
fun LineBadge(
    line: String,
    color: Color,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    Box(
        modifier
            .defaultMinSize(minWidth = if (small) 30.dp else 40.dp)
            .clip(RoundedCornerShape(if (small) 6.dp else 8.dp))
            .background(color)
            .padding(horizontal = if (small) 5.dp else 7.dp, vertical = if (small) 2.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            line,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (small) 12.sp else 15.sp,
            maxLines = 1,
        )
    }
}

/** Lüktető pötty az élő GPS-adatból származó időknél. */
@Composable
fun RealtimeDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "rt")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "rtAlpha",
    )
    Box(
        modifier
            .size(7.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(LocalTransitColors.current.realtime),
    )
}

/** A „Felszállok” gomb az ennyi másodpercen belül érkező buszoknál jelenik meg. */
const val BOARD_WINDOW_SEC = 20 * 60

fun Departure.canBoard(now: Long) =
    expected - now in -60..BOARD_WINDOW_SEC || DevTools.current.boardAnyDeparture && expected >= now - 60

@Composable
fun DepartureRow(
    departure: Departure,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showStopHint: String? = null,
    onBoard: (() -> Unit)? = null,
    /** "12. kocsiállás", ha a busz egy állomás számozott kocsiállásáról indul. */
    platform: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBadge(departure.line, lineColorOf(departure.line, departure.isLocal))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                departure.headsign,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showStopHint != null) {
                Text(
                    showStopHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (platform != null) PlatformPill(platform, Modifier.padding(top = 4.dp))
            if (onBoard != null && departure.canBoard(now)) {
                BoardButton(onBoard, Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        DepartureTime(departure, now)
    }
}

@Composable
fun BoardButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Felszállok", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Az egy irányba induló járatok csoportjának címe, állomáson a kocsiállásaival.
 * [onClick] esetén ezzel az iránnyal felül nyitja meg a megállót; [onFavorite] esetén csillag is
 * van rajta, amellyel az irány kedvencnek jelölhető.
 */
@Composable
fun DirectionHeader(
    label: String,
    platforms: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        platformText(platforms)?.let {
            Spacer(Modifier.width(8.dp))
            PlatformPill(it)
        }
        if (onFavorite != null) {
            Spacer(Modifier.width(4.dp))
            // Kisebb, mint egy IconButton, hogy a fejléc ne legyen tőle magasabb.
            Icon(
                if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (isFavorite) "Irány eltávolítása a kedvencek közül" else "Irány hozzáadása a kedvencekhez",
                tint = if (isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onFavorite)
                    .padding(6.dp)
                    .size(22.dp),
            )
        }
    }
}

/** Egy állomás kocsiállása, ahonnan a busz indul, pl. "12. kocsiállás". */
@Composable
fun PlatformPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Jelvény azoknál a vonalaknál, amelyeken most élőben látszik busz. */
@Composable
fun LiveBadge(count: Int, modifier: Modifier = Modifier) {
    val color = LocalTransitColors.current.realtime
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val transition = rememberInfiniteTransition(label = "live")
            val scale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 2.2f,
                animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
                label = "liveScale",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = (2.2f - scale) / 1.2f * 0.6f
                    }
                    .clip(CircleShape)
                    .background(color),
            )
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "ÉLŐ",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("$count", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun DepartureTime(departure: Departure, now: Long, modifier: Modifier = Modifier) {
    val colors = LocalTransitColors.current
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (departure.isRealtime) {
                RealtimeDot()
                Spacer(Modifier.width(5.dp))
            }
            Text(
                TimeUtil.relative(departure.expected, now),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (departure.isRealtime) colors.realtime else MaterialTheme.colorScheme.onSurface,
            )
        }
        val minutesLate = departure.delaySec / 60
        Row {
            if (departure.isRealtime && minutesLate != 0) {
                Text(
                    TimeUtil.clock(departure.scheduled),
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = TextDecoration.LineThrough,
                    color = colors.muted,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                TimeUtil.clock(departure.expected),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    !departure.isRealtime -> colors.muted
                    minutesLate > 0 -> colors.late
                    minutesLate < 0 -> colors.early
                    else -> colors.realtime
                },
            )
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Újrapróbálás") }
    }
}

@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.DirectionsBus,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun FullScreenMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
