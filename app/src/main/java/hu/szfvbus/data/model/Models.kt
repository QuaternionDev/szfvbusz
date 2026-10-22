package hu.szfvbus.data.model

import hu.szfvbus.util.LineNameComparator
import hu.szfvbus.util.platformText

data class GeoPoint(val lat: Double, val lon: Double)

data class Line(
    val id: String,
    val shortName: String,
    val longName: String,
    val color: Int,
    val textColor: Int,
    val isLocal: Boolean,
)

data class Stop(
    val id: String,
    /** Megjelenítési név a "Székesfehérvár, " előtag nélkül. */
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Az itt megálló helyi vonalak rövid neve, természetes sorrendben. */
    val lines: List<String>,
    /** Ahová a buszok innen továbbmennek, a leggyakoribb elöl: ez különbözteti meg a peronokat. */
    val towards: List<String> = emptyList(),
    /**
     * A kocsiállás száma vagy betűjele az olyan állomásokon, ahol több azonos nevű van (vasútállomás,
     * autóbusz-állomás).
     */
    val platformCode: String? = null,
) {
    val point get() = GeoPoint(lat, lon)

    /** "12. kocsiállás", vagy null egy átlagos megállónál. */
    val platformLabel: String? get() = platformText(listOfNotNull(platformCode))

    /** "Palotai út felé", vagy null egy olyan peronnál, ahol minden busz útja véget ér. */
    val directionLabel: String? get() = towards.takeIf { it.isNotEmpty() }?.joinToString(" / ", postfix = " felé")
}

/** Az egy irányba vezető peronokról induló járatok. */
data class DirectionGroup(
    val label: String,
    val stopIds: Set<String>,
    val departures: List<Departure>,
    /** A [stopIds] kocsiállás-számai az állomásokon, természetes sorrendben; átlagos megállóknál üres. */
    val platforms: List<String> = emptyList(),
)

/**
 * Irányok szerint bontja egy megálló indulásait, hogy az utca két oldala ne keveredjen.
 * Az azonos irányú peronok egybeolvadnak; a csoportok az első indulásuk szerint rendeződnek,
 * kivéve, hogy a [firstStopId] (a felhasználó által koppintott peron) iránya kerül előre.
 */
fun directionGroups(
    departures: List<Departure>,
    stops: Map<String, Stop>,
    firstStopId: String? = null,
): List<DirectionGroup> {
    fun platformsOf(ids: Collection<String>) =
        ids.mapNotNull { stops[it]?.platformCode }.distinct().sortedWith(LineNameComparator)

    val groups = departures.groupBy { dep ->
        stops[dep.stopId]?.directionLabel ?: "id:${dep.stopId}"
    }.map { (key, list) ->
        val label = if (key.startsWith("id:")) {
            // Nem helyi peron (vagy csak végállomás): a buszai úti céljáról kap nevet.
            list.groupingBy { it.headsign }.eachCount().entries
                .sortedByDescending { it.value }
                .take(2)
                .joinToString(" / ", postfix = " irányába") { it.key }
        } else {
            key
        }
        val ids = list.map { it.stopId }.toSet()
        DirectionGroup(label, ids, list.sortedBy { it.expected }, platformsOf(ids))
    }.sortedBy { it.departures.first().expected }

    if (firstStopId == null) return groups
    val tapped = stops[firstStopId]
    val direction = tapped?.directionLabel
    val chosen = groups.indexOfFirst { firstStopId in it.stopIds || direction != null && it.label == direction }
    return when {
        chosen >= 0 -> listOf(groups[chosen]) + groups.filterIndexed { i, _ -> i != chosen }
        // Arra most nem indul semmi; az irány mégis elöl van, hogy a felhasználó ezt lássa.
        direction != null -> listOf(DirectionGroup(direction, setOf(firstStopId), emptyList(), platformsOf(listOf(firstStopId)))) + groups
        else -> groups
    }
}

/** Az azonos nevű megállók (mindkét irány peronjai) egy helyként, ahogy a realCity is mutatja. */
data class StopGroup(val name: String, val stops: List<Stop>) {
    val ids get() = stops.map { it.id }
    val lines get() = stops.flatMap { it.lines }.distinct().sortedWith(LineNameComparator)
    val center get() = GeoPoint(stops.map { it.lat }.average(), stops.map { it.lon }.average())
}

/** A város statikus hálózati adatai: a helyi vonalak és minden megálló, amelyet érintenek. */
data class Network(
    val lines: List<Line>,
    val stops: Map<String, Stop>,
    val fetchedAt: Long,
) {
    val linesById = lines.associateBy { it.id }
    val linesByName = lines.associateBy { it.shortName }
    val groups: List<StopGroup> = stops.values.groupBy { it.name }
        .map { (name, list) -> StopGroup(name, list.sortedBy { it.id }) }
        .sortedBy { it.name }
    private val groupsByName = groups.associateBy { it.name }

    fun group(name: String): StopGroup? = groupsByName[name]
}

data class Vehicle(
    val id: String,
    /** A rendszám, ahogy a jármű jelenti, pl. "RVY631". */
    val label: String,
    val lat: Double,
    val lon: Double,
    val heading: Float?,
    val speedMps: Double?,
    val lastUpdated: Long,
    val tripId: String?,
    val routeId: String?,
    val line: String,
    val headsign: String,
    val isLocal: Boolean,
    val nextStopName: String?,
    val stopStatus: String?,
)

data class Departure(
    val tripId: String,
    val stopId: String,
    val routeId: String,
    val line: String,
    val headsign: String,
    val scheduled: Long,
    val expected: Long,
    val delaySec: Int,
    val isRealtime: Boolean,
    val isLocal: Boolean,
    val serviceDay: Long,
)

data class TripStop(
    val stopId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val scheduledArrival: Long,
    val expectedArrival: Long,
    val scheduledDeparture: Long,
    val expectedDeparture: Long,
    val delaySec: Int,
    val isRealtime: Boolean,
    /** A kocsiállások egy állomáson, abban a sorrendben, ahogy a busz megáll rajtuk (van, amelyik kettőn is). */
    val platformCodes: List<String> = emptyList(),
) {
    val platformLabel: String? get() = platformText(platformCodes)
}

data class TripDetails(
    val tripId: String,
    val routeId: String,
    val line: String,
    val headsign: String,
    val isLocal: Boolean,
    val wheelchair: Boolean,
    val shape: List<GeoPoint>,
    val stops: List<TripStop>,
) {
    val isRealtime get() = stops.any { it.isRealtime }

    /** Késés a következő, még előttünk lévő megállónál, vagy az utolsó ismert késés. */
    fun currentDelay(now: Long): Int? {
        if (!isRealtime) return null
        val next = stops.firstOrNull { it.expectedDeparture >= now } ?: stops.lastOrNull()
        return next?.delaySec
    }

    /**
     * Annak a megállónak az indexe, amelyhez a busz legközelebb ér, vagy ahol éppen áll; a járat végén null.
     * A jármű saját jelentése felülírja a menetrendet, ha a várthoz közeli megállót nevez meg.
     */
    fun nextStopIndex(now: Long, vehicleNextStop: String?): Int? {
        val byTime = stops.indexOfFirst { it.expectedDeparture >= now }.takeIf { it >= 0 }
        if (vehicleNextStop == null) return byTime
        val around = byTime ?: stops.lastIndex
        return stops.indices
            .filter { stops[it].name == vehicleNextStop && kotlin.math.abs(it - around) <= 3 }
            .minByOrNull { kotlin.math.abs(it - around) }
            ?: byTime
    }

    /** Egy megálló helye ebben a járatban azonosító szerint, összevont peronoknál név szerint. */
    fun indexOfStop(stopId: String, name: String?): Int? =
        stops.indexOfFirst { it.stopId == stopId }.takeIf { it >= 0 }
            ?: name?.let { n -> stops.indexOfFirst { it.name == n }.takeIf { it >= 0 } }
}

enum class RidePhase {
    /** A busz még nem érte el a felszállási megállót. */
    WAITING,
    RIDING,
    /** A következő megállónál száll le az utas. */
    ALIGHT_NEXT,
    ARRIVED,
}

data class RideStatus(
    val phase: RidePhase,
    val nextIndex: Int?,
    /** A megálló, ahol az utas leszáll: a választott, vagy a végállomás. */
    val targetIndex: Int,
    /** A még hátralévő megállók, a célt is beleértve. */
    val stopsLeft: Int,
)

fun rideStatus(trip: TripDetails, boardIndex: Int, alightIndex: Int?, nextIndex: Int?): RideStatus {
    val target = (alightIndex?.takeIf { it > boardIndex } ?: trip.stops.lastIndex).coerceAtLeast(0)
    val phase = when {
        nextIndex == null || nextIndex > target -> RidePhase.ARRIVED
        nextIndex <= boardIndex -> RidePhase.WAITING
        nextIndex == target -> RidePhase.ALIGHT_NEXT
        else -> RidePhase.RIDING
    }
    val from = if (nextIndex == null) target + 1 else maxOf(nextIndex, boardIndex + 1)
    return RideStatus(phase, nextIndex, target, (target - from + 1).coerceAtLeast(0))
}

data class PatternInfo(
    val code: String,
    val headsign: String,
    val directionId: Int,
    val shape: List<GeoPoint>,
    val stops: List<Stop>,
    val tripsToday: Int,
)

data class RouteDetails(
    val line: Line,
    val patterns: List<PatternInfo>,
    val alerts: List<Alert>,
) {
    /** Irányonként egy jellemző útvonalváltozat: amelyiknek ma a legtöbb menete van, azután a leghosszabb. */
    val mainPatterns: List<PatternInfo>
        get() = patterns.groupBy { it.directionId }
            .toSortedMap()
            .values
            .map { list -> list.maxWith(compareBy<PatternInfo> { it.tripsToday }.thenBy { it.stops.size }) }
}

data class NearbyStop(
    val stop: Stop,
    val distanceM: Int,
    val departures: List<Departure>,
)

data class TimetablePattern(
    val routeId: String,
    val line: String,
    val headsign: String,
    val isLocal: Boolean,
    /** Indulási idők az üzemnap kezdete óta eltelt másodpercben (24 órán túl is lehet). */
    val departures: List<Int>,
    val serviceDay: Long,
)

data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val stopGroup: String? = null,
    val detail: String? = null,
    /** A kocsiállás egy állomáson, ahol egy buszos szakasz kezdődik vagy véget ér. */
    val platformCode: String? = null,
) {
    val point get() = GeoPoint(lat, lon)

    /** "Vasútállomás · 12. kocsiállás" */
    val nameWithPlatform: String get() = platformText(listOfNotNull(platformCode))?.let { "$name · $it" } ?: name
}

enum class LegMode { WALK, BUS, COACH, RAIL, OTHER }

data class Leg(
    val mode: LegMode,
    val start: Long,
    val end: Long,
    val from: Place,
    val to: Place,
    val line: String?,
    val routeId: String?,
    val tripId: String?,
    val headsign: String?,
    val isRealtime: Boolean,
    val departureDelaySec: Int,
    val distanceM: Double,
    val intermediateStops: List<String>,
    val shape: List<GeoPoint>,
    val serviceDay: Long?,
)

data class Itinerary(
    val start: Long,
    val end: Long,
    val walkDistanceM: Double,
    val legs: List<Leg>,
) {
    val durationSec get() = end - start
    val transitLegs get() = legs.filter { it.mode != LegMode.WALK }
}

data class Alert(
    val id: String,
    val header: String,
    val description: String,
    val url: String?,
    val start: Long?,
    val end: Long?,
    val lines: List<String>,
)
