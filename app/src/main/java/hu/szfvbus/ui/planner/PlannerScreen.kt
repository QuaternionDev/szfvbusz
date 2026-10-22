package hu.szfvbus.ui.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import hu.szfvbus.AppContainer
import hu.szfvbus.data.model.GeoPoint
import hu.szfvbus.data.model.Itinerary
import hu.szfvbus.data.model.Place
import hu.szfvbus.ui.common.EmptyBox
import hu.szfvbus.ui.common.ErrorBox
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.LoadingBox
import hu.szfvbus.ui.common.RealtimeDot
import hu.szfvbus.ui.common.userMessage
import hu.szfvbus.ui.map.LegChips
import hu.szfvbus.ui.theme.LocalTransitColors
import hu.szfvbus.util.TimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Egy tervezett út kiinduló- vagy célpontja. */
sealed interface Endpoint {
    data object MyLocation : Endpoint
    data class Fixed(val place: Place) : Endpoint
}

enum class TimeMode { NOW, DEPART, ARRIVE }

/** Mire optimalizál a tervező. */
enum class RoutePreference(val label: String) {
    LEAST_WALK("Legkevesebb séta"),
    FASTEST("Leggyorsabb"),
}

data class PlannerState(
    val from: Endpoint? = null,
    val to: Endpoint? = null,
    val timeMode: TimeMode = TimeMode.NOW,
    val preference: RoutePreference = RoutePreference.LEAST_WALK,
    val dateTime: LocalDateTime = TimeUtil.nowDateTime(),
    val results: Load<List<Itinerary>>? = null,
)

class PlannerViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.transit
    private val location = container.location
    private val prefs = container.prefs

    private val _state = MutableStateFlow(
        PlannerState(from = if (location.hasPermission()) Endpoint.MyLocation else null),
    )
    val state = _state.asStateFlow()

    private var job: Job? = null

    fun setFrom(endpoint: Endpoint) {
        _state.value = _state.value.copy(from = endpoint, results = null)
        planIfReady()
    }

    fun setTo(endpoint: Endpoint) {
        _state.value = _state.value.copy(to = endpoint, results = null)
        planIfReady()
    }

    fun swap() {
        val s = _state.value
        _state.value = s.copy(from = s.to, to = s.from, results = null)
        planIfReady()
    }

    fun setTimeMode(mode: TimeMode) {
        val s = _state.value
        _state.value = s.copy(
            timeMode = mode,
            dateTime = if (mode == TimeMode.NOW) TimeUtil.nowDateTime() else s.dateTime,
        )
        planIfReady()
    }

    fun setPreference(preference: RoutePreference) {
        if (_state.value.preference == preference) return
        _state.value = _state.value.copy(preference = preference)
        planIfReady()
    }

    fun setDateTime(dateTime: LocalDateTime) {
        val s = _state.value
        _state.value = s.copy(
            dateTime = dateTime,
            timeMode = if (s.timeMode == TimeMode.NOW) TimeMode.DEPART else s.timeMode,
        )
        planIfReady()
    }

    private fun planIfReady() {
        val s = _state.value
        if (s.from != null && s.to != null) plan()
    }

    fun plan() {
        val s = _state.value
        val from = s.from ?: return
        val to = s.to ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(results = Load.Loading)
            val result = try {
                val fromPlace = resolve(from) ?: throw IllegalStateException("Nem sikerült meghatározni a helyzetedet")
                val toPlace = resolve(to) ?: throw IllegalStateException("Nem sikerült meghatározni a helyzetedet")
                val dateTime = if (s.timeMode == TimeMode.NOW) TimeUtil.nowDateTime() else s.dateTime
                val list = repo.plan(
                    from = fromPlace,
                    to = toPlace,
                    dateTime = dateTime,
                    arriveBy = s.timeMode == TimeMode.ARRIVE,
                    includeIntercity = prefs.prefs.first().showIntercity,
                    leastWalking = s.preference == RoutePreference.LEAST_WALK,
                )
                Load.Success(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.userMessage())
            }
            _state.value = _state.value.copy(results = result)
        }
    }

    private suspend fun resolve(endpoint: Endpoint): Place? = when (endpoint) {
        is Endpoint.Fixed -> endpoint.place
        Endpoint.MyLocation -> {
            val p: GeoPoint? = location.lastKnown() ?: withTimeoutOrNull(12_000) { location.updates().first() }
            p?.let { Place("Saját helyzet", it.lat, it.lon) }
        }
    }
}

private val dateTimeFormat = DateTimeFormatter.ofPattern("MMM d. HH:mm", Locale.forLanguageTag("hu"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    vm: PlannerViewModel,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onItinerary: (Itinerary) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Útvonaltervezés") }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            EndpointField(Icons.Default.TripOrigin, Color(0xFF2E7D32), "Honnan?", state.from, onPickFrom)
                            HorizontalDivider(Modifier.padding(start = 52.dp))
                            EndpointField(Icons.Default.Place, Color(0xFFC62828), "Hová?", state.to, onPickTo)
                        }
                        IconButton(onClick = vm::swap) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Csere")
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.timeMode == TimeMode.NOW,
                        onClick = { vm.setTimeMode(TimeMode.NOW) },
                        label = { Text("Most") },
                    )
                    FilterChip(
                        selected = state.timeMode == TimeMode.DEPART,
                        onClick = { vm.setTimeMode(TimeMode.DEPART) },
                        label = { Text("Indulás") },
                    )
                    FilterChip(
                        selected = state.timeMode == TimeMode.ARRIVE,
                        onClick = { vm.setTimeMode(TimeMode.ARRIVE) },
                        label = { Text("Érkezés") },
                    )
                }
                if (state.timeMode != TimeMode.NOW) {
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        TextButton(onClick = { showDate = true }) {
                            Text(dateTimeFormat.format(state.dateTime).substringBeforeLast(' '))
                        }
                        TextButton(onClick = { showTime = true }) {
                            Text(state.dateTime.toLocalTime().toString().take(5))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoutePreference.entries.forEach { pref ->
                        FilterChip(
                            selected = state.preference == pref,
                            onClick = { vm.setPreference(pref) },
                            label = { Text(pref.label) },
                            leadingIcon = {
                                Icon(
                                    if (pref == RoutePreference.LEAST_WALK) {
                                        Icons.AutoMirrored.Filled.DirectionsWalk
                                    } else {
                                        Icons.Default.Speed
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
                Button(
                    onClick = vm::plan,
                    enabled = state.from != null && state.to != null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text("Tervezés") }
            }
            when (val r = state.results) {
                null -> item {
                    EmptyBox(
                        "Add meg, honnan hová utaznál. Tipp: a térképen hosszan nyomva is kijelölhetsz egy pontot.",
                    )
                }
                Load.Loading -> item { LoadingBox() }
                is Load.Error -> item { ErrorBox(r.message, vm::plan) }
                is Load.Success -> {
                    if (r.data.isEmpty()) item { EmptyBox("Nem található útvonal ebben az időpontban.") }
                    items(r.data) { itinerary -> ItineraryCard(itinerary, onClick = { onItinerary(itinerary) }) }
                }
            }
        }
    }

    if (showDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.dateTime.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.setDateTime(LocalDateTime.of(date, state.dateTime.toLocalTime()))
                    }
                    showDate = false
                }) { Text("Kiválasztás") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Mégse") } },
        ) { DatePicker(state = pickerState) }
    }
    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = state.dateTime.hour,
            initialMinute = state.dateTime.minute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { showTime = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = timeState)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showTime = false }) { Text("Mégse") }
                        TextButton(onClick = {
                            vm.setDateTime(
                                LocalDateTime.of(state.dateTime.toLocalDate(), LocalTime.of(timeState.hour, timeState.minute)),
                            )
                            showTime = false
                        }) { Text("Kiválasztás") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointField(icon: ImageVector, tint: Color, hint: String, endpoint: Endpoint?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (endpoint == Endpoint.MyLocation) Icons.Default.MyLocation else icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            when (endpoint) {
                null -> hint
                Endpoint.MyLocation -> "Saját helyzet"
                is Endpoint.Fixed -> endpoint.place.name
            },
            color = if (endpoint == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ItineraryCard(itinerary: Itinerary, onClick: () -> Unit) {
    val now = TimeUtil.nowSec()
    val firstTransit = itinerary.transitLegs.firstOrNull()
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TimeUtil.clock(itinerary.start)} – ${TimeUtil.clock(itinerary.end)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(TimeUtil.durationText(itinerary.durationSec), style = MaterialTheme.typography.titleMedium)
            }
            LegChips(itinerary)
            if (firstTransit != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (firstTransit.isRealtime) {
                        RealtimeDot()
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        buildString {
                            append("${firstTransit.line} indul ${TimeUtil.clock(firstTransit.start)}-kor")
                            val minutes = TimeUtil.minutesUntil(firstTransit.start, now)
                            when {
                                minutes <= 0 -> append(" (most)")
                                minutes < 60 -> append(" ($minutes perc múlva)")
                            }
                            append(", ${firstTransit.from.nameWithPlatform}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (firstTransit.isRealtime) LocalTransitColors.current.realtime else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "${itinerary.walkDistanceM.roundToInt()} m gyaloglás",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
