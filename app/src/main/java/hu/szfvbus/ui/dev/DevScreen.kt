package hu.szfvbus.ui.dev

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.szfvbus.BuildConfig
import hu.szfvbus.R
import hu.szfvbus.SzfvBusApp
import hu.szfvbus.data.DevTools
import hu.szfvbus.data.model.Alert
import hu.szfvbus.notify.AlertCheckWorker
import hu.szfvbus.notify.AlertNotifier
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.rememberNow
import hu.szfvbus.ui.map.MapViewModel
import hu.szfvbus.ui.map.vibrateAlightAlert
import hu.szfvbus.util.DevClock
import hu.szfvbus.util.TimeUtil
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val ISSUES_URL = "https://github.com/QuaternionDev/szfvbusz/issues"

private val clockFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd. (EEE) HH:mm:ss")

/** Egy sor arról, mit hamisít most a fejlesztői mód, vagy null, ha semmit. */
fun devStatusText(): String? {
    val dev = DevTools.current
    val parts = buildList {
        if (DevClock.isShifted) {
            add("idő ${TimeUtil.clock(TimeUtil.nowSec())}" + if (DevClock.speed != 1.0) " (${DevClock.speed.toInt()}×)" else "")
        }
        if (dev.simulateVehicles) add("szimulált buszok")
        if (dev.fakeDelayMin != 0) add("késés ${"%+d".format(dev.fakeDelayMin)} perc")
        if (dev.boardAnyDeparture) add("felszállás bármikor")
        if (dev.testAlertLine != null) add("teszt hír: ${dev.testAlertLine}")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * A rejtett fejlesztői oldal: hamisítja az órát, a buszokat, a késéseket és a forgalmi híreket, és
 * tesztértesítéseket küld, hogy minden funkció bármikor kipróbálható legyen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DevScreen(vm: MapViewModel, onBack: () -> Unit, onOpenAlerts: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as SzfvBusApp).container
    val scope = rememberCoroutineScope()
    val dev by DevTools.state.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val launchNotices by vm.launchNotices.collectAsStateWithLifecycle()
    val now = rememberNow(1_000)
    val net = (network as? Load.Success)?.data

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    // Az oldal másodpercenként frissül, így az óra változása is látszik.
    fun clockChanged() = vm.retrySelection()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        toast(if (granted) "Értesítések engedélyezve" else "Az értesítések tiltva maradtak")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fejlesztői mód") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Állapot") {
                    val realCount = vehicles.count { !it.id.startsWith("SIM-") }
                    val simCount = vehicles.size - realCount
                    val canNotify = AlertNotifier.canNotify(context)
                    Info("Verzió", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
                    Info("Alkalmazás ideje", clockFormat.format(Instant.ofEpochSecond(now).atZone(TimeUtil.ZONE)))
                    Info(
                        "Hálózat",
                        net?.let { "${it.lines.size} vonal, ${it.stops.size} megálló, letöltve ${TimeUtil.clock(it.fetchedAt / 1000)}" }
                            ?: "nincs betöltve",
                    )
                    Info("Járművek", "$realCount valós, $simCount szimulált")
                    Info("Forgalmi hírek", "${alerts.size} db, ${alerts.count { it.lines.isNotEmpty() }} vonalhoz kötve")
                    Info("Értesítési engedély", if (canNotify) "megadva" else "nincs")
                }
            }

            item {
                Section("Idő", "Az alkalmazás órája; az indulások, az utazás és a szimuláció ezt követi.") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("−1 óra") { DevClock.set(DevClock.nowMs() - 3_600_000); clockChanged() }
                        SmallButton("+10 perc") { DevClock.set(DevClock.nowMs() + 600_000); clockChanged() }
                        SmallButton("+1 óra") { DevClock.set(DevClock.nowMs() + 3_600_000); clockChanged() }
                        SmallButton("Reggeli csúcs 7:30") {
                            var date = TimeUtil.today()
                            if (TimeUtil.nowDateTime().toLocalTime() > LocalTime.of(7, 30)) date = date.plusDays(1)
                            while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) date = date.plusDays(1)
                            DevClock.set(date.atTime(7, 30).atZone(TimeUtil.ZONE).toInstant().toEpochMilli())
                            clockChanged()
                        }
                        SmallButton("Valós idő") { DevClock.reset(); clockChanged() }
                    }
                    Text("Sebesség", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1.0, 10.0, 60.0).forEach { speed ->
                            FilterChip(
                                selected = DevClock.speed == speed,
                                onClick = { DevClock.setSpeed(speed); clockChanged() },
                                label = { Text("${speed.toInt()}×") },
                            )
                        }
                    }
                }
            }

            item {
                Section("Járművek") {
                    Toggle(
                        "Szimulált buszok",
                        "Menetrend szerint mozgó buszok a forgalmas megállók körül és a kiválasztott járaton. " +
                            "Az élő követés, az ÉLŐ jelvény és a felszállás így éjjel is kipróbálható.",
                        dev.simulateVehicles,
                    ) { on -> DevTools.update { it.copy(simulateVehicles = on) } }
                }
            }

            item {
                Section("Felszállás") {
                    Toggle(
                        "Felszállok minden indulásnál",
                        "A gomb a 20 percen túli indulásoknál is megjelenik.",
                        dev.boardAnyDeparture,
                    ) { on -> DevTools.update { it.copy(boardAnyDeparture = on) } }
                    OutlinedButton(onClick = { vibrateAlightAlert(context) }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Leszállás előtti rezgés kipróbálása")
                    }
                }
            }

            item {
                Section("Késés", "Minden várható időhöz hozzáadva, mintha a buszok késnének.") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, -2, 2, 5, 15).forEach { min ->
                            FilterChip(
                                selected = dev.fakeDelayMin == min,
                                onClick = {
                                    DevTools.update { it.copy(fakeDelayMin = min) }
                                    vm.retrySelection()
                                },
                                label = { Text(if (min == 0) "Nincs" else "%+d perc".format(min)) },
                            )
                        }
                    }
                }
            }

            item {
                Section("Forgalmi változások") {
                    Text(
                        "Teszt hír ezen a vonalon (banner a vonalnál, a járatnál és az utazásnál):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        item {
                            FilterChip(
                                selected = dev.testAlertLine == null,
                                onClick = { DevTools.update { it.copy(testAlertLine = null) } },
                                label = { Text("Nincs") },
                            )
                        }
                        items(net?.lines.orEmpty(), key = { it.id }) { line ->
                            FilterChip(
                                selected = dev.testAlertLine == line.shortName,
                                onClick = { DevTools.update { it.copy(testAlertLine = line.shortName) } },
                                label = { Text(line.shortName) },
                            )
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 33 && !AlertNotifier.canNotify(context)) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("Értesítési engedély kérése")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("Teszt értesítés") {
                            if (!AlertNotifier.canNotify(context)) {
                                toast("Nincs értesítési engedély")
                            } else {
                                AlertNotifier.notifyNew(context, listOf(sampleAlert(dev.testAlertLine)))
                            }
                        }
                        SmallButton("Hírek ellenőrzése most") {
                            AlertCheckWorker.runNow(context)
                            toast("Ellenőrzés elindítva; új hír esetén értesítés jön")
                        }
                        SmallButton("Minden hír újként") {
                            scope.launch {
                                container.prefs.setSeenAlerts(emptySet())
                                AlertCheckWorker.runNow(context)
                                toast("Az aktuális hírekről egyenként jön értesítés")
                            }
                        }
                        SmallButton("Első indítás") {
                            scope.launch {
                                container.prefs.setSeenAlerts(null)
                                AlertCheckWorker.runNow(context)
                                toast("Összesítő értesítés jön, mint telepítés után")
                            }
                        }
                        SmallButton("Hírek megnyitása", onOpenAlerts)
                    }
                }
            }

            item {
                Section(
                    "Indítási figyelmeztetések",
                    "Az élő követésről szóló és a forgalmi változásokról szóló teljes képernyős figyelmeztetések.",
                ) {
                    Info("Elrejtve", "${prefs.hiddenNotices.size} db")
                    Info("Hátravan", "${launchNotices.size} db")
                    SmallButton("Összes újra, az elrejtettek is") {
                        vm.replayNotices()
                        toast("A figyelmeztetések újra megjelennek")
                    }
                }
            }

            item {
                Section("Egyéb") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("Hálózat újratöltése") {
                            vm.loadNetwork(force = true)
                            toast("Vonalak és megállók letöltése…")
                        }
                        SmallButton("Tesztek alaphelyzetbe") {
                            DevTools.reset()
                            DevClock.reset()
                            clockChanged()
                        }
                    }
                }
            }

            item {
                Section("Hibajelentés", "A hibákat a GitHub-repó Issues fülén lehet jelenteni.") {
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL))) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Hiba jelentése a GitHubon")
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        DevClock.reset()
                        vm.setDevMode(false)
                        vm.retrySelection()
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) { Text("Fejlesztői mód kikapcsolása") }
            }
        }
    }
}

private fun sampleAlert(line: String?) = Alert(
    id = "dev-notification-${System.currentTimeMillis()}",
    header = "Teszt: Székesfehérvár – ideiglenes forgalmi változás",
    description = "<p><strong>Fejlesztői teszt értesítés.</strong> Munkálatok miatt az autóbuszok terelve közlekednek.</p>",
    url = null,
    start = TimeUtil.nowSec(),
    end = null,
    lines = listOf(line ?: "14", "20").distinct(),
)

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
