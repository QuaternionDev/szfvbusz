package hu.szfvbus.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.szfvbus.AppContainer
import hu.szfvbus.BuildConfig
import hu.szfvbus.data.DevTools
import hu.szfvbus.data.LineColors
import hu.szfvbus.data.Prefs
import hu.szfvbus.data.ThemeMode
import hu.szfvbus.data.VehicleSimulator
import hu.szfvbus.data.api.AppRelease
import hu.szfvbus.data.api.UpdateClient
import hu.szfvbus.data.model.Alert
import hu.szfvbus.data.model.Departure
import hu.szfvbus.data.model.GeoPoint
import hu.szfvbus.data.model.Itinerary
import hu.szfvbus.data.model.LaunchNotice
import hu.szfvbus.data.model.LegMode
import hu.szfvbus.data.model.Network
import hu.szfvbus.data.model.noticeQueue
import hu.szfvbus.data.model.rideStatus
import hu.szfvbus.data.model.RidePhase
import hu.szfvbus.data.model.RouteDetails
import hu.szfvbus.data.model.TripDetails
import hu.szfvbus.data.model.Vehicle
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.userMessage
import hu.szfvbus.util.DevClock
import hu.szfvbus.util.TimeUtil
import hu.szfvbus.util.bearingDeg
import hu.szfvbus.util.distanceM
import hu.szfvbus.util.platformText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/** Amit az alsó panel és a térképréteg éppen mutat. */
sealed interface Selection {
    data object None : Selection
    /** A [stopId] a peron, amelyre a felhasználó koppintott; az iránya kerül előre. */
    data class StopGroup(val name: String, val stopId: String? = null) : Selection
    data class Trip(val tripId: String, val vehicleId: String?, val serviceDate: LocalDate?) : Selection
    data class Line(val routeId: String) : Selection
    data class Route(val itinerary: Itinerary) : Selection

    /** Utazás egy buszon a [boardStopId] megállótól, navigációs stílusú útmutatással. */
    data class Ride(
        val tripId: String,
        val vehicleId: String?,
        val serviceDate: LocalDate?,
        val boardStopId: String,
    ) : Selection
}

sealed interface CameraCommand {
    data class Center(val point: GeoPoint, val zoom: Double) : CameraCommand
    data class Fit(val points: List<GeoPoint>) : CameraCommand
    /** A [navigation] megdönti a térképet és ráközelít, mint egy navigációs alkalmazás. */
    data class Follow(val vehicleId: String?, val navigation: Boolean = false) : CameraCommand
    /** Navigációs nézet az utas saját helyzetén: a haladási irány felfelé, az utas a képernyő alsó részén. */
    data object FollowLocation : CameraCommand
    data object ResetTilt : CameraCommand
}

class MapViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.transit
    private val userPrefs = container.prefs
    private val locationRepo = container.location
    private val updateClient = container.updates
    private val simulator = VehicleSimulator(repo)

    // Egyszer, előre beolvasva, hogy a választott téma villanás nélkül, az első képkockától érvényes legyen.
    val prefs: StateFlow<Prefs> = userPrefs.prefs.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        runBlocking { userPrefs.prefs.first() },
    )

    private val _network = MutableStateFlow<Load<Network>>(Load.Loading)
    val network = _network.asStateFlow()

    private val allVehicles = MutableStateFlow<List<Vehicle>>(emptyList())
    val vehicles: StateFlow<List<Vehicle>> = combine(allVehicles, prefs) { list, p ->
        if (p.showIntercity) list else list.filter { it.isLocal }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _vehiclesUpdatedAt = MutableStateFlow<Long?>(null)
    val vehiclesUpdatedAt = _vehiclesUpdatedAt.asStateFlow()

    private val _vehicleError = MutableStateFlow<String?>(null)
    val vehicleError = _vehicleError.asStateFlow()

    private val _selection = MutableStateFlow<Selection>(Selection.None)
    val selection = _selection.asStateFlow()

    private val _departures = MutableStateFlow<Load<List<Departure>>>(Load.Loading)
    val departures = _departures.asStateFlow()

    private val _trip = MutableStateFlow<Load<TripDetails>>(Load.Loading)
    val trip = _trip.asStateFlow()

    private val _route = MutableStateFlow<Load<RouteDetails>>(Load.Loading)
    val route = _route.asStateFlow()

    private val _direction = MutableStateFlow(0)
    val direction = _direction.asStateFlow()

    private val _follow = MutableStateFlow(false)
    val follow = _follow.asStateFlow()

    /** Ahol az utas leszáll utazás közben; null esetén a vonal végén. */
    private val _alightStopId = MutableStateFlow<String?>(null)
    val alightStopId = _alightStopId.asStateFlow()

    /** Hogy az utazás követi-e már a buszát, így a térképet elhúzó felhasználót nem rántja vissza. */
    private var rideFollowStarted = false

    /** Felszállt-e már az utas: addig a buszt követi a kamera, utána az utas saját helyzetét. */
    private var rideBoarded = false

    /** A városi vonalak forgalmi változásai, sávként az érintett vonalaknál. */
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    /**
     * Az első sikeres letöltés forgalmi változásai: ezek jelennek meg indításkor. A később érkező
     * hírek nem ugranak fel használat közben; azokról értesítés szól.
     */
    private val launchAlerts = MutableStateFlow<List<Alert>?>(null)

    /** Az ebben a menetben „Rendben” vagy „Ne mutasd újra” gombbal bezárt figyelmeztetések. */
    private val closedNotices = MutableStateFlow(emptySet<String>())

    /** A még megjelenítendő indítási figyelmeztetések, sorrendben. */
    val launchNotices: StateFlow<List<LaunchNotice>> = combine(launchAlerts, prefs, closedNotices) { alerts, p, closed ->
        noticeQueue(alerts.orEmpty(), p.hiddenNotices, closed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, noticeQueue(emptyList(), prefs.value.hiddenNotices, emptySet()))

    /** Hány figyelmeztetés zárult be eddig (a „2 / 8” számlálóhoz). */
    val closedNoticeCount: StateFlow<Int> = closedNotices.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Az elérhető újabb verzió a GitHubon, amíg a felhasználó be nem zárja az értesítést. */
    private val _update = MutableStateFlow<AppRelease?>(null)
    val update = _update.asStateFlow()

    private val _myLocation = MutableStateFlow<GeoPoint?>(null)
    val myLocation = _myLocation.asStateFlow()

    /** Az utas haladási iránya fokban, két elég távoli helyzetéből számolva. */
    private val _myHeading = MutableStateFlow<Float?>(null)
    val myHeading = _myHeading.asStateFlow()

    /** A helyzet, amelyhez képest a következő irány számolódik. */
    private var headingAnchor: GeoPoint? = null

    private val retryCounter = MutableStateFlow(0)
    private var handledRetry = 0

    /** A kijelölés, amelyhez a kamera legutóbb igazodott, hogy a térképre visszatérve ne ugorjon újra. */
    private var cameraFittedFor: Selection? = null
    private val locationPermissionVersion = MutableStateFlow(0)

    private val _camera = MutableSharedFlow<CameraCommand>(extraBufferCapacity = 8)
    val camera = _camera.asSharedFlow()

    /** Alakzatok és jelölők a jelenlegi kijelöléshez. */
    val overlay: StateFlow<MapOverlay> = combine(
        combine(_selection, _alightStopId, ::Pair), _trip, _route, _direction, _network,
    ) { (sel, alight), trip, route, dir, net ->
        buildOverlay(sel, alight, trip, route, dir, (net as? Load.Success)?.data)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MapOverlay())

    init {
        loadNetwork()
        checkForUpdate()
        viewModelScope.launch {
            DevTools.state.map { it.simulateVehicles }.distinctUntilChanged().collect { on ->
                if (!on) simulator.clear()
            }
        }
    }

    /**
     * Frissen tartja a forgalmi változásokat, amíg az alkalmazás látszik. A sávok minden fülön megjelennek,
     * ezért ez a térképen kívül is fut; a háttérben az időszakos ellenőrzés veszi át.
     */
    suspend fun keepAlertsFresh() {
        // A fejlesztői mód teszthíre azonnal megjelenik.
        DevTools.state.map { it.testAlertLine }.distinctUntilChanged().collectLatest {
            while (true) {
                loadAlerts()
                delay(ALERTS_POLL_MS)
            }
        }
    }

    fun loadNetwork(force: Boolean = false) {
        viewModelScope.launch {
            if (_network.value !is Load.Success) _network.value = Load.Loading
            _network.value = try {
                Load.Success(repo.network(force))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.userMessage())
            }
        }
    }

    /** Élő adatokat kérdez le, amíg a térkép látszik; elrejtéskor leáll. */
    suspend fun runWhileVisible() = coroutineScope {
        if (_network.value is Load.Error) loadNetwork()
        launch {
            while (true) {
                refreshVehicles()
                // Gyorsított fejlesztői időben sűrűbben, hogy a szimulált busz ne ugráljon megállókat.
                delay((VEHICLE_POLL_MS / DevClock.speed).toLong().coerceAtLeast(MIN_VEHICLE_POLL_MS))
            }
        }
        launch {
            combine(_selection, retryCounter) { sel, retry -> sel to retry }.collectLatest { (sel, retry) ->
                val forced = retry != handledRetry
                handledRetry = retry
                refreshSelection(sel, forced)
            }
        }
        launch {
            locationPermissionVersion.collectLatest {
                locationRepo.updates().collect(::onLocation)
            }
        }
    }

    private fun onLocation(point: GeoPoint) {
        // Álló helyzetben a GPS ingadozása ne forgassa a nyilat.
        val anchor = headingAnchor
        if (anchor == null || distanceM(anchor, point) >= HEADING_MIN_MOVE_M) {
            if (anchor != null) _myHeading.value = bearingDeg(anchor, point)
            headingAnchor = point
        }
        val first = _myLocation.value == null
        _myLocation.value = point
        // Ha a felszálláskor még nem volt helyzet, az elsővel indul a navigációs nézet.
        if (first && rideBoarded && _follow.value) _camera.tryEmit(CameraCommand.FollowLocation)
    }

    /** Az engedély megadása után újraindítja a helyzetfrissítéseket. */
    fun onLocationPermissionGranted() {
        locationRepo.lastKnown()?.let {
            _myLocation.value = it
            _camera.tryEmit(CameraCommand.Center(it, 16.0))
        }
        locationPermissionVersion.value++
    }

    fun centerOnMyLocation(): Boolean {
        val p = _myLocation.value ?: locationRepo.lastKnown() ?: return false
        _myLocation.value = p
        stopFollowing()
        _camera.tryEmit(CameraCommand.Center(p, 16.0))
        return true
    }

    private suspend fun loadAlerts() {
        try {
            val list = repo.alerts()
            _alerts.value = list
            if (launchAlerts.value == null) launchAlerts.value = list
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A sávok csak kiegészítők; az utolsó ismert lista marad.
        }
    }

    private suspend fun refreshVehicles() {
        try {
            val net = (_network.value as? Load.Success)?.data
            val simulated = if (DevTools.current.simulateVehicles && net != null) simulator.vehicles(net) else emptyList()
            allVehicles.value = repo.vehicles() + simulated
            _vehiclesUpdatedAt.value = TimeUtil.nowSec()
            _vehicleError.value = null
            moveRideTestPassenger()
            // Ha az utazás a busz elindulása előtt kezdődött, a buszt felveszi, amint az megjelenik.
            val sel = _selection.value
            if (sel is Selection.Ride && !rideFollowStarted && !rideBoarded) {
                findVehicle(sel.tripId, sel.vehicleId)?.let { followRide(it.id) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _vehicleError.value = e.userMessage()
        }
    }

    private suspend fun refreshSelection(sel: Selection, forced: Boolean) {
        when (sel) {
            is Selection.StopGroup -> while (true) {
                loadDepartures(sel)
                delay(DEPARTURES_POLL_MS)
            }
            is Selection.Trip -> {
                var first = true
                while (true) {
                    loadTrip(sel, sel.tripId, sel.serviceDate, sel.vehicleId, first)
                    first = false
                    delay(TRIP_POLL_MS)
                }
            }
            is Selection.Ride -> {
                var first = true
                while (true) {
                    loadTrip(sel, sel.tripId, sel.serviceDate, sel.vehicleId, first)
                    first = false
                    delay(RIDE_POLL_MS)
                }
            }
            is Selection.Line -> {
                val loaded = (_route.value as? Load.Success)?.data?.line?.id == sel.routeId
                if (!loaded || forced) loadRoute(sel)
            }
            is Selection.Route, Selection.None -> Unit
        }
    }

    private suspend fun loadDepartures(sel: Selection.StopGroup) {
        val group = (network.value as? Load.Success)?.data?.group(sel.name)
        if (group == null) {
            _departures.value = Load.Error("Ismeretlen megálló")
            return
        }
        try {
            val all = repo.departures(group.ids, count = 14, rangeSec = 12 * 3600)
            val showIntercity = prefs.value.showIntercity
            _departures.value = Load.Success(all.filter { showIntercity || it.isLocal })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_departures.value !is Load.Success) _departures.value = Load.Error(e.userMessage())
        }
    }

    private suspend fun loadTrip(
        sel: Selection,
        tripId: String,
        serviceDate: LocalDate?,
        vehicleId: String?,
        first: Boolean,
    ) {
        try {
            val details = repo.trip(tripId, serviceDate)
            if (details == null) {
                _trip.value = Load.Error("A járat adatai nem érhetők el")
                return
            }
            _trip.value = Load.Success(details)
            if (DevTools.current.simulateVehicles) simulator.track(details)
            if (first && cameraFittedFor != sel && vehicleId == null && findVehicle(tripId, null) == null) {
                cameraFittedFor = sel
                val points = if (sel is Selection.Ride) {
                    // A járat még az utas előtt lévő része.
                    val board = details.indexOfStop(sel.boardStopId, null) ?: 0
                    details.stops.drop(board).map { GeoPoint(it.lat, it.lon) }
                } else {
                    details.shape.ifEmpty { details.stops.map { GeoPoint(it.lat, it.lon) } }
                }
                _camera.tryEmit(CameraCommand.Fit(points))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_trip.value !is Load.Success) _trip.value = Load.Error(e.userMessage())
        }
    }

    private suspend fun loadRoute(sel: Selection.Line) {
        try {
            val details = repo.route(sel.routeId)
            if (details == null) {
                _route.value = Load.Error("A vonal adatai nem érhetők el")
                return
            }
            _route.value = Load.Success(details)
            if (cameraFittedFor != sel) {
                cameraFittedFor = sel
                _camera.tryEmit(CameraCommand.Fit(details.mainPatterns.flatMap { it.shape }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _route.value = Load.Error(e.userMessage())
        }
    }

    fun retrySelection() {
        when (_selection.value) {
            is Selection.StopGroup -> _departures.value = Load.Loading
            is Selection.Trip, is Selection.Ride -> _trip.value = Load.Loading
            is Selection.Line -> _route.value = Load.Loading
            else -> Unit
        }
        retryCounter.value++
    }

    fun findVehicle(sel: Selection.Trip): Vehicle? = findVehicle(sel.tripId, sel.vehicleId)

    fun findVehicle(tripId: String, vehicleId: String?): Vehicle? {
        val list = allVehicles.value
        return list.firstOrNull { it.tripId == tripId } ?: vehicleId?.let { id -> list.firstOrNull { it.id == id } }
    }

    /** Megnyit egy megállót; [stopId] esetén annak a peronnak az iránya kerül előre. */
    fun selectStopGroup(name: String, stopId: String? = null) {
        val net = (network.value as? Load.Success)?.data ?: return
        val group = net.group(name) ?: return
        stopFollowing()
        val current = _selection.value
        // Ugyanannak a megállónak egy másik peronja megtartja az indulásokat; csak a sorrendjük változik.
        if (current !is Selection.StopGroup || current.name != name) _departures.value = Load.Loading
        _selection.value = Selection.StopGroup(name, stopId)
        _camera.tryEmit(CameraCommand.Center(group.center, 16.5))
    }

    fun selectStop(stopId: String) {
        val net = (network.value as? Load.Success)?.data ?: return
        net.stops[stopId]?.let { selectStopGroup(it.name, stopId) }
    }

    fun selectVehicle(vehicleId: String) {
        val v = allVehicles.value.firstOrNull { it.id == vehicleId } ?: return
        val tripId = v.tripId ?: return
        _trip.value = Load.Loading
        _selection.value = Selection.Trip(tripId, v.id, null)
        _follow.value = true
        _camera.tryEmit(CameraCommand.Follow(v.id))
    }

    fun selectTrip(tripId: String, serviceDay: Long?) {
        val date = serviceDay?.let { TimeUtil.serviceDateOf(it) }
        val vehicle = allVehicles.value.firstOrNull { it.tripId == tripId }
        _trip.value = Load.Loading
        _selection.value = Selection.Trip(tripId, vehicle?.id, date)
        if (vehicle != null) {
            _follow.value = true
            _camera.tryEmit(CameraCommand.Follow(vehicle.id))
        } else {
            stopFollowing()
        }
    }

    fun selectLine(routeId: String) {
        stopFollowing()
        _route.value = Load.Loading
        _direction.value = 0
        _selection.value = Selection.Line(routeId)
    }

    fun selectItinerary(itinerary: Itinerary) {
        stopFollowing()
        _selection.value = Selection.Route(itinerary)
        _camera.tryEmit(CameraCommand.Fit(itinerary.legs.flatMap { leg -> leg.shape.ifEmpty { listOf(leg.from.point, leg.to.point) } }))
    }

    /** „Felszállok”: végigvezeti az utast az indulás járatán, amíg le nem száll. */
    fun startRide(departure: Departure) {
        val vehicle = allVehicles.value.firstOrNull { it.tripId == departure.tripId }
        val current = (_trip.value as? Load.Success)?.data
        if (current?.tripId != departure.tripId) _trip.value = Load.Loading
        _alightStopId.value = null
        rideFollowStarted = false
        rideBoarded = false
        _selection.value = Selection.Ride(
            tripId = departure.tripId,
            vehicleId = vehicle?.id,
            serviceDate = TimeUtil.serviceDateOf(departure.serviceDay),
            boardStopId = departure.stopId,
        )
        if (vehicle != null) followRide(vehicle.id) else stopFollowing()
    }

    private fun followRide(vehicleId: String) {
        rideFollowStarted = true
        _follow.value = true
        _camera.tryEmit(CameraCommand.Follow(vehicleId, navigation = true))
    }

    /**
     * A felszállás pillanata: innen a kamera a busz helyett az utas saját helyzetét követi navigációs nézetben,
     * akkor is, ha a felhasználó közben elhúzta a térképet. Helyzet nélkül marad a busz.
     */
    fun setRideBoarded(boarded: Boolean) {
        if (_selection.value !is Selection.Ride || boarded == rideBoarded) return
        rideBoarded = boarded
        rideFollowTarget()?.let {
            _follow.value = true
            _camera.tryEmit(it)
        }
    }

    /** Amit utazás közben a követés mutat: felszállás előtt a buszt, utána az utast. */
    private fun rideFollowTarget(): CameraCommand? {
        val sel = _selection.value as? Selection.Ride ?: return null
        if (rideBoarded && _myLocation.value != null) return CameraCommand.FollowLocation
        return findVehicle(sel.tripId, sel.vehicleId)?.let { CameraCommand.Follow(it.id, navigation = true) }
    }

    /** Kiválasztja a leszállási megállót; újra kiválasztva visszaáll a vonal végére. */
    fun setAlightStop(stopId: String) {
        _alightStopId.value = if (_alightStopId.value == stopId) null else stopId
    }

    /**
     * Fejlesztői utazásteszt: egy hamarosan induló járat elé teszi az órát [speed]-szeres tempóval, az utast
     * a megállóba, szimulálja a buszt, és megnyitja a megállót, ahol a „Felszállok” gomb vár. A térképen
     * nyitott megállóból indul, ha van, különben abból, amelyiket a legtöbb vonal érinti.
     */
    fun startRideTest(speed: Double, onResult: (started: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            val net = (_network.value as? Load.Success)?.data
                ?: return@launch onResult(false, "A vonalak és megállók még nem töltődtek be")
            val group = (_selection.value as? Selection.StopGroup)?.let { net.group(it.name) }
                ?: net.groups.maxByOrNull { it.lines.size }
                ?: return@launch onResult(false, "Nincs megálló")
            try {
                val candidates = repo.departures(group.ids, count = 6, rangeSec = 12 * 3600, startTime = TimeUtil.nowSec() + 60)
                    .filter { it.isLocal }
                    .take(RIDE_TEST_CANDIDATES)
                // Olyan járat kell, amelyen a felszállás után még legalább néhány megálló van.
                var picked: Pair<Departure, TripDetails>? = null
                for (dep in candidates) {
                    val details = repo.trip(dep.tripId, TimeUtil.serviceDateOf(dep.serviceDay)) ?: continue
                    val board = details.indexOfStop(dep.stopId, null) ?: continue
                    if (details.stops.lastIndex - board >= RIDE_TEST_MIN_STOPS) {
                        picked = dep to details
                        break
                    }
                }
                val (dep, details) = picked
                    ?: return@launch onResult(false, "${group.name}: a következő 12 órában nincs megfelelő indulás")
                if (_selection.value is Selection.Ride) endRide()
                DevClock.set((dep.expected - RIDE_TEST_WAIT_SEC) * 1000, speed)
                DevTools.update {
                    it.copy(
                        simulateVehicles = true,
                        fakeLocation = net.stops[dep.stopId]?.point ?: group.center,
                        rideTest = "${dep.line} → ${dep.headsign}, ${group.name} ${TimeUtil.clock(dep.expected)}",
                    )
                }
                simulator.track(details)
                selectStopGroup(group.name, dep.stopId)
                // Ugyanannál a megállónál a régi idő indulásai maradnának kint.
                retrySelection()
                onResult(true, "${group.name}, indulás ${TimeUtil.clock(dep.expected)}: ${dep.line} → ${dep.headsign}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, e.userMessage())
            }
        }
    }

    /** Leállítja az utazástesztet: valós idő, valódi helyzet és buszok. */
    fun stopRideTest() {
        endRide()
        DevTools.update { it.copy(simulateVehicles = false, fakeLocation = null, rideTest = null) }
        DevClock.reset()
        retrySelection()
    }

    /** Utazásteszt közben az utas a megállóban vár, felszállás után a busszal halad, végül a leszállási megállóban marad. */
    private fun moveRideTestPassenger() {
        if (DevTools.current.rideTest == null) return
        val sel = _selection.value as? Selection.Ride ?: return
        val details = (_trip.value as? Load.Success)?.data?.takeIf { it.tripId == sel.tripId } ?: return
        val vehicle = findVehicle(sel.tripId, sel.vehicleId) ?: return
        val board = details.indexOfStop(sel.boardStopId, null) ?: return
        val alight = _alightStopId.value?.let { details.indexOfStop(it, null) }
        val status = rideStatus(details, board, alight, details.nextStopIndex(TimeUtil.nowSec(), vehicle.nextStopName))
        val point = when (status.phase) {
            RidePhase.WAITING -> return
            RidePhase.RIDING, RidePhase.ALIGHT_NEXT -> GeoPoint(vehicle.lat, vehicle.lon)
            RidePhase.ARRIVED -> details.stops[status.targetIndex].let { GeoPoint(it.lat, it.lon) }
        }
        DevTools.update { it.copy(fakeLocation = point) }
    }

    /** „Leszállok”: befejezi az utazást. */
    fun endRide() {
        if (_selection.value !is Selection.Ride) return
        _alightStopId.value = null
        rideBoarded = false
        clearSelection()
        _camera.tryEmit(CameraCommand.ResetTilt)
    }

    fun setDirection(index: Int) {
        _direction.value = index
    }

    fun clearSelection() {
        stopFollowing()
        _selection.value = Selection.None
    }

    fun toggleFollow() {
        val sel = _selection.value
        val on = !_follow.value
        if (sel is Selection.Ride) {
            val target = rideFollowTarget() ?: return
            _follow.value = on
            _camera.tryEmit(if (on) target else CameraCommand.Follow(null))
            return
        }
        val vehicle = (sel as? Selection.Trip)?.let(::findVehicle) ?: return
        _follow.value = on
        _camera.tryEmit(CameraCommand.Follow(if (on) vehicle.id else null))
    }

    fun stopFollowing() {
        if (_follow.value) {
            _follow.value = false
            _camera.tryEmit(CameraCommand.Follow(null))
        }
    }

    fun toggleFavoriteStop(name: String) {
        viewModelScope.launch { userPrefs.toggleFavoriteStop(name) }
    }

    fun toggleFavoriteDirection(stopIds: List<String>) {
        viewModelScope.launch { userPrefs.toggleFavoriteDirection(stopIds) }
    }

    fun toggleFavoriteLine(routeId: String) {
        viewModelScope.launch { userPrefs.toggleFavoriteLine(routeId) }
    }

    fun setShowIntercity(show: Boolean) {
        viewModelScope.launch { userPrefs.setShowIntercity(show) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPrefs.setThemeMode(mode) }
    }

    fun setNotifyAlerts(on: Boolean) {
        viewModelScope.launch { userPrefs.setNotifyAlerts(on) }
    }

    fun setCheckUpdates(on: Boolean) {
        viewModelScope.launch { userPrefs.setCheckUpdates(on) }
    }

    /**
     * Megnézi, van-e újabb verzió a GitHubon: naponta legfeljebb egyszer, és a kihagyott verzióról nem
     * szól. A [force] (fejlesztői mód) mindkettőt figyelmen kívül hagyja, az [onResult] az eredményt kapja.
     */
    fun checkForUpdate(force: Boolean = false, onResult: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val p = userPrefs.prefs.first()
            // Valós idő, nem a fejlesztői óra: az eltolt idő ne indítson felesleges kéréseket.
            val nowMs = System.currentTimeMillis()
            if (!force && (!p.checkUpdates || nowMs - userPrefs.lastUpdateCheck() < UPDATE_CHECK_INTERVAL_MS)) return@launch
            val release = try {
                updateClient.latest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult?.invoke("Nem sikerült elérni a GitHubot")
                return@launch
            }
            userPrefs.setLastUpdateCheck(nowMs)
            val newer = UpdateClient.isNewer(release.version, BuildConfig.VERSION_NAME)
            if (newer && (force || release.version != p.skippedVersion)) _update.value = release
            onResult?.invoke(if (newer) "Elérhető: ${release.tag}" else "Nincs újabb verzió (legfrissebb: ${release.tag})")
        }
    }

    /** „Később”: a következő napi ellenőrzéskor újra szól. */
    fun dismissUpdate() {
        _update.value = null
    }

    /** „Kihagyom”: erről a verzióról többet nem szól, csak a következőről. */
    fun skipUpdate(release: AppRelease) {
        _update.value = null
        viewModelScope.launch { userPrefs.setSkippedVersion(release.version) }
    }

    fun setDevMode(on: Boolean) {
        if (!on) DevTools.reset()
        viewModelScope.launch { userPrefs.setDevMode(on) }
    }

    /** „Rendben”: a figyelmeztetés a következő indításkor újra megjelenik. */
    fun closeNotice(id: String) {
        closedNotices.update { it + id }
    }

    /** „Ne mutasd újra”: a figyelmeztetés többé nem jelenik meg indításkor. */
    fun hideNotice(id: String) {
        closedNotices.update { it + id }
        val current = (_alerts.value + launchAlerts.value.orEmpty()).map { it.id }.toSet() +
            LaunchNotice.LiveCoverage.id + LaunchNotice.Unofficial.id
        viewModelScope.launch { userPrefs.hideNotice(id, current) }
    }

    /** Fejlesztői mód: az összes figyelmeztetés újra, a mostani forgalmi változásokkal. */
    fun replayNotices() {
        launchAlerts.value = _alerts.value
        closedNotices.value = emptySet()
        viewModelScope.launch { userPrefs.clearHiddenNotices() }
    }

    private fun buildOverlay(
        sel: Selection,
        alightStopId: String?,
        trip: Load<TripDetails>,
        route: Load<RouteDetails>,
        direction: Int,
        net: Network?,
    ): MapOverlay = when (sel) {
        Selection.None -> MapOverlay()
        is Selection.StopGroup -> MapOverlay(
            selectedStops = net?.group(sel.name)?.stops.orEmpty().map {
                val label = listOfNotNull(it.platformLabel, it.directionLabel?.let { d -> "→ $d" }).joinToString("\n")
                MapLabeledPoint(it.point, label, 0, it.id)
            },
        )
        is Selection.Ride -> {
            val details = (trip as? Load.Success)?.data
            if (details == null || details.tripId != sel.tripId || details.stops.isEmpty()) {
                MapOverlay()
            } else {
                val color = LineColors.forLine(details.line, details.isLocal)
                val board = details.indexOfStop(sel.boardStopId, net?.stops?.get(sel.boardStopId)?.name) ?: 0
                val alight = alightStopId?.let { id -> details.indexOfStop(id, null) }?.takeIf { it > board }
                    ?: details.stops.lastIndex
                val boardStop = details.stops[board]
                val alightStop = details.stops[alight]
                MapOverlay(
                    shapes = listOf(MapShape(details.shape, color)),
                    routeStops = details.stops.drop(board).map {
                        MapLabeledPoint(GeoPoint(it.lat, it.lon), mapName(it.name, it.platformLabel), color, it.stopId)
                    },
                    endpoints = listOf(
                        MapLabeledPoint(GeoPoint(boardStop.lat, boardStop.lon), "Felszállás", START_COLOR),
                        MapLabeledPoint(GeoPoint(alightStop.lat, alightStop.lon), "Leszállás", END_COLOR),
                    ),
                )
            }
        }
        is Selection.Trip -> {
            val details = (trip as? Load.Success)?.data
            if (details == null || details.tripId != sel.tripId) {
                MapOverlay()
            } else {
                val color = LineColors.forLine(details.line, details.isLocal)
                MapOverlay(
                    shapes = listOf(MapShape(details.shape, color)),
                    routeStops = details.stops.map {
                        MapLabeledPoint(GeoPoint(it.lat, it.lon), mapName(it.name, it.platformLabel), color, it.stopId)
                    },
                )
            }
        }
        is Selection.Line -> {
            val details = (route as? Load.Success)?.data
            if (details == null || details.line.id != sel.routeId) {
                MapOverlay()
            } else {
                val color = details.line.color
                val mains = details.mainPatterns
                val current = mains.getOrNull(direction) ?: mains.firstOrNull()
                MapOverlay(
                    shapes = mains.map { MapShape(it.shape, color) },
                    routeStops = current?.stops.orEmpty().map {
                        MapLabeledPoint(it.point, mapName(it.name, it.platformLabel), color, it.id)
                    },
                )
            }
        }
        is Selection.Route -> {
            val it = sel.itinerary
            MapOverlay(
                shapes = it.legs.map { leg ->
                    if (leg.mode == LegMode.WALK) {
                        MapShape(leg.shape.ifEmpty { listOf(leg.from.point, leg.to.point) }, WALK_COLOR, dashed = true)
                    } else {
                        MapShape(leg.shape, LineColors.forLine(leg.line.orEmpty(), leg.mode == LegMode.BUS))
                    }
                },
                routeStops = it.transitLegs.flatMap { leg ->
                    val color = LineColors.forLine(leg.line.orEmpty(), leg.mode == LegMode.BUS)
                    listOf(
                        MapLabeledPoint(leg.from.point, mapName(leg.from.name, platformText(listOfNotNull(leg.from.platformCode))), color),
                        MapLabeledPoint(leg.to.point, mapName(leg.to.name, platformText(listOfNotNull(leg.to.platformCode))), color),
                    )
                },
                endpoints = listOf(
                    MapLabeledPoint(it.legs.first().from.point, "Indulás", START_COLOR),
                    MapLabeledPoint(it.legs.last().to.point, "Érkezés", END_COLOR),
                ),
            )
        }
    }

    /** Megállónevek a térképen, állomásokon alattuk a kocsiállással. */
    private fun mapName(name: String, platform: String?) = if (platform == null) name else "$name\n$platform"

    companion object {
        const val VEHICLE_POLL_MS = 5_000L
        const val MIN_VEHICLE_POLL_MS = 1_500L
        const val DEPARTURES_POLL_MS = 20_000L
        const val TRIP_POLL_MS = 15_000L
        const val RIDE_POLL_MS = 10_000L

        /** Ennyi elmozdulás után számol új haladási irányt. */
        const val HEADING_MIN_MOVE_M = 8.0
        const val ALERTS_POLL_MS = 10 * 60_000L
        const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60_000L

        /** Az utazásteszt ennyivel a busz indulása előtt kezdődik. */
        const val RIDE_TEST_WAIT_SEC = 3 * 60

        /** Legalább ennyi megálló legyen a felszállás után, hogy a leszállás előtti jelzés is kipróbálható legyen. */
        const val RIDE_TEST_MIN_STOPS = 4
        const val RIDE_TEST_CANDIDATES = 8
        const val WALK_COLOR = 0xFF607D8B.toInt()
        const val START_COLOR = 0xFF2E7D32.toInt()
        const val END_COLOR = 0xFFC62828.toInt()
    }
}
