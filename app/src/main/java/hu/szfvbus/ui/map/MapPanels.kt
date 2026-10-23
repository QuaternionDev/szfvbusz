package hu.szfvbus.ui.map

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.szfvbus.data.LineColors
import hu.szfvbus.data.model.Departure
import hu.szfvbus.data.model.Itinerary
import hu.szfvbus.data.model.Leg
import hu.szfvbus.data.model.LegMode
import hu.szfvbus.data.model.Network
import hu.szfvbus.data.model.RouteDetails
import hu.szfvbus.data.model.Stop
import hu.szfvbus.data.model.StopGroup
import hu.szfvbus.data.model.TripDetails
import hu.szfvbus.data.model.Vehicle
import hu.szfvbus.data.model.directionGroups
import hu.szfvbus.ui.common.DepartureRow
import hu.szfvbus.ui.common.DirectionHeader
import hu.szfvbus.ui.common.EmptyBox
import hu.szfvbus.ui.common.ErrorBox
import hu.szfvbus.ui.common.LineBadge
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.LoadingBox
import hu.szfvbus.ui.common.RealtimeDot
import hu.szfvbus.ui.common.lineColorOf
import hu.szfvbus.ui.theme.LocalTransitColors
import hu.szfvbus.util.LineNameComparator
import hu.szfvbus.data.model.Alert
import hu.szfvbus.ui.common.AlertBanner
import hu.szfvbus.ui.common.forLine
import hu.szfvbus.util.TimeUtil
import java.time.LocalTime
import kotlin.math.roundToInt

@Composable
internal fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (title.length > 26) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
        if (onClose != null) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Bezárás") }
        }
    }
}

/** Rögzített fejléc egy görgethető lista fölött, hogy a panel címe és gombjai elérhetők maradjanak. */
@Composable
internal fun PanelLayout(
    header: @Composable () -> Unit,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        header()
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = state, content = content)
    }
}

@Composable
private fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = if (isFavorite) "Eltávolítás a kedvencek közül" else "Kedvencekhez adás",
            tint = if (isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun <T> LazyListScope.loadState(load: Load<T>, onRetry: () -> Unit, content: LazyListScope.(T) -> Unit) {
    when (load) {
        Load.Loading -> item { LoadingBox() }
        is Load.Error -> item { ErrorBox(load.message, onRetry) }
        is Load.Success -> content(load.data)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewPanel(
    network: Load<Network>,
    vehicles: List<Vehicle>,
    updatedAt: Long?,
    error: String?,
    onLineClick: (routeId: String) -> Unit,
    onRetryNetwork: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            val local = vehicles.count { it.isLocal }
            val now = TimeUtil.nowDateTime().toLocalTime()
            val night = now >= LocalTime.of(1, 0) && now < LocalTime.of(4, 30)
            PanelHeader(
                title = "Székesfehérvár élőben",
                subtitle = when {
                    error != null && updatedAt == null -> "Nem sikerült lekérni a járműveket"
                    updatedAt == null -> "Járművek betöltése…"
                    local == 0 && night -> "Éjszaka nem járnak a helyi buszok, jó éjszakát!"
                    local == 0 -> "Most egy helyi busz sem látszik élőben a térképen"
                    else -> "$local helyi busz látszik élőben · frissítve ${TimeUtil.clock(updatedAt)}"
                },
            )
        }
        when (network) {
            Load.Loading -> item { LoadingBox() }
            is Load.Error -> item { ErrorBox(network.message, onRetryNetwork) }
            is Load.Success -> {
                val net = network.data
                val running = vehicles.filter { it.isLocal }.mapNotNull { it.routeId }.toSet()
                val lines = net.lines.filter { it.id in running }
                item {
                    Text(
                        if (lines.isEmpty()) "Most egy helyi busz sem látszik élőben a térképen." else "Élőben látható vonalak",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        lines.forEach { line ->
                            LineBadge(
                                line.shortName,
                                Color(line.color),
                                Modifier.clickable { onLineClick(line.id) },
                            )
                        }
                    }
                }
                item {
                    Text(
                        "Koppints egy buszra a menetéhez, egy megállóra az indulásokhoz. " +
                            "Hosszan nyomva a térképen útvonalat tervezhetsz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun StopPanel(
    group: StopGroup?,
    stops: Map<String, Stop>,
    /** A peron, amelyre a felhasználó koppintott: az iránya kerül előre. */
    firstStopId: String?,
    departures: Load<List<Departure>>,
    isFavorite: Boolean,
    now: Long,
    onFavorite: () -> Unit,
    onTimetable: () -> Unit,
    onDepartureClick: (Departure) -> Unit,
    onBoard: (Departure) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    // Az irányok, amelyeknél az utas az első néhány indulásnál többet nyitott ki.
    var expanded by remember(group?.name) { mutableStateOf(emptySet<String>()) }
    // A lista a régi első irányt tartaná a képen; a koppintott irány van felül.
    val listState = rememberLazyListState()
    LaunchedEffect(group?.name, firstStopId) { listState.scrollToItem(0) }
    PanelLayout(
        state = listState,
        header = {
            PanelHeader(
                title = group?.name ?: "Megálló",
                subtitle = group?.lines?.takeIf { it.isNotEmpty() }
                    ?.sortedWith(LineNameComparator)?.joinToString(", ", prefix = "Vonalak: "),
                actions = {
                    FavoriteButton(isFavorite, onFavorite)
                    IconButton(onClick = onTimetable) {
                        Icon(Icons.Default.Schedule, contentDescription = "Menetrend")
                    }
                },
                onClose = onClose,
            )
            HorizontalDivider()
        },
    ) {
        loadState(departures, onRetry) { list ->
            if (list.isEmpty()) {
                item { EmptyBox("A következő órákban nincs indulás erről a megállóról.") }
            } else {
                for (direction in directionGroups(list, stops, firstStopId)) {
                    item(key = "h-${direction.label}") { DirectionHeader(direction.label, direction.platforms) }
                    if (direction.departures.isEmpty()) {
                        item(key = "e-${direction.label}") {
                            Text(
                                "Erre a következő órákban nem indul járat.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    val open = direction.label in expanded
                    val shown = if (open) direction.departures else direction.departures.take(DEPARTURES_PER_DIRECTION)
                    items(shown, key = { "${it.tripId}|${it.stopId}|${it.scheduled}" }) { dep ->
                        DepartureRow(
                            dep,
                            now,
                            onClick = { onDepartureClick(dep) },
                            onBoard = { onBoard(dep) },
                            // Ha egy irányba több kocsiállásról is indul busz, mindegyik mutatja a sajátját.
                            platform = stops[dep.stopId]?.platformLabel?.takeIf { direction.platforms.size > 1 },
                        )
                    }
                    val hidden = direction.departures.size - DEPARTURES_PER_DIRECTION
                    if (hidden > 0) {
                        item(key = "m-${direction.label}") {
                            TextButton(
                                onClick = { expanded = if (open) expanded - direction.label else expanded + direction.label },
                                modifier = Modifier.padding(start = 8.dp),
                            ) { Text(if (open) "Kevesebb" else "További $hidden indulás") }
                        }
                    }
                }
            }
        }
    }
}

private const val DEPARTURES_PER_DIRECTION = 5

@Composable
fun TripPanel(
    trip: Load<TripDetails>,
    vehicle: Vehicle?,
    alerts: List<Alert>,
    follow: Boolean,
    now: Long,
    onFollow: () -> Unit,
    onStopClick: (stopId: String) -> Unit,
    onLineClick: (routeId: String) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val details = (trip as? Load.Success)?.data
    val nextIndex = details?.stops?.indexOfFirst { it.expectedDeparture >= now }?.takeIf { it >= 0 }
    var scrolledFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(details?.tripId, nextIndex) {
        if (details != null && nextIndex != null && scrolledFor != details.tripId) {
            scrolledFor = details.tripId
            // Az utoljára elhagyott megálló látszódjon a következő fölött.
            listState.scrollToItem((nextIndex - 1).coerceAtLeast(0))
        }
    }
    PanelLayout(
        state = listState,
        header = {
            val line = details?.line ?: vehicle?.line ?: ""
            val isLocal = details?.isLocal ?: vehicle?.isLocal ?: true
            val delay = details?.currentDelay(now)
            PanelHeader(
                title = details?.headsign ?: vehicle?.headsign ?: "Járat",
                subtitle = buildList {
                    if (vehicle != null && vehicle.label.isNotEmpty()) add(vehicle.label)
                    if (delay != null) add(TimeUtil.delayText(delay)) else if (details != null) add("menetrend szerint")
                    val kmh = vehicle?.speedMps?.let { (it * 3.6).roundToInt() }
                    if (kmh != null && kmh > 0) add("$kmh km/h")
                }.joinToString(" · ").ifEmpty { null },
                leading = {
                    LineBadge(
                        line,
                        lineColorOf(line, isLocal),
                        Modifier.clickable(enabled = details != null && isLocal) {
                            details?.let { onLineClick(it.routeId) }
                        },
                    )
                },
                actions = {
                    if (vehicle != null) {
                        IconButton(onClick = onFollow) {
                            Icon(
                                if (follow) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                                contentDescription = if (follow) "Követés kikapcsolása" else "Jármű követése",
                                tint = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                onClose = onClose,
            )
            if (details != null && (details.wheelchair || vehicle == null)) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (details.wheelchair) {
                        Icon(
                            Icons.AutoMirrored.Filled.Accessible,
                            contentDescription = "Akadálymentes",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (vehicle == null) {
                        Text(
                            "Ez a busz most nem követhető élőben",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            AlertBanner(
                alerts.forLine(details?.takeIf { it.isLocal }?.line ?: vehicle?.takeIf { it.isLocal }?.line),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
        },
    ) {
        loadState(trip, onRetry) { d ->
            val color = lineColorOf(d.line, d.isLocal)
            itemsIndexed(d.stops, key = { i, s -> "$i-${s.stopId}" }) { index, stop ->
                val passed = nextIndex != null && index < nextIndex || nextIndex == null && stop.expectedDeparture < now
                TimelineRow(
                    name = stop.name,
                    detail = stop.platformLabel,
                    time = if (index == d.stops.lastIndex) stop.expectedArrival else stop.expectedDeparture,
                    scheduled = if (index == d.stops.lastIndex) stop.scheduledArrival else stop.scheduledDeparture,
                    realtime = stop.isRealtime,
                    color = color,
                    passed = passed,
                    current = index == nextIndex,
                    first = index == 0,
                    last = index == d.stops.lastIndex,
                    onClick = { onStopClick(stop.stopId) },
                )
            }
        }
    }
}

@Composable
internal fun TimelineRow(
    name: String,
    time: Long?,
    scheduled: Long?,
    realtime: Boolean,
    color: Color,
    passed: Boolean,
    current: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    tag: (@Composable () -> Unit)? = null,
    /** Második sor a név alatt, pl. a kocsiállás egy állomáson. */
    detail: String? = null,
) {
    val colors = LocalTransitColors.current
    val lineColor = if (passed) color.copy(alpha = 0.35f) else color
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(4.dp).weight(1f).background(if (first) Color.Transparent else lineColor))
                Box(Modifier.width(4.dp).weight(1f).background(if (last) Color.Transparent else lineColor))
            }
            Box(
                Modifier
                    .size(if (current) 16.dp else 12.dp)
                    .clip(CircleShape)
                    .background(if (current) color else MaterialTheme.colorScheme.surface),
            )
            Box(
                Modifier
                    .size(if (current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (current) Color.White else lineColor),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (passed) colors.muted else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
            tag?.invoke()
        }
        if (time != null) {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (realtime && !passed) {
                        RealtimeDot()
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        TimeUtil.clock(time),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            passed -> colors.muted
                            realtime -> colors.realtime
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (scheduled != null && realtime && (time - scheduled) / 60 != 0L) {
                    Text(
                        TimeUtil.clock(scheduled),
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = TextDecoration.LineThrough,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinePanel(
    route: Load<RouteDetails>,
    alerts: List<Alert>,
    direction: Int,
    vehiclesOnLine: Int,
    isFavorite: Boolean,
    onDirection: (Int) -> Unit,
    onFavorite: () -> Unit,
    onStopClick: (Stop) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    val details = (route as? Load.Success)?.data
    PanelLayout(
        header = {
            PanelHeader(
                title = details?.line?.longName?.ifEmpty { null } ?: "Vonal",
                // Nem minden busz küld helyzetet, ezért csak azt mondjuk, mi látszik, azt nem, mi közlekedik.
                subtitle = if (vehiclesOnLine > 0) {
                    "$vehiclesOnLine busz látszik élőben"
                } else {
                    "Most nem látszik élőben busz – nem minden busz követhető még"
                },
                leading = details?.let { { LineBadge(it.line.shortName, Color(it.line.color)) } },
                actions = { if (details != null) FavoriteButton(isFavorite, onFavorite) },
                onClose = onClose,
            )
        },
    ) {
        loadState(route, onRetry) { d ->
            val mains = d.mainPatterns
            val lineAlerts = (alerts.forLine(d.line.shortName) + d.alerts).distinctBy { it.id }
            if (lineAlerts.isNotEmpty()) {
                item {
                    AlertBanner(lineAlerts, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    mains.forEachIndexed { index, p ->
                        FilterChip(
                            selected = index == direction,
                            onClick = { onDirection(index) },
                            label = { Text("→ ${p.headsign}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            val pattern = mains.getOrNull(direction) ?: mains.firstOrNull()
            if (pattern == null) {
                item { EmptyBox("A vonalnak ma nincs menete.") }
            } else {
                item {
                    Text(
                        "Koppints egy megállóra a menetrendjéhez",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                val color = Color(d.line.color)
                itemsIndexed(pattern.stops, key = { i, s -> "$i-${s.id}" }) { index, stop ->
                    TimelineRow(
                        name = stop.name,
                        detail = stop.platformLabel,
                        time = null,
                        scheduled = null,
                        realtime = false,
                        color = color,
                        passed = false,
                        current = false,
                        first = index == 0,
                        last = index == pattern.stops.lastIndex,
                        onClick = { onStopClick(stop) },
                    )
                }
            }
        }
    }
}

@Composable
fun ItineraryPanel(
    itinerary: Itinerary,
    onLegClick: (Leg) -> Unit,
    onClose: () -> Unit,
) {
    PanelLayout(
        header = {
            PanelHeader(
                title = "${TimeUtil.clock(itinerary.start)} – ${TimeUtil.clock(itinerary.end)}",
                subtitle = "${TimeUtil.durationText(itinerary.durationSec)} · " +
                    "${itinerary.walkDistanceM.roundToInt()} m gyaloglás",
                onClose = onClose,
            )
            HorizontalDivider()
        },
    ) {
        items(itinerary.legs) { leg -> LegRow(leg, onClick = { onLegClick(leg) }) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(MapViewModel.END_COLOR)))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Érkezés: ${itinerary.legs.last().to.name} · ${TimeUtil.clock(itinerary.end)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
fun LegRow(leg: Leg, onClick: () -> Unit) {
    val colors = LocalTransitColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = leg.mode != LegMode.WALK, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.TopCenter) {
            if (leg.mode == LegMode.WALK) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "Gyalog")
            } else {
                LineBadge(leg.line.orEmpty(), lineColorOf(leg.line.orEmpty(), leg.mode == LegMode.BUS))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            if (leg.mode == LegMode.WALK) {
                Text(
                    "Séta ${TimeUtil.durationText(leg.end - leg.start)} (${leg.distanceM.roundToInt()} m)",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "${TimeUtil.clock(leg.start)} · ${leg.from.nameWithPlatform} → ${leg.to.nameWithPlatform}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    leg.headsign?.let { "→ $it" } ?: "Busz",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leg.isRealtime) {
                        RealtimeDot()
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "${TimeUtil.clock(leg.start)} ${leg.from.nameWithPlatform}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (leg.isRealtime) colors.realtime else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "${TimeUtil.clock(leg.end)} ${leg.to.nameWithPlatform}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val stops = leg.intermediateStops.size + 1
                Text(
                    "$stops megálló · ${TimeUtil.durationText(leg.end - leg.start)}" +
                        if (leg.isRealtime) " · ${TimeUtil.delayText(leg.departureDelaySec)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LegChips(itinerary: Itinerary, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itinerary.legs.forEachIndexed { index, leg ->
            if (index > 0) {
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (leg.mode == LegMode.WALK) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = "Gyalog",
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "${((leg.end - leg.start + 59) / 60)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                LineBadge(leg.line.orEmpty(), lineColorOf(leg.line.orEmpty(), leg.mode == LegMode.BUS), small = true)
            }
        }
    }
}

internal fun vehicleColors(vehicles: List<Vehicle>): Map<String, Int> =
    vehicles.associate { it.id to LineColors.forLine(it.line, it.isLocal) }
