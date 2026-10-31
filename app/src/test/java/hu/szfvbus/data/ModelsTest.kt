package hu.szfvbus.data

import hu.szfvbus.data.model.Alert
import hu.szfvbus.data.model.Departure
import hu.szfvbus.data.model.LaunchNotice
import hu.szfvbus.data.model.Line
import hu.szfvbus.data.model.Network
import hu.szfvbus.data.model.PatternInfo
import hu.szfvbus.data.model.RidePhase
import hu.szfvbus.data.model.RouteDetails
import hu.szfvbus.data.model.Stop
import hu.szfvbus.data.model.StopGroup
import hu.szfvbus.data.model.TripDetails
import hu.szfvbus.data.model.TripStop
import hu.szfvbus.data.model.directionGroups
import hu.szfvbus.data.model.earlyLoginSuspected
import hu.szfvbus.data.model.noticeQueue
import hu.szfvbus.data.model.rideStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private val line = Line("r1", "42", "Csapó utca - Börgönd", 0, 0, true)

    private fun stop(id: String, name: String) = Stop(id, name, 47.19, 18.41, listOf("42"))

    @Test
    fun groupsPlatformsByName() {
        val net = Network(
            lines = listOf(line),
            stops = listOf(stop("a_1", "Csapó utca"), stop("a_2", "Csapó utca"), stop("b_1", "Börgönd"))
                .associateBy { it.id },
            fetchedAt = 0,
        )
        assertEquals(2, net.groups.size)
        assertEquals(listOf("a_1", "a_2"), net.group("Csapó utca")?.ids)
        assertNull(net.group("Nincs ilyen"))
    }

    @Test
    fun mainPatternPrefersMostTripsThenLongest() {
        fun pattern(code: String, dir: Int, trips: Int, stops: Int) = PatternInfo(
            code, "x", dir, emptyList(), List(stops) { stop("s$it", "S$it") }, trips,
        )
        val details = RouteDetails(
            line,
            listOf(
                pattern("p1", 1, trips = 5, stops = 10),
                pattern("p2", 1, trips = 2, stops = 20),
                pattern("p3", 0, trips = 3, stops = 8),
                pattern("p4", 0, trips = 3, stops = 12),
            ),
            emptyList(),
        )
        assertEquals(listOf("p4", "p1"), details.mainPatterns.map { it.code })
    }

    @Test
    fun currentDelayComesFromNextStop() {
        fun ts(dep: Long, delay: Int, rt: Boolean) =
            TripStop("s", "S", 0.0, 0.0, dep, dep + delay, dep, dep + delay, delay, rt)
        val trip = TripDetails(
            "t", "r1", "42", "Börgönd", true, false, emptyList(),
            listOf(ts(100, 60, true), ts(200, 120, true), ts(300, 180, true)),
        )
        assertEquals(120, trip.currentDelay(now = 250))
        assertEquals(180, trip.currentDelay(now = 1000))

        val scheduled = trip.copy(stops = trip.stops.map { it.copy(isRealtime = false) })
        assertNull(scheduled.currentDelay(now = 250))
    }

    private fun departure(stopId: String, expected: Long, headsign: String = "Börgönd") =
        Departure("t$expected", stopId, "r1", "42", headsign, expected, expected, 0, false, true, 0)

    @Test
    fun splitsDeparturesByDirection() {
        val stops = listOf(
            stop("a_1", "Csapó utca").copy(towards = listOf("Palotai út")),
            stop("a_2", "Csapó utca").copy(towards = listOf("Piac tér", "Távirda utca")),
            stop("a_3", "Csapó utca").copy(towards = listOf("Palotai út")),
        ).associateBy { it.id }
        val groups = directionGroups(
            listOf(
                departure("a_2", 100),
                departure("a_1", 200),
                departure("a_3", 150),
                departure("x_9", 300, headsign = "Seregélyes"),
            ),
            stops,
        )
        assertEquals(
            listOf("Piac tér / Távirda utca felé", "Palotai út felé", "Seregélyes irányába"),
            groups.map { it.label },
        )
        // Az azonos irányba vezető peronok együtt látszanak, időrendben.
        assertEquals(setOf("a_1", "a_3"), groups[1].stopIds)
        assertEquals(listOf(150L, 200L), groups[1].departures.map { it.expected })
    }

    private val csapoUtca = listOf(
        stop("a_1", "Csapó utca").copy(towards = listOf("Palotai út")),
        stop("a_2", "Csapó utca").copy(towards = listOf("Piac tér", "Távirda utca")),
        stop("a_3", "Csapó utca").copy(towards = listOf("Palotai út")),
    ).associateBy { it.id }

    @Test
    fun tappedDirectionComesFirst() {
        val departures = listOf(departure("a_2", 100), departure("a_1", 200), departure("x_9", 300, headsign = "Seregélyes"))
        assertEquals(
            listOf("Palotai út felé", "Piac tér / Távirda utca felé", "Seregélyes irányába"),
            directionGroups(departures, csapoUtca, firstStopId = "a_3").map { it.label },
        )
        assertEquals(
            listOf("Seregélyes irányába", "Piac tér / Távirda utca felé", "Palotai út felé"),
            directionGroups(departures, csapoUtca, firstStopId = "x_9").map { it.label },
        )
        // A koppintott peronról most nem indul semmi: az iránya mégis elöl van, üresen.
        val quiet = directionGroups(listOf(departure("a_2", 100)), csapoUtca, firstStopId = "a_1")
        assertEquals(listOf("Palotai út felé", "Piac tér / Távirda utca felé"), quiet.map { it.label })
        assertTrue(quiet.first().departures.isEmpty())
    }

    @Test
    fun directionsKnowTheirPlatforms() {
        val station = listOf(
            stop("v_11", "Vasútállomás").copy(towards = listOf("Palotai út"), platformCode = "11"),
            stop("v_12", "Vasútállomás").copy(towards = listOf("Palotai út"), platformCode = "12"),
            stop("v_2", "Vasútállomás").copy(towards = listOf("Piac tér"), platformCode = "2"),
        ).associateBy { it.id }
        val groups = directionGroups(listOf(departure("v_12", 100), departure("v_11", 200), departure("v_2", 300)), station)
        assertEquals(listOf("11", "12"), groups[0].platforms)
        assertEquals(listOf("2"), groups[1].platforms)
        assertEquals("12.\u00A0kocsiállás", station.getValue("v_12").platformLabel)
        assertTrue(directionGroups(listOf(departure("a_1", 100)), csapoUtca).single().platforms.isEmpty())
    }

    @Test
    fun favoriteDirectionCoversAllItsPlatforms() {
        val group = StopGroup(
            "Csapó utca",
            (csapoUtca.values + stop("a_4", "Csapó utca")).sortedBy { it.id },
        )
        // Az azonos irányú peronok együtt; ha csak az egyikről indul busz, a többi is hozzá tartozik.
        assertEquals(listOf("a_1", "a_3"), group.sameDirection(setOf("a_3")))
        assertEquals(listOf("a_2"), group.sameDirection(listOf("a_2")))
        // Irány nélküli (végállomási) peron csak önmaga.
        assertEquals(listOf("a_4"), group.sameDirection(listOf("a_4")))
    }

    @Test
    fun liveCoverageNoticeAlwaysComesFirst() {
        fun alert(id: String) = Alert(id, "Hír $id", "", null, null, null, emptyList())
        val alerts = listOf(alert("a"), alert("b"), alert("c"))
        assertEquals(
            listOf(LaunchNotice.LiveCoverage.id, LaunchNotice.Unofficial.id, "a", "b", "c"),
            noticeQueue(alerts, hidden = emptySet(), closed = emptySet()).map { it.id },
        )
        // Az elrejtett és a most bezárt figyelmeztetés kimarad, a sorrend nem változik.
        assertEquals(
            listOf(LaunchNotice.LiveCoverage.id, "c"),
            noticeQueue(alerts, setOf("a", LaunchNotice.Unofficial.id), setOf("b")).map { it.id },
        )
        // Csak a nem hivatalos alkalmazásról szóló jelenik meg egyszer.
        assertEquals(
            listOf(LaunchNotice.Unofficial.id),
            noticeQueue(emptyList(), emptySet(), emptySet()).filter { it.showOnce }.map { it.id },
        )
        val rest = noticeQueue(alerts, hidden = setOf(LaunchNotice.LiveCoverage.id, LaunchNotice.Unofficial.id), closed = emptySet())
        assertEquals(listOf("a", "b", "c"), rest.map { it.id })
        assertEquals(alert("a"), (rest.first() as LaunchNotice.TrafficChange).alert)
    }

    private fun rideTrip(): TripDetails {
        fun ts(i: Int) = TripStop("s$i", "S$i", 0.0, 0.0, i * 100L, i * 100L, i * 100L, i * 100L, 0, true)
        return TripDetails("t", "r1", "42", "Börgönd", true, false, emptyList(), List(6) { ts(it) })
    }

    @Test
    fun nextStopFollowsTheBusWhenItReportsOne() {
        val trip = rideTrip()
        assertEquals(2, trip.nextStopIndex(now = 150, vehicleNextStop = null))
        // A busz késik, és még S1 felé tart.
        assertEquals(1, trip.nextStopIndex(now = 150, vehicleNextStop = "S1"))
        // A várt helyzettől messze lévő megálló nem számít.
        assertEquals(1, trip.nextStopIndex(now = 50, vehicleNextStop = "S5"))
        assertNull(trip.nextStopIndex(now = 1000, vehicleNextStop = null))
        assertEquals(3, trip.indexOfStop("x", "S3"))
        assertEquals(4, trip.indexOfStop("s4", null))
    }

    @Test
    fun suspiciouslyEarlyBusIsFlagged() {
        val live = departure("a_1", 100).copy(isRealtime = true)
        assertFalse(live.copy(delaySec = -5 * 60).earlyLoginSuspected)
        assertTrue(live.copy(delaySec = -45 * 60).earlyLoginSuspected)
        // A menetrend szerinti időben nincs mit gyanúsítani.
        assertFalse(live.copy(delaySec = -45 * 60, isRealtime = false).earlyLoginSuspected)
        val trip = rideTrip()
        assertFalse(trip.earlyLoginSuspected)
        assertTrue(trip.copy(stops = trip.stops.map { it.copy(delaySec = -46 * 60) }).earlyLoginSuspected)
    }

    @Test
    fun ridePhasesFollowTheBus() {
        val trip = rideTrip()
        // Felszállás S1-ben, leszállás S4-ben.
        assertEquals(RidePhase.WAITING, rideStatus(trip, 1, 4, nextIndex = 0).phase)
        assertEquals(RidePhase.WAITING, rideStatus(trip, 1, 4, nextIndex = 1).phase)
        val riding = rideStatus(trip, 1, 4, nextIndex = 2)
        assertEquals(RidePhase.RIDING, riding.phase)
        assertEquals(3, riding.stopsLeft)
        assertEquals(RidePhase.ALIGHT_NEXT, rideStatus(trip, 1, 4, nextIndex = 4).phase)
        assertEquals(RidePhase.ARRIVED, rideStatus(trip, 1, 4, nextIndex = 5).phase)
        assertEquals(RidePhase.ARRIVED, rideStatus(trip, 1, 4, nextIndex = null).phase)
        // Választott megálló nélkül az utazás a vonal végéig tart.
        val toEnd = rideStatus(trip, 1, null, nextIndex = 2)
        assertEquals(5, toEnd.targetIndex)
        assertEquals(4, toEnd.stopsLeft)
    }
}
