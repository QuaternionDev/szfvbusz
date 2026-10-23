package hu.szfvbus.ui.nearby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.szfvbus.AppContainer
import hu.szfvbus.data.model.Departure
import hu.szfvbus.data.model.GeoPoint
import hu.szfvbus.data.model.DirectionGroup
import hu.szfvbus.data.model.directionGroups
import hu.szfvbus.ui.common.DepartureRow
import hu.szfvbus.ui.common.DirectionHeader
import hu.szfvbus.ui.common.EmptyBox
import hu.szfvbus.ui.common.ErrorBox
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.LoadingBox
import hu.szfvbus.ui.common.appViewModelFactory
import hu.szfvbus.ui.common.rememberNow
import hu.szfvbus.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A felhasználóhoz közeli, azonos nevű megállók indulásai irányok szerint bontva. */
data class NearbyGroup(
    val name: String,
    val distanceM: Int,
    val directions: List<DirectionGroup>,
    /** Kocsiállás-feliratok megálló-azonosító szerint, az állomásokon. */
    val platforms: Map<String, String> = emptyMap(),
) {
    fun platformOf(departure: Departure): String? = platforms[departure.stopId]
}

class NearbyViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.transit
    private val location = container.location
    private val prefs = container.prefs

    private val _state = MutableStateFlow<Load<List<NearbyGroup>>>(Load.Loading)
    val state = _state.asStateFlow()

    private val _needsPermission = MutableStateFlow(!location.hasPermission())
    val needsPermission = _needsPermission.asStateFlow()

    suspend fun refreshLoop() {
        while (true) {
            refresh()
            delay(30_000)
        }
    }

    suspend fun refresh() {
        if (!location.hasPermission()) {
            _needsPermission.value = true
            return
        }
        _needsPermission.value = false
        try {
            val here: GeoPoint = location.lastKnown()
                ?: withTimeoutOrNull(12_000) { location.updates().first() }
                ?: run {
                    _state.value = Load.Error("Nem sikerült meghatározni a helyzetedet. Be van kapcsolva a helymeghatározás?")
                    return
                }
            val showIntercity = prefs.prefs.first().showIntercity
            val stops = repo.nearby(here, 700, includeIntercity = showIntercity)
            val groups = stops.groupBy { it.stop.name }.map { (name, list) ->
                val departures = list.flatMap { it.departures }
                    .filter { showIntercity || it.isLocal }
                    .distinctBy { it.tripId }
                NearbyGroup(
                    name = name,
                    distanceM = list.minOf { it.distanceM },
                    directions = directionGroups(departures, list.associate { it.stop.id to it.stop })
                        .map { it.copy(departures = it.departures.take(3)) },
                    platforms = list.mapNotNull { n -> n.stop.platformLabel?.let { n.stop.id to it } }.toMap(),
                )
            }.filter { it.directions.isNotEmpty() }.sortedBy { it.distanceM }
            _state.value = Load.Success(groups)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value !is Load.Success) _state.value = Load.Error(e.userMessage())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    permissionVersion: Int,
    onRequestLocation: () -> Unit,
    onStopGroup: (name: String, stopId: String?) -> Unit,
    onDeparture: (Departure) -> Unit,
    onBoard: (Departure) -> Unit,
) {
    val vm: NearbyViewModel = viewModel(factory = appViewModelFactory { NearbyViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    val needsPermission by vm.needsPermission.collectAsStateWithLifecycle()
    val now = rememberNow()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(permissionVersion) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.refreshLoop() }
    }

    Scaffold(
        // Az alsó behúzást már a lenti navigációs sáv kezeli.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Közelben") },
                actions = {
                    IconButton(onClick = onRequestLocation) {
                        Icon(Icons.Default.Refresh, contentDescription = "Frissítés")
                    }
                },
            )
        },
    ) { padding ->
        if (needsPermission) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "A közeli indulások megtekintéséhez engedélyezd a helymeghatározást.",
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Button(onClick = onRequestLocation) { Text("Engedélyezés") }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                Load.Loading -> item { LoadingBox() }
                is Load.Error -> item { ErrorBox(s.message, onRetry = onRequestLocation) }
                is Load.Success -> {
                    if (s.data.isEmpty()) item { EmptyBox("A közelben nincs induló járat a következő két órában.") }
                    items(s.data, key = { it.name }) { group ->
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onStopGroup(group.name, null) }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${group.distanceM} m",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            group.directions.forEach { direction ->
                                DirectionHeader(
                                    direction.label,
                                    direction.platforms,
                                    onClick = { onStopGroup(group.name, direction.stopIds.first()) },
                                )
                                direction.departures.forEach { dep ->
                                    DepartureRow(
                                        dep,
                                        now,
                                        onClick = { onDeparture(dep) },
                                        onBoard = { onBoard(dep) },
                                        platform = group.platformOf(dep).takeIf { direction.platforms.size > 1 },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
