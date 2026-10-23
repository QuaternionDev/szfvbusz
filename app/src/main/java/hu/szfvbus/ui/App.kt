package hu.szfvbus.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.szfvbus.SzfvBusApp
import hu.szfvbus.data.ThemeMode
import hu.szfvbus.data.model.Departure
import hu.szfvbus.data.model.LaunchNotice
import hu.szfvbus.notify.AlertCheckWorker
import hu.szfvbus.notify.AlertNotifier
import hu.szfvbus.ui.alerts.AlertsScreen
import hu.szfvbus.ui.changelog.ChangelogScreen
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.appViewModelFactory
import hu.szfvbus.ui.dev.DevScreen
import hu.szfvbus.ui.favorites.FavoritesScreen
import hu.szfvbus.ui.lines.LinesScreen
import hu.szfvbus.ui.map.MapScreen
import hu.szfvbus.ui.map.MapViewModel
import hu.szfvbus.ui.map.Selection
import hu.szfvbus.ui.nearby.NearbyScreen
import hu.szfvbus.ui.notice.LaunchNoticeDialog
import hu.szfvbus.ui.planner.Endpoint
import hu.szfvbus.ui.planner.PlannerScreen
import hu.szfvbus.ui.planner.PlannerViewModel
import hu.szfvbus.ui.search.SearchPurpose
import hu.szfvbus.ui.search.SearchScreen
import hu.szfvbus.ui.theme.SzfvBusTheme
import hu.szfvbus.ui.timetable.TimetableScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Tab(val label: String, val icon: ImageVector) {
    MAP("Térkép", Icons.Default.Map),
    NEARBY("Közelben", Icons.Default.NearMe),
    LINES("Járatok", Icons.Default.FormatListNumbered),
    PLANNER("Tervező", Icons.Default.Directions),
    FAVORITES("Kedvencek", Icons.Default.Star),
}

/** A fülek fölött megnyíló, teljes képernyős oldalak. */
sealed interface Overlay {
    data class Search(val purpose: SearchPurpose) : Overlay
    data class Timetable(val groupName: String, val routeId: String?) : Overlay
    data object Alerts : Overlay
    data object Developer : Overlay
    data object Changelog : Overlay
}

/**
 * A térkép minden fül alatt megmarad, így megtartja a kameraállását és a betöltött csempéket;
 * a többi fül és a ráhelyezett oldalak fölé rajzolódnak.
 */
@Composable
fun SzfvBusAppUi(openAlertsRequests: StateFlow<Int> = MutableStateFlow(0)) {
    val mapVm: MapViewModel = viewModel(factory = appViewModelFactory { MapViewModel(it) })
    val themeMode = mapVm.prefs.collectAsStateWithLifecycle().value.themeMode
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Az állapot- és a navigációs sáv ikonjai az alkalmazás témáját követik, nem a rendszerét.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    SzfvBusTheme(darkTheme) {
        val context = LocalContext.current
        val container = (context.applicationContext as SzfvBusApp).container
        val plannerVm: PlannerViewModel = viewModel(factory = appViewModelFactory { PlannerViewModel(it) })

        var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
        val overlays = remember { mutableStateListOf<Overlay>() }
        var locationVersion by remember { mutableIntStateOf(0) }
        var hasLocation by remember { mutableStateOf(container.location.hasPermission()) }

        val network by mapVm.network.collectAsStateWithLifecycle()
        val prefs by mapVm.prefs.collectAsStateWithLifecycle()
        val vehicles by mapVm.vehicles.collectAsStateWithLifecycle()
        val alerts by mapVm.alerts.collectAsStateWithLifecycle()
        val launchNotices by mapVm.launchNotices.collectAsStateWithLifecycle()
        val closedNotices by mapVm.closedNoticeCount.collectAsStateWithLifecycle()
        val selection by mapVm.selection.collectAsStateWithLifecycle()
        // A térkép sárga jelével kézzel megnyitott figyelmeztetés.
        var openedNotice by remember { mutableStateOf<LaunchNotice?>(null) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            if (result.values.any { it }) {
                hasLocation = true
                locationVersion++
                mapVm.onLocationPermissionGranted()
            }
        }
        val requestLocation: () -> Unit = {
            if (container.location.hasPermission()) {
                hasLocation = true
                locationVersion++
                if (tab == Tab.MAP && !mapVm.centerOnMyLocation()) {
                    Toast.makeText(context, "Helymeghatározás folyamatban…", Toast.LENGTH_SHORT).show()
                }
            } else {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        }

        fun showOnMap(action: () -> Unit) {
            overlays.clear()
            tab = Tab.MAP
            action()
        }
        val openDeparture: (Departure) -> Unit = { dep -> showOnMap { mapVm.selectTrip(dep.tripId, dep.serviceDay) } }
        val boardDeparture: (Departure) -> Unit = { dep -> showOnMap { mapVm.startRide(dep) } }
        val openStopGroup: (name: String, stopId: String?) -> Unit = { name, stopId ->
            showOnMap { mapVm.selectStopGroup(name, stopId) }
        }

        // A forgalmi hírek frissülnek, amíg az alkalmazás látszik; a háttérben az időszakos ellenőrzés fut.
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(Unit) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { mapVm.keepAlertsFresh() }
        }

        // A forgalmi változásról szóló értesítésre koppintva megnyílnak a hírek.
        val openAlerts by openAlertsRequests.collectAsStateWithLifecycle()
        LaunchedEffect(openAlerts) {
            if (openAlerts > 0) {
                overlays.clear()
                overlays += Overlay.Alerts
            }
        }

        // Android 13-tól az alkalmazásnak engedélyt kell kérnie az értesítéshez; induláskor egyszer kérjük,
        // ha az értesítések be vannak kapcsolva.
        val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) AlertCheckWorker.runNow(context)
        }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 && prefs.notifyAlerts && !AlertNotifier.canNotify(context)) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        BackHandler(enabled = overlays.isNotEmpty() || tab != Tab.MAP) {
            if (overlays.isNotEmpty()) overlays.removeAt(overlays.lastIndex) else tab = Tab.MAP
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t && overlays.isEmpty(),
                            onClick = {
                                overlays.clear()
                                tab = t
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                MapScreen(
                    vm = mapVm,
                    active = tab == Tab.MAP && overlays.isEmpty(),
                    darkTheme = darkTheme,
                    onOpenSearch = { overlays += Overlay.Search(SearchPurpose.BROWSE) },
                    onOpenTimetable = { group, routeId -> overlays += Overlay.Timetable(group, routeId) },
                    onOpenAlerts = { overlays += Overlay.Alerts },
                    onOpenDev = { overlays += Overlay.Developer },
                    onOpenChangelog = { overlays += Overlay.Changelog },
                    onLiveCoverageInfo = { openedNotice = LaunchNotice.LiveCoverage },
                    onPlanFrom = { place ->
                        plannerVm.setFrom(Endpoint.Fixed(place))
                        tab = Tab.PLANNER
                    },
                    onPlanTo = { place ->
                        plannerVm.setTo(Endpoint.Fixed(place))
                        tab = Tab.PLANNER
                    },
                    onRequestLocation = requestLocation,
                )

                if (tab != Tab.MAP) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when (tab) {
                            Tab.NEARBY -> NearbyScreen(
                                permissionVersion = locationVersion,
                                onRequestLocation = requestLocation,
                                onStopGroup = openStopGroup,
                                onDeparture = openDeparture,
                                onBoard = boardDeparture,
                            )
                            Tab.LINES -> LinesScreen(
                                network = network,
                                favoriteLines = prefs.favoriteLines,
                                runningLines = vehicles.filter { it.isLocal }.mapNotNull { it.routeId }.groupingBy { it }.eachCount(),
                                alerts = alerts,
                                onLine = { line -> showOnMap { mapVm.selectLine(line.id) } },
                                onAlerts = { overlays += Overlay.Alerts },
                                onRetry = { mapVm.loadNetwork(force = true) },
                            )
                            Tab.PLANNER -> PlannerScreen(
                                vm = plannerVm,
                                onPickFrom = { overlays += Overlay.Search(SearchPurpose.PLAN_FROM) },
                                onPickTo = { overlays += Overlay.Search(SearchPurpose.PLAN_TO) },
                                onItinerary = { itinerary -> showOnMap { mapVm.selectItinerary(itinerary) } },
                            )
                            Tab.FAVORITES -> FavoritesScreen(
                                lines = (network as? Load.Success)?.data?.lines.orEmpty(),
                                onStopGroup = openStopGroup,
                                onDeparture = openDeparture,
                                onBoard = boardDeparture,
                                onLine = { line -> showOnMap { mapVm.selectLine(line.id) } },
                            )
                            Tab.MAP -> Unit
                        }
                    }
                }
            }
        }

        // A ráhelyezett oldalak a navigációs sávot is eltakarják.
        overlays.lastOrNull()?.let { overlay ->
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val close: () -> Unit = { overlays.removeAt(overlays.lastIndex) }
                when (overlay) {
                    is Overlay.Search -> SearchScreen(
                        purpose = overlay.purpose,
                        network = network,
                        hasLocation = hasLocation,
                        onBack = close,
                        onStopGroup = { group ->
                            when (overlay.purpose) {
                                SearchPurpose.BROWSE -> openStopGroup(group.name, null)
                                SearchPurpose.PLAN_FROM -> {
                                    close()
                                    plannerVm.setFrom(Endpoint.Fixed(group.toPlace()))
                                }
                                SearchPurpose.PLAN_TO -> {
                                    close()
                                    plannerVm.setTo(Endpoint.Fixed(group.toPlace()))
                                }
                            }
                        },
                        onLine = { line -> showOnMap { mapVm.selectLine(line.id) } },
                        onPlace = { place ->
                            close()
                            if (overlay.purpose == SearchPurpose.PLAN_FROM) {
                                plannerVm.setFrom(Endpoint.Fixed(place))
                            } else {
                                plannerVm.setTo(Endpoint.Fixed(place))
                            }
                        },
                        onMyLocation = {
                            close()
                            if (overlay.purpose == SearchPurpose.PLAN_FROM) {
                                plannerVm.setFrom(Endpoint.MyLocation)
                            } else {
                                plannerVm.setTo(Endpoint.MyLocation)
                            }
                        },
                    )
                    is Overlay.Timetable -> TimetableScreen(
                        groupName = overlay.groupName,
                        routeId = overlay.routeId,
                        onBack = close,
                    )
                    Overlay.Alerts -> AlertsScreen(onBack = close)
                    Overlay.Developer -> DevScreen(
                        vm = mapVm,
                        onBack = close,
                        onOpenAlerts = { overlays += Overlay.Alerts },
                    )
                    Overlay.Changelog -> ChangelogScreen(onBack = close)
                }
            }
        }

        // Az indítási figyelmeztetések egymás után jönnek; utazás közben nem ugranak fel.
        val opened = openedNotice
        val queued = launchNotices.firstOrNull()?.takeIf { selection !is Selection.Ride }
        when {
            opened != null -> LaunchNoticeDialog(
                notice = opened,
                position = 1,
                total = 1,
                canHide = opened.id !in prefs.hiddenNotices,
                onOk = { openedNotice = null },
                onHide = {
                    mapVm.hideNotice(opened.id)
                    openedNotice = null
                },
            )
            // A kulcs miatt minden figyelmeztetés a szöveg elejéről indul.
            queued != null -> key(queued.id) {
                LaunchNoticeDialog(
                    notice = queued,
                    position = closedNotices + 1,
                    total = closedNotices + launchNotices.size,
                    canHide = !queued.showOnce,
                    onOk = { if (queued.showOnce) mapVm.hideNotice(queued.id) else mapVm.closeNotice(queued.id) },
                    onHide = { mapVm.hideNotice(queued.id) },
                )
            }
        }
    }
}

private fun hu.szfvbus.data.model.StopGroup.toPlace() =
    hu.szfvbus.data.model.Place(name = name, lat = center.lat, lon = center.lon, stopGroup = name)
