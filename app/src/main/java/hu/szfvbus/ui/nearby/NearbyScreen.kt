package hu.szfvbus.ui.nearby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.szfvbus.AppContainer
import hu.szfvbus.data.DEFAULT_NEARBY_RADIUS
import hu.szfvbus.data.NEARBY_RADII
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    private val radiusFlow = prefs.prefs.map { it.nearbyRadiusM }.distinctUntilChanged()
    val radius = radiusFlow.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_NEARBY_RADIUS)

    /** 30 másodpercenként frissít; ha a sugár változik, a régi kör megszakad és azonnal újrakezdi. */
    suspend fun refreshLoop() {
        radiusFlow.collectLatest { radiusM ->
            while (true) {
                refresh(radiusM)
                delay(30_000)
            }
        }
    }

    fun setRadius(meters: Int) {
        if (meters == radius.value) return
        // A régi sugár listája nem maradhat kint, amíg az új betölt.
        _state.value = Load.Loading
        viewModelScope.launch { prefs.setNearbyRadius(meters) }
    }

    private suspend fun refresh(radiusM: Int) {
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
            val stops = repo.nearby(here, radiusM, includeIntercity = showIntercity)
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
    val radius by vm.radius.collectAsStateWithLifecycle()
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
            item(key = "radius") { RadiusPicker(radius, onSelect = vm::setRadius) }
            when (val s = state) {
                Load.Loading -> item { LoadingBox() }
                is Load.Error -> item { ErrorBox(s.message, onRetry = onRequestLocation) }
                is Load.Success -> {
                    if (s.data.isEmpty()) {
                        item {
                            EmptyBox(
                                "${distanceLabel(radius)}-en belül nincs induló járat a következő két órában." +
                                    if (radius < NEARBY_RADII.last()) " Próbáld nagyobb távolsággal." else "",
                            )
                        }
                    }
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

/** A keresési sugár választója a lista tetején. Nagy betűmérettel a sor görgethető. */
@Composable
private fun RadiusPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NEARBY_RADII.forEach { meters ->
            FilterChip(
                selected = meters == selected,
                onClick = { onSelect(meters) },
                label = { Text(distanceLabel(meters), maxLines = 1) },
            )
        }
    }
}

private fun distanceLabel(meters: Int): String =
    if (meters < 1000) "$meters m" else "%s km".format((meters / 1000.0).toString().removeSuffix(".0").replace('.', ','))
