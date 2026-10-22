package hu.szfvbus.data

import hu.szfvbus.data.model.GeoPoint
import hu.szfvbus.data.model.Network
import hu.szfvbus.data.model.TripDetails
import hu.szfvbus.data.model.Vehicle
import hu.szfvbus.util.TimeUtil
import hu.szfvbus.util.distanceM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Egy járat busza, amely a járat útvonalán halad a várható megállási idők szerint. A fejlesztői mód
 * használja, amikor nincs valódi busz úton.
 */
class SimulatedTrip(val details: TripDetails) {
    private val path: List<GeoPoint> =
        details.shape.takeIf { it.size >= 2 } ?: details.stops.map { GeoPoint(it.lat, it.lon) }
    private val distances: DoubleArray = DoubleArray(path.size).also { d ->
        for (i in 1 until path.size) d[i] = d[i - 1] + distanceM(path[i - 1], path[i])
    }
    /** Minden megállóhoz az útvonal legközelebbi pontja, visszafelé soha nem lépve. */
    private val stopPoints: IntArray = IntArray(details.stops.size).also { idx ->
        var from = 0
        details.stops.forEachIndexed { i, stop ->
            val p = GeoPoint(stop.lat, stop.lon)
            var best = from
            var bestDist = Double.MAX_VALUE
            for (k in from until path.size) {
                val dist = distanceM(p, path[k])
                if (dist < bestDist) {
                    bestDist = dist
                    best = k
                }
            }
            idx[i] = best
            from = best
        }
    }

    val endsAt: Long get() = details.stops.lastOrNull()?.expectedArrival ?: 0

    fun vehicleAt(now: Long): Vehicle? {
        val stops = details.stops
        if (stops.size < 2 || path.size < 2) return null
        // A buszok indulás előtt néhány percet várnak a végállomáson.
        if (now < stops.first().expectedDeparture - WAIT_AT_START_SEC || now > endsAt + 30) return null
        var i = 0
        while (i < stops.lastIndex && now >= stops[i + 1].expectedArrival) i++
        val atStop = i == stops.lastIndex || now <= stops[i].expectedDeparture
        val point: GeoPoint
        val heading: Float?
        val speed: Double
        if (atStop) {
            point = path[stopPoints[i]]
            heading = headingAt(stopPoints[i].coerceAtMost(path.size - 2))
            speed = 0.0
        } else {
            val leave = stops[i].expectedDeparture
            val arrive = stops[i + 1].expectedArrival
            val fraction = ((now - leave).toDouble() / (arrive - leave).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            val d0 = distances[stopPoints[i]]
            val d1 = distances[stopPoints[i + 1]]
            val target = d0 + (d1 - d0) * fraction
            var k = stopPoints[i]
            while (k < stopPoints[i + 1] - 1 && distances[k + 1] < target) k++
            val k1 = (k + 1).coerceAtMost(path.size - 1)
            val segment = distances[k1] - distances[k]
            val t = if (segment > 0) ((target - distances[k]) / segment).coerceIn(0.0, 1.0) else 0.0
            point = GeoPoint(
                path[k].lat + (path[k1].lat - path[k].lat) * t,
                path[k].lon + (path[k1].lon - path[k].lon) * t,
            )
            heading = headingAt(k.coerceAtMost(path.size - 2))
            speed = (d1 - d0) / (arrive - leave).coerceAtLeast(1)
        }
        val next = if (atStop) stops[i] else stops[i + 1]
        return Vehicle(
            id = "SIM-${details.tripId}",
            label = "SIM ${details.line}",
            lat = point.lat,
            lon = point.lon,
            heading = heading,
            speedMps = speed,
            lastUpdated = now,
            tripId = details.tripId,
            routeId = details.routeId,
            line = details.line,
            headsign = details.headsign,
            isLocal = details.isLocal,
            nextStopName = next.name,
            stopStatus = if (atStop) "STOPPED_AT" else "IN_TRANSIT_TO",
        )
    }

    private fun headingAt(k: Int): Float? {
        if (k < 0 || k + 1 >= path.size) return null
        val a = path[k]
        val b = path[k + 1]
        val dx = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        if (dx == 0.0 && dy == 0.0) return null
        return ((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat()
    }

    companion object {
        const val WAIT_AT_START_SEC = 5 * 60
    }
}

/** Járatokat választ a legforgalmasabb megállók körül, és mindegyikhez elindít egy [SimulatedTrip] buszt. */
class VehicleSimulator(private val repo: TransitRepository) {
    private val mutex = Mutex()
    private val trips = LinkedHashMap<String, SimulatedTrip>()
    private var pickedAt: Long? = null

    suspend fun vehicles(network: Network): List<Vehicle> = mutex.withLock {
        val now = TimeUtil.nowSec()
        if (pickedAt.let { it == null || abs(now - it) > REPICK_SEC }) {
            pickedAt = now
            pickTrips(network, now)
        }
        trips.values.removeAll { it.endsAt < now - 60 && trips.size > 1 }
        trips.values.mapNotNull { it.vehicleAt(now) }
    }

    /** Buszt ad a [details] járathoz (pl. amelyikre valaki felszáll), a legfrissebb megállási időkkel. */
    suspend fun track(details: TripDetails) = mutex.withLock {
        trips[details.tripId] = SimulatedTrip(details)
    }

    suspend fun clear() = mutex.withLock {
        trips.clear()
        pickedAt = null
    }

    private suspend fun pickTrips(network: Network, now: Long) {
        val hubs = network.stops.values.sortedByDescending { it.lines.size }.take(HUB_STOPS).map { it.id }
        try {
            val departures = repo.departures(hubs, count = 10, rangeSec = 3600, startTime = now - 2700)
                .filter { it.isLocal && it.tripId !in trips }
                .distinctBy { it.tripId }
                .take(MAX_TRIPS)
            val details = coroutineScope {
                departures.map { d ->
                    async { runCatching { repo.trip(d.tripId, TimeUtil.serviceDateOf(d.serviceDay)) }.getOrNull() }
                }.awaitAll()
            }
            details.filterNotNull().forEach { trips[it.tripId] = SimulatedTrip(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A következő választáskor újra próbálkozik.
            pickedAt = null
        }
    }

    private companion object {
        const val HUB_STOPS = 10
        const val MAX_TRIPS = 18
        const val REPICK_SEC = 10 * 60
    }
}
