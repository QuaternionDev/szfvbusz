package hu.szfvbus.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.szfvbus.AppContainer
import hu.szfvbus.data.model.Departure
import hu.szfvbus.data.model.Line
import hu.szfvbus.data.model.DirectionGroup
import hu.szfvbus.data.model.directionGroups
import hu.szfvbus.ui.common.DepartureRow
import hu.szfvbus.ui.common.DirectionHeader
import hu.szfvbus.ui.common.EmptyBox
import hu.szfvbus.ui.common.LineBadge
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.LoadingBox
import hu.szfvbus.ui.common.SectionHeader
import hu.szfvbus.ui.common.appViewModelFactory
import hu.szfvbus.ui.common.rememberNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FavoriteStop(
    val name: String,
    val departures: Load<List<DirectionGroup>>,
    /** Kocsiállás-feliratok megálló-azonosító szerint, az állomásokon. */
    val platforms: Map<String, String> = emptyMap(),
)

class FavoritesViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.transit
    private val userPrefs = container.prefs
    val prefs = userPrefs.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _stops = MutableStateFlow<List<FavoriteStop>>(emptyList())
    val stops = _stops.asStateFlow()

    suspend fun refreshLoop() {
        userPrefs.prefs.collectLatest { p ->
            val names = p.favoriteStops.sorted()
            _stops.value = names.map { name ->
                FavoriteStop(name, _stops.value.firstOrNull { it.name == name }?.departures ?: Load.Loading)
            }
            while (true) {
                refresh(names, p.showIntercity)
                delay(30_000)
            }
        }
    }

    private suspend fun refresh(names: List<String>, showIntercity: Boolean) = coroutineScope {
        val net = try {
            repo.network()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@coroutineScope
        }
        _stops.value = names.map { name ->
            async {
                val group = net.group(name)
                val result: Load<List<DirectionGroup>> = if (group == null) {
                    Load.Error("A megálló már nem szerepel a hálózatban")
                } else {
                    try {
                        val departures = repo.departures(group.ids, count = 4, rangeSec = 12 * 3600)
                            .filter { showIntercity || it.isLocal }
                        Load.Success(
                            directionGroups(departures, net.stops).map { it.copy(departures = it.departures.take(3)) },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _stops.value.firstOrNull { it.name == name }?.departures?.takeIf { it is Load.Success }
                            ?: Load.Error("Nem sikerült frissíteni")
                    }
                }
                val platforms = group?.stops.orEmpty().mapNotNull { s -> s.platformLabel?.let { s.id to it } }.toMap()
                FavoriteStop(name, result, platforms)
            }
        }.awaitAll()
    }

    fun removeStop(name: String) {
        viewModelScope.launch { userPrefs.toggleFavoriteStop(name) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    lines: List<Line>,
    onStopGroup: (name: String, stopId: String?) -> Unit,
    onDeparture: (Departure) -> Unit,
    onBoard: (Departure) -> Unit,
    onLine: (Line) -> Unit,
) {
    val vm: FavoritesViewModel = viewModel(factory = appViewModelFactory { FavoritesViewModel(it) })
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val stops by vm.stops.collectAsStateWithLifecycle()
    val now = rememberNow()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.refreshLoop() }
    }
    val favoriteLines = lines.filter { it.id in prefs?.favoriteLines.orEmpty() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Kedvencek") }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (prefs == null) {
                item { LoadingBox() }
                return@LazyColumn
            }
            if (stops.isEmpty() && favoriteLines.isEmpty()) {
                item {
                    EmptyBox(
                        "Még nincs kedvenced.\nEgy megálló vagy vonal paneljén a ☆ gombbal adhatsz hozzá.",
                    )
                }
            }
            if (stops.isNotEmpty()) item { SectionHeader("Megállók") }
            items(stops, key = { "s-" + it.name }) { fav ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onStopGroup(fav.name, null) }.padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            fav.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { vm.removeStop(fav.name) }) {
                            Icon(Icons.Default.Star, contentDescription = "Eltávolítás", tint = Color(0xFFFFB300))
                        }
                    }
                    when (val d = fav.departures) {
                        Load.Loading -> LoadingBox()
                        is Load.Error -> Text(
                            d.message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                        is Load.Success -> if (d.data.isEmpty()) {
                            Text(
                                "Nincs indulás a következő órákban.",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            d.data.forEach { direction ->
                                DirectionHeader(
                                    direction.label,
                                    direction.platforms,
                                    onClick = { onStopGroup(fav.name, direction.stopIds.first()) },
                                )
                                direction.departures.forEach { dep ->
                                    DepartureRow(
                                        dep,
                                        now,
                                        onClick = { onDeparture(dep) },
                                        onBoard = { onBoard(dep) },
                                        platform = fav.platforms[dep.stopId].takeIf { direction.platforms.size > 1 },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (favoriteLines.isNotEmpty()) item { SectionHeader("Vonalak") }
            items(favoriteLines, key = { "l-" + it.id }) { line ->
                Row(
                    Modifier.fillMaxWidth().clickable { onLine(line) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LineBadge(line.shortName, Color(line.color), Modifier.width(52.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(line.longName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
