package hu.szfvbus.ui.map

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import hu.szfvbus.BuildConfig
import hu.szfvbus.R
import hu.szfvbus.data.ThemeMode
import hu.szfvbus.data.model.GeoPoint
import hu.szfvbus.data.model.LegMode
import hu.szfvbus.data.model.Place
import hu.szfvbus.data.model.RidePhase
import hu.szfvbus.data.model.Stop
import hu.szfvbus.data.model.rideStatus
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.rememberNow
import hu.szfvbus.ui.dev.devStatusText
import hu.szfvbus.ui.notice.CONTACT_EMAIL
import hu.szfvbus.ui.notice.LiveCoverageMark
import hu.szfvbus.ui.notice.UNOFFICIAL_TITLE
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    vm: MapViewModel,
    active: Boolean,
    darkTheme: Boolean,
    onOpenSearch: () -> Unit,
    onOpenTimetable: (groupName: String, routeId: String?) -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenDev: () -> Unit,
    onOpenChangelog: () -> Unit,
    onLiveCoverageInfo: () -> Unit,
    onPlanFrom: (Place) -> Unit,
    onPlanTo: (Place) -> Unit,
    onRequestLocation: () -> Unit,
) {
    val network by vm.network.collectAsStateWithLifecycle()
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    val updatedAt by vm.vehiclesUpdatedAt.collectAsStateWithLifecycle()
    val vehicleError by vm.vehicleError.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val departures by vm.departures.collectAsStateWithLifecycle()
    val trip by vm.trip.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val direction by vm.direction.collectAsStateWithLifecycle()
    val follow by vm.follow.collectAsStateWithLifecycle()
    val myLocation by vm.myLocation.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val alightStopId by vm.alightStopId.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val now = rememberNow(5_000)
    val context = LocalContext.current

    val rideInfo = (selection as? Selection.Ride)?.let { sel ->
        val details = (trip as? Load.Success)?.data?.takeIf { it.tripId == sel.tripId && it.stops.isNotEmpty() }
            ?: return@let null
        val vehicle = vm.findVehicle(sel.tripId, sel.vehicleId).takeIf { vehicles.isNotEmpty() }
        val boardName = (network as? Load.Success)?.data?.stops?.get(sel.boardStopId)?.name
        val board = details.indexOfStop(sel.boardStopId, boardName) ?: 0
        val alight = alightStopId?.let { details.indexOfStop(it, null) }?.takeIf { it > board }
        val next = details.nextStopIndex(now, vehicle?.nextStopName)
        RideInfo(details, vehicle, board, alight, rideStatus(details, board, alight, next))
    }
    val ridePhase = rideInfo?.status?.phase
    LaunchedEffect(ridePhase) {
        if (ridePhase == RidePhase.ALIGHT_NEXT) vibrateAlightAlert(context)
    }
    // Utazás közben a kijelző ébren marad, mint egy navigációs alkalmazásban.
    val view = LocalView.current
    val riding = selection is Selection.Ride
    DisposableEffect(riding) {
        view.keepScreenOn = riding
        onDispose { view.keepScreenOn = false }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(active) {
        if (active) lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.runWhileVisible() }
    }

    // Az utazás csak a „Leszállok” gombbal ér véget.
    BackHandler(enabled = active && selection != Selection.None && !riding) { vm.clearSelection() }

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    var controller by remember { mutableStateOf<MapController?>(null) }
    var longPressPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }

    // Új kijelölésnél a panel a kezdő magasságán nyílik.
    LaunchedEffect(selection) {
        if (sheetState.currentValue == SheetValue.Expanded) sheetState.partialExpand()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenHeight = maxHeight
        val screenHeightPx = constraints.maxHeight
        val peekHeight = if (selection == Selection.None) 200.dp else screenHeight * 0.42f
        val peekPx = with(density) { peekHeight.roundToPx() }
        val statusBarTop = WindowInsets.statusBars.getTop(density)
        val topBarPx = statusBarTop + with(density) { 72.dp.roundToPx() }
        // A kamera azért mozdul, mert valamit kijelöltek, ezért helyet hagy a kijelölt állapot paneljének.
        val cameraPaddingPx by rememberUpdatedState(with(density) { (screenHeight * 0.42f).roundToPx() })
        // Az utazási kártya magasabb a keresősávnál.
        val fitTopPx by rememberUpdatedState(if (riding) statusBarTop + with(density) { 190.dp.roundToPx() } else topBarPx)

        // A térkép eseményeinek bekötése a nézetmodellbe.
        LaunchedEffect(controller) {
            val c = controller ?: return@LaunchedEffect
            c.onVehicleClick = { if (vm.selection.value !is Selection.Ride) vm.selectVehicle(it) }
            c.onStopClick = { if (vm.selection.value !is Selection.Ride) vm.selectStop(it) }
            c.onEmptyClick = { }
            c.onLongClick = { longPressPoint = it }
            c.onUserGesture = { vm.stopFollowing() }
        }
        LaunchedEffect(controller, network) {
            val net = (network as? Load.Success)?.data ?: return@LaunchedEffect
            controller?.setStops(net.stops.values)
        }
        val shownVehicles = remember(vehicles, selection) {
            when (val sel = selection) {
                is Selection.Line -> vehicles.filter { it.routeId == sel.routeId }
                else -> vehicles
            }
        }
        val selectedVehicleId = when (val sel = selection) {
            is Selection.Trip -> vm.findVehicle(sel)?.id
            is Selection.Ride -> vm.findVehicle(sel.tripId, sel.vehicleId)?.id
            else -> null
        }
        LaunchedEffect(controller, shownVehicles, selectedVehicleId) {
            controller?.setVehicles(shownVehicles, vehicleColors(shownVehicles), selectedVehicleId)
        }
        LaunchedEffect(controller, overlay) { controller?.setOverlay(overlay) }
        LaunchedEffect(controller, myLocation) { controller?.setMyLocation(myLocation) }
        LaunchedEffect(controller) {
            val c = controller ?: return@LaunchedEffect
            vm.camera.collect { cmd ->
                when (cmd) {
                    is CameraCommand.Center -> c.moveTo(cmd.point, cmd.zoom, cameraPaddingPx)
                    is CameraCommand.Fit -> c.fit(cmd.points, cameraPaddingPx, fitTopPx)
                    is CameraCommand.Follow -> c.follow(cmd.vehicleId, cameraPaddingPx, cmd.navigation)
                    CameraCommand.ResetTilt -> c.resetTilt()
                }
            }
        }

        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetShadowElevation = 8.dp,
            sheetContent = {
                Box(Modifier.fillMaxWidth().height(screenHeight * 0.88f)) {
                    SheetContent(
                        vm = vm,
                        selection = selection,
                        network = network,
                        vehicles = vehicles,
                        updatedAt = updatedAt,
                        vehicleError = vehicleError,
                        departures = departures,
                        trip = trip,
                        route = route,
                        direction = direction,
                        follow = follow,
                        favoriteStops = prefs.favoriteStops,
                        favoriteLines = prefs.favoriteLines,
                        rideInfo = rideInfo,
                        alerts = alerts,
                        now = now,
                        onOpenTimetable = onOpenTimetable,
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                TransitMap(
                    darkTheme = darkTheme,
                    topInset = with(density) { topBarPx.toDp() },
                    onController = { controller = it },
                    modifier = Modifier.fillMaxSize(),
                )

                if (riding) {
                    if (rideInfo != null) RideBanner(rideInfo, now)
                } else {
                    TopBar(
                        showIntercity = prefs.showIntercity,
                        notifyAlerts = prefs.notifyAlerts,
                        checkUpdates = prefs.checkUpdates,
                        devMode = prefs.devMode,
                        // Minden óraütéskor újraolvasva.
                        devStatus = if (prefs.devMode && now > 0) devStatusText() else null,
                        offline = vehicleError != null,
                        onSearch = onOpenSearch,
                        onToggleIntercity = { vm.setShowIntercity(!prefs.showIntercity) },
                        onToggleNotify = { vm.setNotifyAlerts(!prefs.notifyAlerts) },
                        onToggleUpdates = { vm.setCheckUpdates(!prefs.checkUpdates) },
                        onAlerts = onOpenAlerts,
                        onTheme = { showTheme = true },
                        onDev = onOpenDev,
                        onChangelog = onOpenChangelog,
                        onAbout = { showAbout = true },
                    )
                }

                // A lebegő gombok közvetlenül a panel fölött úsznak.
                val sheetTop = runCatching { sheetState.requireOffset() }.getOrNull()
                val fabSize = with(density) { 48.dp.roundToPx() }
                val margin = with(density) { 12.dp.roundToPx() }
                val sheetTopPx = sheetTop?.roundToInt() ?: (screenHeightPx - peekPx)
                if (sheetTopPx > screenHeightPx * 0.35f) {
                    // Szemben a saját helyzet gombjával: nem minden busz követhető élőben.
                    LiveCoverageMark(
                        onClick = onLiveCoverageInfo,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(margin / 2, sheetTopPx - fabSize - margin) },
                    )
                    Column(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(-margin, sheetTopPx - fabSize - margin) },
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                if (!vm.centerOnMyLocation()) onRequestLocation()
                            },
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = "Saját helyzet")
                        }
                    }
                }
            }
        }
    }

    longPressPoint?.let { point ->
        AlertDialog(
            onDismissRequest = { longPressPoint = null },
            title = { Text("Útvonaltervezés") },
            text = { Text("Mi legyen a kijelölt pont?") },
            confirmButton = {
                TextButton(onClick = {
                    longPressPoint = null
                    onPlanTo(Place("Kijelölt pont", point.lat, point.lon))
                }) { Text("Útvonal ide") }
            },
            dismissButton = {
                TextButton(onClick = {
                    longPressPoint = null
                    onPlanFrom(Place("Kijelölt pont", point.lat, point.lon))
                }) { Text("Útvonal innen") }
            },
            icon = { Icon(Icons.Default.TripOrigin, contentDescription = null) },
        )
    }

    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("Téma") },
            icon = { Icon(Icons.Default.Palette, contentDescription = null) },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setThemeMode(mode)
                                    showTheme = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = prefs.themeMode == mode, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Bezárás") } },
        )
    }

    if (showAbout) {
        // Mint magában az Androidban: hét koppintás a verziószámra bekapcsolja a fejlesztői módot.
        var versionTaps by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("SzfvBusz") },
            icon = { Icon(Icons.Default.DirectionsBus, contentDescription = null) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Verzió: v${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            versionTaps++
                            val left = DEV_MODE_TAPS - versionTaps
                            when {
                                prefs.devMode -> toast(context, "A fejlesztői mód már be van kapcsolva")
                                left == 0 -> {
                                    vm.setDevMode(true)
                                    toast(context, "Fejlesztői mód bekapcsolva – a menüben találod")
                                }
                                left in 1..3 -> toast(context, "Még $left koppintás a fejlesztői módhoz")
                            }
                        },
                    )
                    AboutText()
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Rendben") } },
            dismissButton = {
                TextButton(onClick = {
                    showAbout = false
                    onOpenChangelog()
                }) { Text("Változásnapló") }
            },
        )
    }
}

private const val DEV_MODE_TAPS = 7

/** Az alkalmazás forráskódja. */
private const val SOURCE_URL = "https://github.com/QuaternionDev/szfvbusz"

private var lastToast: Toast? = null

private fun toast(context: android.content.Context, text: String) {
    lastToast?.cancel()
    lastToast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
}

@Composable
private fun AboutText() {
    val context = LocalContext.current
    Text("Készítette: QuaternionDev\n\nAndroid alkalmazás Székesfehérvár helyi buszközlekedéséhez.")
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(12.dp)) {
            Text(UNOFFICIAL_TITLE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Független projekt: nem a MÁV-csoport vagy a Volánbusz terméke, és nem áll kapcsolatban " +
                    "velük. A pontos információkért lásd a MÁV-csoport oldalait.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("Kapcsolat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(
        "Hibajelzés, kérdés, vagy ha az alkalmazás a jogait sérti:",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        CONTACT_EMAIL,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL"))) }
            }
            .padding(vertical = 6.dp),
    )
    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) } },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(painterResource(R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Forráskód a GitHubon")
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Menetrend és járműadatok: MÁV-csoport (MÁV Plusz utazástervező). " +
            "Térkép: OpenFreeMap, © OpenMapTiles, © OpenStreetMap közreműködők. " +
            "Címkeresés: Photon (komoot).\n\n" +
            "Forgalmi változások: MÁVINFORM (mavcsoport.hu).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TopBar(
    showIntercity: Boolean,
    notifyAlerts: Boolean,
    checkUpdates: Boolean,
    devMode: Boolean,
    devStatus: String?,
    offline: Boolean,
    onSearch: () -> Unit,
    onToggleIntercity: () -> Unit,
    onToggleNotify: () -> Unit,
    onToggleUpdates: () -> Unit,
    onAlerts: () -> Unit,
    onTheme: () -> Unit,
    onDev: () -> Unit,
    onChangelog: () -> Unit,
    onAbout: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.weight(1f).height(52.dp).clickable(onClick = onSearch),
            ) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Megálló vagy vonal keresése",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Surface(
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(52.dp),
                ) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Layers, contentDescription = "Beállítások")
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Helyközi buszok mutatása") },
                        leadingIcon = { Checkbox(checked = showIntercity, onCheckedChange = null) },
                        onClick = {
                            menuOpen = false
                            onToggleIntercity()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Forgalmi hírek") },
                        leadingIcon = { Icon(Icons.Default.NotificationsActive, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAlerts()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Értesítés új változásról") },
                        leadingIcon = { Checkbox(checked = notifyAlerts, onCheckedChange = null) },
                        onClick = {
                            menuOpen = false
                            onToggleNotify()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Új verzió keresése") },
                        leadingIcon = { Checkbox(checked = checkUpdates, onCheckedChange = null) },
                        onClick = {
                            menuOpen = false
                            onToggleUpdates()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Téma") },
                        leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onTheme()
                        },
                    )
                    if (devMode) {
                        DropdownMenuItem(
                            text = { Text("Fejlesztői mód") },
                            leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDev()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Változásnapló") },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onChangelog()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Névjegy") },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAbout()
                        },
                    )
                }
            }
        }
        if (devStatus != null) {
            Surface(
                onClick = onDev,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "TESZT · $devStatus",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (offline) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nincs kapcsolat, újrapróbálkozás…", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SheetContent(
    vm: MapViewModel,
    selection: Selection,
    network: Load<hu.szfvbus.data.model.Network>,
    vehicles: List<hu.szfvbus.data.model.Vehicle>,
    updatedAt: Long?,
    vehicleError: String?,
    departures: Load<List<hu.szfvbus.data.model.Departure>>,
    trip: Load<hu.szfvbus.data.model.TripDetails>,
    route: Load<hu.szfvbus.data.model.RouteDetails>,
    direction: Int,
    follow: Boolean,
    favoriteStops: Set<String>,
    favoriteLines: Set<String>,
    rideInfo: RideInfo?,
    alerts: List<hu.szfvbus.data.model.Alert>,
    now: Long,
    onOpenTimetable: (groupName: String, routeId: String?) -> Unit,
) {
    val net = (network as? Load.Success)?.data
    when (selection) {
        Selection.None -> OverviewPanel(
            network = network,
            vehicles = vehicles,
            updatedAt = updatedAt,
            error = vehicleError,
            onLineClick = vm::selectLine,
            onRetryNetwork = { vm.loadNetwork(force = true) },
        )
        is Selection.StopGroup -> StopPanel(
            group = net?.group(selection.name),
            stops = net?.stops.orEmpty(),
            firstStopId = selection.stopId,
            departures = departures,
            isFavorite = selection.name in favoriteStops,
            now = now,
            onFavorite = { vm.toggleFavoriteStop(selection.name) },
            onTimetable = { onOpenTimetable(selection.name, null) },
            onDepartureClick = { vm.selectTrip(it.tripId, it.serviceDay) },
            onBoard = vm::startRide,
            onClose = vm::clearSelection,
            onRetry = vm::retrySelection,
        )
        is Selection.Trip -> TripPanel(
            trip = trip,
            vehicle = vm.findVehicle(selection).takeIf { vehicles.isNotEmpty() },
            alerts = alerts,
            follow = follow,
            now = now,
            onFollow = vm::toggleFollow,
            onStopClick = vm::selectStop,
            onLineClick = vm::selectLine,
            onClose = vm::clearSelection,
            onRetry = vm::retrySelection,
        )
        is Selection.Line -> LinePanel(
            route = route,
            alerts = alerts,
            direction = direction,
            vehiclesOnLine = vehicles.count { it.routeId == selection.routeId },
            isFavorite = selection.routeId in favoriteLines,
            onDirection = vm::setDirection,
            onFavorite = { vm.toggleFavoriteLine(selection.routeId) },
            onStopClick = { stop: Stop -> onOpenTimetable(stop.name, selection.routeId) },
            onClose = vm::clearSelection,
            onRetry = vm::retrySelection,
        )
        is Selection.Route -> ItineraryPanel(
            itinerary = selection.itinerary,
            onLegClick = { leg ->
                if (leg.mode != LegMode.WALK && leg.tripId != null) vm.selectTrip(leg.tripId, leg.serviceDay)
            },
            onClose = vm::clearSelection,
        )
        is Selection.Ride -> RidePanel(
            trip = trip,
            info = rideInfo,
            alerts = alerts,
            follow = follow,
            onFollow = vm::toggleFollow,
            onAlightStop = vm::setAlightStop,
            onEnd = vm::endRide,
            onRetry = vm::retrySelection,
        )
    }
}
