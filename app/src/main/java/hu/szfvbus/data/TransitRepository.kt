package hu.szfvbus.data

import hu.szfvbus.data.api.AlertDto
import hu.szfvbus.data.api.AlertsData
import hu.szfvbus.data.api.AreaStopsData
import hu.szfvbus.data.api.DeparturesData
import hu.szfvbus.data.api.GeocoderClient
import hu.szfvbus.data.api.LegPlaceDto
import hu.szfvbus.data.api.MavinformClient
import hu.szfvbus.data.api.MavinformParser
import hu.szfvbus.data.api.NetworkData
import hu.szfvbus.data.api.OtpClient
import hu.szfvbus.data.api.PlanData
import hu.szfvbus.data.api.Queries
import hu.szfvbus.data.api.RouteData
import hu.szfvbus.data.api.StopDto
import hu.szfvbus.data.api.StoptimeDto
import hu.szfvbus.data.api.TimetableData
import hu.szfvbus.data.api.TripData
import hu.szfvbus.data.api.VehiclesData
import hu.szfvbus.data.model.Alert
import hu.szfvbus.data.model.Departure
import hu.szfvbus.data.model.GeoPoint
import hu.szfvbus.data.model.Itinerary
import hu.szfvbus.data.model.Leg
import hu.szfvbus.data.model.LegMode
import hu.szfvbus.data.model.Line
import hu.szfvbus.data.model.NearbyStop
import hu.szfvbus.data.model.Network
import hu.szfvbus.data.model.PatternInfo
import hu.szfvbus.data.model.Place
import hu.szfvbus.data.model.RouteDetails
import hu.szfvbus.data.model.Stop
import hu.szfvbus.data.model.TimetablePattern
import hu.szfvbus.data.model.TripDetails
import hu.szfvbus.data.model.TripStop
import hu.szfvbus.data.model.Vehicle
import hu.szfvbus.util.DevClock
import hu.szfvbus.util.LineNameComparator
import hu.szfvbus.util.Polyline
import hu.szfvbus.util.TimeUtil
import hu.szfvbus.util.cleanStopName
import hu.szfvbus.util.distanceM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

class TransitRepository(
    private val otp: OtpClient,
    private val geocoder: GeocoderClient,
    private val mavinform: MavinformClient,
    private val cacheDir: File,
) {
    private val networkCache = DailyCache(
        file = File(cacheDir, NETWORK_FILE),
        // Abból az időből, amikor a megállóknak még nem volt kocsiállás-számuk.
        replaces = File(cacheDir, "network.json"),
        download = { otp.queryRaw(Queries.NETWORK, vars { put("agency", Queries.CITY_AGENCY) }) },
    ) { body, fetchedAt ->
        buildNetwork(otp.parse(body, NetworkData.serializer()), fetchedAt).also {
            check(it.lines.isNotEmpty()) { "A szerver nem adott vissza vonalakat" }
        }
    }

    private val areaStopsCache = DailyCache(
        file = File(cacheDir, "area-stops.json"),
        download = {
            otp.queryRaw(
                Queries.AREA_STOPS,
                vars {
                    put("minLat", Queries.SW_LAT); put("minLon", Queries.SW_LON)
                    put("maxLat", Queries.NE_LAT); put("maxLon", Queries.NE_LON)
                },
            )
        },
    ) { body, _ -> buildAreaStops(otp.parse(body, AreaStopsData.serializer())) }

    /** A város vonalai és megállói, naponta egyszer frissítve. */
    suspend fun network(forceRefresh: Boolean = false): Network = networkCache.get(forceRefresh)

    /**
     * A város környékének összes buszmegállója, a csak helyközi buszok által használtakkal együtt; naponta
     * egyszer frissítve.
     */
    suspend fun areaStops(): List<Stop> = areaStopsCache.get()

    private fun buildAreaStops(data: AreaStopsData): List<Stop> = data.stopsByBbox.mapNotNull { s ->
        val id = s.gtfsId ?: return@mapNotNull null
        // A vonatoknak külön adatforrásuk van; ez az alkalmazás a buszokról szól.
        if (!id.startsWith("${Queries.BUS_FEED}:") || s.vehicleMode == "RAIL") return@mapNotNull null
        Stop(
            id = id,
            name = cleanStopName(s.name),
            lat = s.lat ?: return@mapNotNull null,
            lon = s.lon ?: return@mapNotNull null,
            lines = emptyList(),
            platformCode = s.platformCode,
        )
    }

    private fun buildNetwork(data: NetworkData, fetchedAt: Long): Network {
        val routes = data.agency?.routes.orEmpty()
        val lines = routes.mapNotNull { r ->
            val name = r.shortName ?: return@mapNotNull null
            Line(
                id = r.gtfsId,
                shortName = name,
                longName = r.longName.orEmpty(),
                color = LineColors.forLine(name),
                textColor = LineColors.WHITE,
                isLocal = true,
            )
        }.sortedWith(compareBy(LineNameComparator) { it.shortName })

        val stopLines = HashMap<String, MutableSet<String>>()
        val stopInfo = HashMap<String, StopDto>()
        // Melyik következő megálló milyen gyakran követ egy peront: ebből lesz a peron iránya.
        val nextStops = HashMap<String, MutableMap<String, Int>>()
        for (route in routes) {
            val name = route.shortName ?: continue
            for (pattern in route.patterns) {
                val names = pattern.stops.map { cleanStopName(it.name) }
                pattern.stops.forEachIndexed { i, stop ->
                    val id = stop.gtfsId ?: return@forEachIndexed
                    stopInfo.putIfAbsent(id, stop)
                    stopLines.getOrPut(id) { HashSet() } += name
                    // Ugyanannak a megállónak a további peronjai kimaradnak.
                    val next = names.drop(i + 1).firstOrNull { it != names[i] && it.isNotEmpty() }
                    if (next != null) {
                        val counts = nextStops.getOrPut(id) { HashMap() }
                        counts[next] = (counts[next] ?: 0) + 1
                    }
                }
            }
        }
        val stops = stopInfo.mapNotNull { (id, dto) ->
            val lat = dto.lat ?: return@mapNotNull null
            val lon = dto.lon ?: return@mapNotNull null
            id to Stop(
                id = id,
                name = cleanStopName(dto.name),
                lat = lat,
                lon = lon,
                lines = stopLines[id].orEmpty().sortedWith(LineNameComparator),
                towards = nextStops[id].orEmpty().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(2)
                    .map { it.key },
                platformCode = dto.platformCode,
            )
        }.toMap()
        return Network(lines, stops, fetchedAt)
    }

    suspend fun vehicles(): List<Vehicle> {
        val data = otp.query(
            Queries.VEHICLES,
            vars {
                put("swLat", Queries.SW_LAT); put("swLon", Queries.SW_LON)
                put("neLat", Queries.NE_LAT); put("neLon", Queries.NE_LON)
            },
            VehiclesData.serializer(),
        )
        return data.vehiclePositions.mapNotNull { v ->
            val route = v.trip?.route
            val mode = route?.mode
            if (mode != null && mode != "BUS" && mode != "COACH") return@mapNotNull null
            Vehicle(
                id = v.vehicleId ?: return@mapNotNull null,
                label = v.label.orEmpty(),
                lat = v.lat ?: return@mapNotNull null,
                lon = v.lon ?: return@mapNotNull null,
                heading = v.heading?.toFloat(),
                speedMps = v.speed,
                lastUpdated = v.lastUpdated ?: 0,
                tripId = v.trip?.gtfsId,
                routeId = route?.gtfsId,
                line = route?.shortName ?: "?",
                headsign = cleanStopName(v.trip?.tripHeadsign),
                isLocal = route?.agency?.gtfsId == Queries.CITY_AGENCY,
                nextStopName = v.stopRelationship?.stop?.name?.let(::cleanStopName),
                stopStatus = v.stopRelationship?.status,
            )
        }
    }

    suspend fun departures(
        stopIds: List<String>,
        count: Int = 12,
        rangeSec: Int = 3 * 3600,
        startTime: Long? = null,
    ): List<Departure> {
        if (stopIds.isEmpty()) return emptyList()
        val data = otp.query(
            Queries.DEPARTURES,
            vars {
                putJsonArray("ids") { stopIds.forEach { add(it) } }
                put("n", count)
                put("range", rangeSec)
                put("start", startTime ?: startTime())
            },
            DeparturesData.serializer(),
        )
        return data.stops.filterNotNull()
            .flatMap { s -> s.stoptimesWithoutPatterns.mapNotNull { it.toDeparture(s.gtfsId.orEmpty()) } }
            .distinctBy { Triple(it.tripId, it.stopId, it.scheduled) }
            .sortedBy { it.expected }
    }

    /**
     * A [here] ponttól [radiusM] méteren belüli megállók a következő indulásaikkal, a legközelebbi elöl.
     * A megállókat a telefon keresi meg a tárolt megállólistákból, így a szerver csak
     * megálló-azonosítókat kap, a felhasználó helyzetét soha.
     */
    suspend fun nearby(here: GeoPoint, radiusM: Int = 700, includeIntercity: Boolean = false): List<NearbyStop> {
        val net = network()
        val candidates = if (includeIntercity) {
            // A csak helyközi buszok által használt megállók nem részei a városi hálózatnak.
            val others = runCatching { areaStops() }.getOrDefault(emptyList()).filter { it.id !in net.stops }
            net.stops.values + others
        } else {
            net.stops.values
        }
        val near = candidates
            .map { it to distanceM(here, it.point) }
            .filter { (_, d) -> d <= radiusM }
            .sortedBy { (_, d) -> d }
            .take(MAX_NEARBY_STOPS)
        if (near.isEmpty()) return emptyList()
        val byStop = departures(near.map { (stop, _) -> stop.id }, count = 6, rangeSec = 2 * 3600).groupBy { it.stopId }
        return near.map { (stop, d) -> NearbyStop(stop, d.roundToInt(), byStop[stop.id].orEmpty()) }
    }

    /**
     * Egy járat megállási idejei. Kifejezett üzemnap nélkül a mai menet számít, kivéve röviddel éjfél után,
     * amikor az előző üzemnap egy járata még úton lehet.
     */
    suspend fun trip(tripId: String, serviceDate: LocalDate? = null): TripDetails? {
        if (serviceDate != null) return fetchTrip(tripId, serviceDate)
        val today = TimeUtil.today()
        val now = TimeUtil.nowSec()
        if (TimeUtil.nowDateTime().hour < 4) {
            val yesterday = fetchTrip(tripId, today.minusDays(1))
            if (yesterday != null && (yesterday.stops.lastOrNull()?.expectedArrival ?: 0) > now - 3600) {
                return yesterday
            }
        }
        val todays = fetchTrip(tripId, today)
        if (todays != null && todays.stops.isNotEmpty()) return todays
        return fetchTrip(tripId, today.minusDays(1)) ?: todays
    }

    private suspend fun fetchTrip(tripId: String, date: LocalDate): TripDetails? {
        val data = otp.query(
            Queries.TRIP,
            vars { put("id", tripId); put("date", TimeUtil.serviceDate(date)) },
            TripData.serializer(),
        )
        val t = data.trip ?: return null
        val raw = t.stoptimesForDate.mapNotNull { st ->
            val stop = st.stop ?: return@mapNotNull null
            val day = st.serviceDay ?: return@mapNotNull null
            val schedDep = st.scheduledDeparture ?: return@mapNotNull null
            val schedArr = st.scheduledArrival ?: schedDep
            TripStop(
                stopId = stop.gtfsId.orEmpty(),
                name = cleanStopName(stop.name),
                lat = stop.lat ?: 0.0,
                lon = stop.lon ?: 0.0,
                scheduledArrival = day + schedArr,
                expectedArrival = day + (st.realtimeArrival ?: schedArr),
                scheduledDeparture = day + schedDep,
                expectedDeparture = day + (st.realtimeDeparture ?: schedDep),
                delaySec = st.departureDelay ?: 0,
                isRealtime = st.realtime == true,
                platformCodes = listOfNotNull(stop.platformCode),
            ).withFakeDelay()
        }
        // Egyes végállomások egy érkező és egy induló peronként szerepelnek, azonos névvel.
        val stops = ArrayList<TripStop>(raw.size)
        for (s in raw) {
            val prev = stops.lastOrNull()
            if (prev != null && prev.name == s.name) {
                stops[stops.lastIndex] = s.copy(
                    scheduledArrival = prev.scheduledArrival,
                    expectedArrival = prev.expectedArrival,
                    // Egy busz egy állomás két kocsiállásán is megállhat (pl. 14-es, majd 11-es).
                    platformCodes = (prev.platformCodes + s.platformCodes).distinct(),
                )
            } else {
                stops += s
            }
        }
        val route = t.route
        return TripDetails(
            tripId = t.gtfsId ?: tripId,
            routeId = route?.gtfsId.orEmpty(),
            line = route?.shortName ?: "?",
            headsign = cleanStopName(t.tripHeadsign),
            isLocal = route?.agency?.gtfsId == Queries.CITY_AGENCY,
            wheelchair = t.wheelchairAccessible == "POSSIBLE",
            shape = Polyline.decode(t.pattern?.patternGeometry?.points),
            stops = stops,
        )
    }

    suspend fun route(routeId: String): RouteDetails? {
        val net = network()
        val data = otp.query(
            Queries.ROUTE,
            vars { put("id", routeId); put("date", TimeUtil.serviceDate(TimeUtil.today())) },
            RouteData.serializer(),
        )
        val r = data.route ?: return null
        val line = net.linesById[routeId] ?: return null
        val patterns = r.patterns.mapNotNull { p ->
            PatternInfo(
                code = p.code ?: return@mapNotNull null,
                headsign = cleanStopName(p.headsign),
                directionId = p.directionId ?: 0,
                shape = Polyline.decode(p.patternGeometry?.points),
                stops = p.stops.mapNotNull { s ->
                    val id = s.gtfsId ?: return@mapNotNull null
                    net.stops[id] ?: Stop(id, cleanStopName(s.name), s.lat ?: 0.0, s.lon ?: 0.0, emptyList(), platformCode = s.platformCode)
                }.fold(ArrayList<Stop>()) { acc, stop ->
                    // Egy végállomás érkező és induló peronja két megállóként jelenik meg, egy névvel.
                    if (acc.lastOrNull()?.name != stop.name) acc += stop
                    acc
                },
                tripsToday = p.tripsForDate.size,
            )
        }
        return RouteDetails(line, patterns, r.alerts.map { it.toAlert(listOf(line.shortName)) })
    }

    suspend fun timetable(stopIds: List<String>, date: LocalDate): List<TimetablePattern> {
        val data = otp.query(
            Queries.TIMETABLE,
            vars {
                putJsonArray("ids") { stopIds.forEach { add(it) } }
                put("date", TimeUtil.serviceDate(date))
            },
            TimetableData.serializer(),
        )
        val merged = LinkedHashMap<Pair<String, String>, MutableList<Int>>()
        val meta = HashMap<Pair<String, String>, Triple<String, Boolean, Long>>()
        for (stop in data.stops.filterNotNull()) for (group in stop.stoptimesForServiceDate) {
            val route = group.pattern?.route ?: continue
            val routeId = route.gtfsId ?: continue
            val key = routeId to cleanStopName(group.pattern.headsign)
            val times = merged.getOrPut(key) { ArrayList() }
            group.stoptimes.forEach { st -> st.scheduledDeparture?.let(times::add) }
            val day = group.stoptimes.firstNotNullOfOrNull { it.serviceDay } ?: 0L
            meta[key] = Triple(route.shortName ?: "?", route.agency?.gtfsId == Queries.CITY_AGENCY, day)
        }
        return merged.mapNotNull { (key, times) ->
            if (times.isEmpty()) return@mapNotNull null
            val (line, local, day) = meta.getValue(key)
            TimetablePattern(key.first, line, key.second, local, times.distinct().sorted(), day)
        }.sortedWith(compareBy<TimetablePattern, String>(LineNameComparator) { it.line }.thenBy { it.headsign })
    }

    suspend fun plan(
        from: Place,
        to: Place,
        dateTime: LocalDateTime,
        arriveBy: Boolean,
        includeIntercity: Boolean,
        leastWalking: Boolean = false,
    ): List<Itinerary> {
        val data = otp.query(
            Queries.PLAN,
            vars {
                putJsonObject("from") { put("lat", from.lat); put("lon", from.lon) }
                putJsonObject("to") { put("lat", to.lat); put("lon", to.lon) }
                put("date", TimeUtil.isoDate(dateTime))
                put("time", TimeUtil.isoTime(dateTime))
                put("arriveBy", arriveBy)
                // Egy perc gyaloglás ennyi perc buszozással ér fel.
                put("walkReluctance", if (leastWalking) LEAST_WALK_RELUCTANCE else DEFAULT_WALK_RELUCTANCE)
                putJsonArray("modes") {
                    addJsonObject { put("mode", "BUS") }
                    if (includeIntercity) addJsonObject { put("mode", "COACH") }
                    addJsonObject { put("mode", "WALK") }
                }
            },
            PlanData.serializer(),
        )
        return data.plan?.itineraries.orEmpty().mapNotNull { itinerary ->
            val legs = itinerary.legs.mapIndexedNotNull { index, leg ->
                val mode = when (leg.mode) {
                    "WALK" -> LegMode.WALK
                    "BUS" -> LegMode.BUS
                    "COACH" -> LegMode.COACH
                    "RAIL" -> LegMode.RAIL
                    else -> LegMode.OTHER
                }
                val serviceDay = leg.serviceDate?.let { d ->
                    runCatching { LocalDate.parse(d).atStartOfDay(TimeUtil.ZONE).toEpochSecond() }.getOrNull()
                }
                Leg(
                    mode = mode,
                    start = (leg.startTime ?: return@mapIndexedNotNull null) / 1000,
                    end = (leg.endTime ?: return@mapIndexedNotNull null) / 1000,
                    from = leg.from.toPlace(if (index == 0) from else null),
                    to = leg.to.toPlace(if (index == itinerary.legs.lastIndex) to else null),
                    line = leg.route?.shortName,
                    routeId = leg.route?.gtfsId,
                    tripId = leg.trip?.gtfsId,
                    headsign = cleanStopName(leg.headsign ?: leg.trip?.tripHeadsign).ifEmpty { null },
                    isRealtime = leg.realTime == true,
                    departureDelaySec = leg.departureDelay ?: 0,
                    distanceM = leg.distance ?: 0.0,
                    intermediateStops = leg.intermediateStops.orEmpty().map { s -> cleanStopName(s.name) },
                    shape = Polyline.decode(leg.legGeometry?.points),
                    serviceDay = serviceDay,
                )
            }
            if (legs.isEmpty()) return@mapNotNull null
            Itinerary(
                start = (itinerary.startTime ?: return@mapNotNull null) / 1000,
                end = (itinerary.endTime ?: return@mapNotNull null) / 1000,
                walkDistanceM = itinerary.walkDistance ?: 0.0,
                legs = legs,
            )
        }.let { list ->
            when {
                // Elöl a legkevesebb séta; a hasonló sétájúak (100 m-en belül) a szokásos időrendben.
                leastWalking && arriveBy -> list.sortedWith(
                    compareBy<Itinerary> { (it.walkDistanceM / 100).toInt() }.thenByDescending { it.start },
                )
                leastWalking -> list.sortedWith(compareBy<Itinerary> { (it.walkDistanceM / 100).toInt() }.thenBy { it.end })
                // Az OTP a hosszú sétát adja elöl; az utasok a legkorábbi érkezést (vagy a legkésőbbi
                // indulást) várják felül.
                arriveBy -> list.sortedByDescending { it.start }
                else -> list.sortedBy { it.end }
            }
        }
    }

    private val alertsMutex = Mutex()
    @Volatile private var alertsCache: Pair<Long, List<Alert>>? = null

    /**
     * A hírek szövege az oldaluk útvonala szerint. A lemezen is megmarad, így a háttér-ellenőrzés, amely
     * gyakran friss folyamatban indul, csak a listát olvassa be, hacsak egy hír nem változott.
     */
    private var notices: Map<String, CachedNotice>? = null
    private val noticesFile get() = File(cacheDir, "notices.json")

    /**
     * A városi vonalak forgalmi változásai: az üzemeltető MÁVINFORM-hírei, valamint a menetrendi adatok
     * figyelmeztetései. Néhány percig tárolva; a két forrás egymástól függetlenül is hibázhat.
     */
    suspend fun alerts(forceRefresh: Boolean = false): List<Alert> {
        val fetched = alertsMutex.withLock {
            val now = System.currentTimeMillis()
            alertsCache?.takeIf { !forceRefresh && now - it.first < ALERTS_MAX_AGE_MS }?.second ?: run {
                val (notices, feed) = coroutineScope {
                    val n = async { runCatching { mavinformAlerts() } }
                    val f = async { runCatching { feedAlerts() } }
                    n.await() to f.await()
                }
                if (notices.isFailure && feed.isFailure) {
                    alertsCache?.let { return@withLock it.second }
                    throw notices.exceptionOrNull()!!
                }
                val list = (notices.getOrDefault(emptyList()) + feed.getOrDefault(emptyList()))
                    .distinctBy { it.id }
                    .sortedByDescending { it.start ?: 0 }
                alertsCache = now to list
                list
            }
        }
        val testLine = DevTools.current.testAlertLine ?: return fetched
        return listOf(testAlert(testLine)) + fetched
    }

    /** Az [alertsMutex] zárolása alatt hívandó. */
    private suspend fun mavinformAlerts(): List<Alert> {
        val known = network().lines.map { it.shortName }
        val items = mavinform.list()
        val cached = notices ?: readNotices()
        val read = coroutineScope {
            items.map { item ->
                async {
                    val old = cached[item.path]
                    if (old?.changed == item.lastChanged) return@async old
                    // Az éppen nem olvasható hír megtartja a korábbi szövegét.
                    runCatching { mavinform.body(item.path) }.getOrNull()?.let { CachedNotice(item.lastChanged, it) } ?: old
                }
            }.awaitAll()
        }
        // A listáról lekerült híreket elfelejtjük.
        val current = items.zip(read).mapNotNull { (item, notice) -> notice?.let { item.path to it } }.toMap()
        notices = current
        if (current != cached) {
            withContext(Dispatchers.IO) { runCatching { noticesFile.writeText(cacheJson.encodeToString(current)) } }
        }
        val bodies = read.map { it?.body }
        return items.zip(bodies).mapNotNull { (item, body) ->
            if (!MavinformParser.isAboutTheCity(item.title, body.orEmpty())) return@mapNotNull null
            Alert(
                id = "mavinform:${item.path}",
                header = item.title,
                description = body?.let(MavinformParser::displayHtml).orEmpty(),
                url = MavinformClient.BASE_URL + item.path,
                start = item.validFrom,
                end = item.validTo,
                lines = MavinformParser.affectedLines(body.orEmpty(), known).sortedWith(LineNameComparator),
            )
        }
    }

    private suspend fun readNotices(): Map<String, CachedNotice> = withContext(Dispatchers.IO) {
        runCatching { cacheJson.decodeFromString<Map<String, CachedNotice>>(noticesFile.readText()) }.getOrDefault(emptyMap())
    }

    private fun testAlert(line: String) = Alert(
        id = DevTools.TEST_ALERT_ID,
        header = "Teszt: ideiglenes forgalmi változás a $line-es vonalon",
        description = "<p><strong>Fejlesztői teszt.</strong> Munkálatok miatt az autóbuszok terelve közlekednek.</p>" +
            "<p><strong>Kimaradó megálló:</strong><br>• Teszt megálló</p>",
        url = null,
        start = TimeUtil.nowSec() - 3600,
        end = TimeUtil.nowSec() + 7 * 24 * 3600,
        lines = listOf(line),
    )

    private suspend fun feedAlerts(): List<Alert> {
        val net = network()
        val data = otp.query(Queries.ALERTS, JsonObject(emptyMap()), AlertsData.serializer())
        return data.alerts.mapNotNull { a ->
            val entities = a.entities.orEmpty()
            val lines = entities.filter { it.typename == "Route" && it.agency?.gtfsId == Queries.CITY_AGENCY }
                .mapNotNull { it.shortName }
            val relevant = lines.isNotEmpty() || entities.any {
                (it.typename == "Agency" && it.gtfsId == Queries.CITY_AGENCY) ||
                    (it.typename == "Stop" && it.gtfsId in net.stops)
            }
            if (relevant) a.toAlert(lines.sortedWith(LineNameComparator)) else null
        }
    }

    suspend fun geocode(query: String): List<Place> =
        geocoder.search(query).mapNotNull { f ->
            val coords = f.geometry?.coordinates ?: return@mapNotNull null
            if (coords.size < 2) return@mapNotNull null
            val p = f.properties
            val streetLine = listOfNotNull(p?.street, p?.housenumber).joinToString(" ")
            val name = p?.name ?: streetLine.ifEmpty { null } ?: return@mapNotNull null
            val detail = listOfNotNull(
                streetLine.takeIf { it.isNotEmpty() && it != name },
                p?.district,
                p?.city,
            ).distinct().joinToString(", ")
            Place(name = name, lat = coords[1], lon = coords[0], detail = detail.ifEmpty { null })
        }

    private fun StoptimeDto.toDeparture(fallbackStopId: String): Departure? {
        val trip = trip ?: return null
        val day = serviceDay ?: return null
        val scheduled = scheduledDeparture ?: return null
        val expected = realtimeDeparture ?: scheduled
        val route = trip.route
        return Departure(
            tripId = trip.gtfsId ?: return null,
            stopId = stop?.gtfsId ?: fallbackStopId,
            routeId = route?.gtfsId.orEmpty(),
            line = route?.shortName ?: "?",
            headsign = cleanStopName(headsign ?: trip.tripHeadsign),
            scheduled = day + scheduled,
            expected = day + expected,
            delaySec = departureDelay ?: (expected - scheduled),
            isRealtime = realtime == true,
            isLocal = route?.agency?.gtfsId == Queries.CITY_AGENCY,
            serviceDay = day,
        ).withFakeDelay()
    }

    /** Az OTP indulási listái a szerver idejétől kezdődnek, ha nem kap más kezdőidőt. */
    private fun startTime(): Long = if (DevClock.isShifted) TimeUtil.nowSec() else 0L

    private fun Departure.withFakeDelay(): Departure {
        val d = DevTools.current.fakeDelayMin * 60
        return if (d == 0) this else copy(expected = expected + d, delaySec = delaySec + d, isRealtime = true)
    }

    private fun TripStop.withFakeDelay(): TripStop {
        val d = DevTools.current.fakeDelayMin * 60
        return if (d == 0) {
            this
        } else {
            copy(
                expectedArrival = expectedArrival + d,
                expectedDeparture = expectedDeparture + d,
                delaySec = delaySec + d,
                isRealtime = true,
            )
        }
    }

    private fun LegPlaceDto?.toPlace(override: Place?): Place {
        if (override != null) return override
        val name = cleanStopName(this?.name)
        return Place(
            name = name,
            lat = this?.lat ?: 0.0,
            lon = this?.lon ?: 0.0,
            stopGroup = if (this?.stop?.gtfsId != null) name else null,
            platformCode = this?.stop?.platformCode,
        )
    }

    private fun AlertDto.toAlert(lines: List<String>) = Alert(
        id = id.orEmpty(),
        header = alertHeaderText.orEmpty().trim(),
        description = alertDescriptionText.orEmpty().trim(),
        url = alertUrl,
        start = effectiveStartDate,
        end = effectiveEndDate,
        lines = lines,
    )

    private inline fun vars(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(block)

    companion object {
        /** A fájl 2. változatában a megállók kocsiállás-száma is benne van. */
        const val NETWORK_FILE = "network-v2.json"
        private const val DEFAULT_WALK_RELUCTANCE = 2.0
        private const val LEAST_WALK_RELUCTANCE = 8.0
        private const val ALERTS_MAX_AGE_MS = 5L * 60 * 1000
        private const val MAX_NEARBY_STOPS = 60
        private val cacheJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class CachedNotice(val changed: String, val body: String)

/**
 * Ritkán változó adat, például a megállók: a memóriából, aztán a [file] fájlból, amíg egy napnál fiatalabb,
 * végül a szerverről. Ha a frissítés nem sikerül, hiba helyett a régebbi példány marad.
 */
private class DailyCache<T : Any>(
    private val file: File,
    private val replaces: File? = null,
    private val download: suspend () -> String,
    private val parse: (body: String, fetchedAt: Long) -> T,
) {
    private val mutex = Mutex()
    private var value: T? = null
    private var fetchedAt = 0L

    suspend fun get(forceRefresh: Boolean = false): T = mutex.withLock {
        val now = System.currentTimeMillis()
        val current = value ?: readFile()
        if (current != null && !forceRefresh && now - fetchedAt < MAX_AGE_MS) {
            value = current
            return current
        }
        try {
            val body = download()
            val fresh = withContext(Dispatchers.Default) { parse(body, now) }
            withContext(Dispatchers.IO) { runCatching { file.writeText(body) } }
            value = fresh
            fetchedAt = now
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            current?.also { value = it } ?: throw e
        }
    }

    private suspend fun readFile(): T? = withContext(Dispatchers.IO) {
        replaces?.delete()
        runCatching {
            if (!file.exists()) return@runCatching null
            val at = file.lastModified()
            parse(file.readText(), at).also { fetchedAt = at }
        }.getOrNull()
    }

    private companion object {
        const val MAX_AGE_MS = 24L * 3600 * 1000
    }
}
