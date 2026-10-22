package hu.szfvbus.ui.lines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.szfvbus.data.model.Alert
import hu.szfvbus.data.model.Line
import hu.szfvbus.data.model.Network
import hu.szfvbus.ui.common.ErrorBox
import hu.szfvbus.ui.common.LineBadge
import hu.szfvbus.ui.common.AlertSummaryBanner
import hu.szfvbus.ui.common.AlertTag
import hu.szfvbus.ui.common.LiveBadge
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.LoadingBox
import hu.szfvbus.ui.common.SectionHeader
import hu.szfvbus.util.searchKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinesScreen(
    network: Load<Network>,
    favoriteLines: Set<String>,
    /** Az élőben látható buszok száma vonalanként (route id szerint). */
    runningLines: Map<String, Int>,
    alerts: List<Alert>,
    onLine: (Line) -> Unit,
    onAlerts: () -> Unit,
    onRetry: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    Scaffold(
        // Az alsó behúzást már a lenti navigációs sáv kezeli.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Járatok") },
                actions = {
                    IconButton(onClick = onAlerts) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = "Forgalmi hírek")
                    }
                },
            )
        },
    ) { padding ->
        when (network) {
            Load.Loading -> LoadingBox(Modifier.padding(padding))
            is Load.Error -> ErrorBox(network.message, onRetry, Modifier.padding(padding))
            is Load.Success -> {
                val key = searchKey(filter)
                val lines = remember(network.data, key) {
                    network.data.lines.filter {
                        key.isEmpty() || searchKey(it.shortName).startsWith(key) || searchKey(it.longName).contains(key)
                    }
                }
                val favorites = lines.filter { it.id in favoriteLines }
                val affected = remember(alerts) { alerts.flatMap { it.lines }.toSet() }
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            placeholder = { Text("Szűrés vonalszámra vagy útvonalra") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (alerts.isNotEmpty() && key.isEmpty()) {
                        item {
                            AlertSummaryBanner(alerts, onAlerts, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                    if (favorites.isNotEmpty()) {
                        item { SectionHeader("Kedvencek") }
                        items(favorites, key = { "f-" + it.id }) { line ->
                            LineRow(
                                line,
                                running = runningLines[line.id] ?: 0,
                                favorite = true,
                                changed = line.shortName in affected,
                                onClick = { onLine(line) },
                            )
                        }
                        item { SectionHeader("Összes vonal") }
                    }
                    items(lines, key = { it.id }) { line ->
                        LineRow(
                            line,
                            running = runningLines[line.id] ?: 0,
                            favorite = line.id in favoriteLines,
                            changed = line.shortName in affected,
                            onClick = { onLine(line) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineRow(line: Line, running: Int, favorite: Boolean, changed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBadge(line.shortName, Color(line.color), Modifier.width(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                line.longName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (changed) AlertTag(Modifier.padding(top = 2.dp))
        }
        if (favorite) {
            Icon(
                Icons.Default.Star,
                contentDescription = "Kedvenc",
                tint = Color(0xFFFFB300),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (running > 0) {
            LiveBadge(running, Modifier.padding(start = 8.dp))
        }
    }
}
