package hu.szfvbus.ui.map

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.szfvbus.data.model.Alert
import hu.szfvbus.data.model.RidePhase
import hu.szfvbus.data.model.RideStatus
import hu.szfvbus.data.model.TripDetails
import hu.szfvbus.data.model.Vehicle
import hu.szfvbus.ui.common.AlertBanner
import hu.szfvbus.ui.common.LineBadge
import hu.szfvbus.ui.common.forLine
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.LoadingBox
import hu.szfvbus.ui.common.ErrorBox
import hu.szfvbus.ui.common.lineColorOf
import hu.szfvbus.util.TimeUtil
import kotlin.math.roundToInt

/** Minden, amire az utazási nézeteknek szüksége van, a járatból, az élő buszból és az órából számolva. */
data class RideInfo(
    val trip: TripDetails,
    val vehicle: Vehicle?,
    val boardIndex: Int,
    /** A megálló, amelyet az utas leszállásra választott, ha van ilyen. */
    val alightIndex: Int?,
    val status: RideStatus,
)

private val AlightSoonColor = Color(0xFFE65100)
private val ArrivedColor = Color(0xFF2E7D32)

/** A navigációs stílusú kártya a térkép tetején utazás közben. */
@Composable
fun RideBanner(info: RideInfo, now: Long, modifier: Modifier = Modifier) {
    val trip = info.trip
    val status = info.status
    val board = trip.stops[info.boardIndex]
    val target = trip.stops[status.targetIndex]
    val targetLabel = if (info.alightIndex != null) "Leszállás" else "Végállomás"
    val next = status.nextIndex?.let { trip.stops.getOrNull(it) }

    val icon: ImageVector
    val caption: String
    val title: String
    var eta: Long? = null
    var footer: String? = null
    when (status.phase) {
        RidePhase.WAITING -> {
            icon = Icons.Default.DirectionsBus
            caption = "${trip.line} → ${trip.headsign}"
            title = "Felszállás: ${board.name}"
            eta = board.expectedDeparture
            val delay = trip.currentDelay(now)
            footer = buildString {
                board.platformLabel?.let { append("$it · ") }
                append(if (delay != null) TimeUtil.delayText(delay) else "menetrend szerint")
                if (info.vehicle == null) append(" · a busz nem látszik élőben")
                if (info.alightIndex != null) append("\nLeszállás: ${target.name} · ${TimeUtil.clock(target.expectedArrival)}")
            }
        }
        RidePhase.RIDING -> {
            icon = Icons.Default.Navigation
            caption = "Következő megálló"
            title = next?.name.orEmpty()
            eta = next?.expectedArrival
            footer = "$targetLabel: ${target.name} · ${TimeUtil.clock(target.expectedArrival)} · " +
                "még ${status.stopsLeft} megálló"
        }
        RidePhase.ALIGHT_NEXT -> {
            icon = Icons.Default.NotificationsActive
            caption = if (info.alightIndex != null) "A következő megállónál szállj le!" else "Következő: végállomás"
            title = target.name
            eta = target.expectedArrival
        }
        RidePhase.ARRIVED -> {
            icon = Icons.Default.CheckCircle
            caption = "Megérkeztél"
            title = target.name
            footer = "Ha leszálltál, nyomd meg a Leszállok gombot."
        }
    }
    val background by animateColorAsState(
        when (status.phase) {
            RidePhase.ALIGHT_NEXT -> AlightSoonColor
            RidePhase.ARRIVED -> ArrivedColor
            else -> lineColorOf(trip.line, trip.isLocal)
        },
        label = "rideBanner",
    )

    Surface(
        color = background,
        contentColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 8.dp,
        modifier = modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (eta != null) {
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            TimeUtil.relative(eta, now),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        // A távoli időpontok már óra:percként látszanak fent.
                        if (TimeUtil.relative(eta, now) != TimeUtil.clock(eta)) {
                            Text(
                                TimeUtil.clock(eta),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.88f),
                            )
                        }
                    }
                }
            }
            if (footer != null) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.3f))
                Text(footer, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Az utazási panel behúzott sávjának magassága, a fogantyúval együtt; a navigációs sáv még hozzájön. */
val RideSheetPeek get() = RIDE_HANDLE_HEIGHT + RIDE_BAR_HEIGHT

private val RIDE_HANDLE_HEIGHT = 20.dp
private val RIDE_BAR_HEIGHT = 84.dp
private val RIDE_BUTTON_SLOT = 76.dp

/** Kisebb fogantyú az utazási panelhez, hogy a behúzott sáv alacsony maradjon. */
@Composable
fun RideDragHandle() {
    Box(Modifier.fillMaxWidth().height(RIDE_HANDLE_HEIGHT), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

/**
 * Az alsó panel utazás közben. Behúzva csak a navigációs sáv látszik: középen az érkezés a célhoz,
 * balra a busz követése, jobbra a „Leszállok”. Felhúzva alatta a járat adatai és az előttünk lévő megállók.
 */
@Composable
fun RidePanel(
    trip: Load<TripDetails>,
    info: RideInfo?,
    alerts: List<Alert>,
    follow: Boolean,
    canFollow: Boolean,
    now: Long,
    onFollow: () -> Unit,
    onAlightStop: (stopId: String) -> Unit,
    onEnd: () -> Unit,
    onRetry: () -> Unit,
    onToggleSheet: () -> Unit,
) {
    val listState = rememberLazyListState()
    val nextIndex = info?.status?.nextIndex
    LaunchedEffect(info?.trip?.tripId, nextIndex) {
        if (info != null && nextIndex != null) {
            // A sorok a felszállási megállónál kezdődnek; az utoljára elhagyott megálló maradjon látható.
            listState.animateScrollToItem((nextIndex - info.boardIndex - 1).coerceAtLeast(0))
        }
    }
    Column(Modifier.fillMaxSize()) {
        RideBar(info, now, follow, canFollow, onFollow = onFollow, onEnd = onEnd, onToggle = onToggleSheet)
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            PanelLayout(
                state = listState,
                header = {
                    val details = info?.trip ?: (trip as? Load.Success)?.data
                    val vehicle = info?.vehicle
                    PanelHeader(
                        title = details?.headsign?.let { "→ $it" } ?: "Utazás",
                        subtitle = buildList {
                            if (vehicle != null && vehicle.label.isNotEmpty()) add(vehicle.label)
                            val delay = details?.currentDelay(now)
                            if (delay != null) add(TimeUtil.delayText(delay)) else if (details != null) add("menetrend szerint")
                            val kmh = vehicle?.speedMps?.let { (it * 3.6).roundToInt() }
                            if (kmh != null && kmh > 0) add("$kmh km/h")
                        }.joinToString(" · ").ifEmpty { null },
                        leading = details?.let { { LineBadge(it.line, lineColorOf(it.line, it.isLocal)) } },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        if (info?.alightIndex == null) {
                            "Koppints arra a megállóra, ahol leszállsz: szólunk, mielőtt odaérsz."
                        } else {
                            "A kijelölt megállóra újra koppintva visszaállíthatod a végállomásra."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                    AlertBanner(
                        alerts.forLine(details?.takeIf { it.isLocal }?.line),
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                    HorizontalDivider()
                },
            ) {
                when {
                    trip is Load.Error -> item { ErrorBox(trip.message, onRetry) }
                    info == null -> item { LoadingBox() }
                    else -> {
                        val d = info.trip
                        val color = lineColorOf(d.line, d.isLocal)
                        val next = info.status.nextIndex
                        val stops = d.stops.drop(info.boardIndex)
                        itemsIndexed(stops, key = { i, s -> "${i + info.boardIndex}-${s.stopId}" }) { i, stop ->
                            val index = i + info.boardIndex
                            val last = index == d.stops.lastIndex
                            TimelineRow(
                                name = stop.name,
                                detail = stop.platformLabel,
                                time = if (last) stop.expectedArrival else stop.expectedDeparture,
                                scheduled = if (last) stop.scheduledArrival else stop.scheduledDeparture,
                                realtime = stop.isRealtime,
                                color = color,
                                passed = next == null || index < next,
                                current = index == next,
                                first = i == 0,
                                last = last,
                                onClick = { if (index > info.boardIndex) onAlightStop(stop.stopId) },
                                tag = when (index) {
                                    info.boardIndex -> { { StopTag("Felszállás", Color(MapViewModel.START_COLOR)) } }
                                    info.status.targetIndex -> {
                                        { StopTag(if (info.alightIndex != null) "Leszállás" else "Végállomás", Color(MapViewModel.END_COLOR)) }
                                    }
                                    else -> null
                                },
                            )
                        }
                        // Az utolsó megálló ne csússzon a rendszer navigációs sávja alá.
                        item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
                    }
                }
            }
        }
    }
}

/** A behúzott panel sávja, a navigációs alkalmazások alsó sávjának mintájára. */
@Composable
private fun RideBar(
    info: RideInfo?,
    now: Long,
    follow: Boolean,
    canFollow: Boolean,
    onFollow: () -> Unit,
    onEnd: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(RIDE_BAR_HEIGHT).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RIDE_BUTTON_SLOT), contentAlignment = Alignment.Center) {
            // Élőben nem látszó buszt, vagy helyzet nélkül az utast nincs mit követni.
            if (canFollow) {
                RoundAction(
                    icon = if (follow) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                    label = "Követés",
                    container = if (follow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = if (follow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onFollow,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onToggle),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (info == null) {
                Text("Betöltés…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val status = info.status
                val arrival = info.trip.stops[status.targetIndex].expectedArrival
                Text(
                    TimeUtil.clock(arrival),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    if (status.phase == RidePhase.ARRIVED) {
                        "Megérkeztél"
                    } else {
                        val left = arrival - now
                        val time = if (left > 0) TimeUtil.durationText(left) else "most"
                        "$time · ${status.stopsLeft} megálló"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.width(RIDE_BUTTON_SLOT), contentAlignment = Alignment.Center) {
            RoundAction(
                icon = Icons.AutoMirrored.Filled.Logout,
                label = "Leszállok",
                container = MaterialTheme.colorScheme.error,
                content = MaterialTheme.colorScheme.onError,
                onClick = onEnd,
            )
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, container: Color, content: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun StopTag(text: String, color: Color) {
    Surface(
        color = color,
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** Jól érezhető rezgés a leszállási megálló előtt. */
@Suppress("DEPRECATION")
internal fun vibrateAlightAlert(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    } ?: return
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350, 150, 600), -1))
}
