package hu.szfvbus.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A [Queries] lekérdezéseinek válaszformái. Minden mező opcionális: az OTP-séma bőven ad null értékeket, az
// ismeretlen mezőket pedig a Json-beállítás figyelmen kívül hagyja.

@Serializable
class IdDto(val gtfsId: String? = null)

@Serializable
class AgencyRefDto(val gtfsId: String? = null)

@Serializable
class RouteRefDto(
    val gtfsId: String? = null,
    val shortName: String? = null,
    val mode: String? = null,
    val agency: AgencyRefDto? = null,
)

@Serializable
class StopDto(
    val gtfsId: String? = null,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Egy állomás kocsiállásának száma vagy betűjele, pl. "12" a vasútállomáson. */
    val platformCode: String? = null,
)

@Serializable
class GeometryDto(val points: String? = null)

// Hálózat

@Serializable
class NetworkData(val agency: NetworkAgencyDto? = null)

@Serializable
class NetworkAgencyDto(val routes: List<NetworkRouteDto> = emptyList())

@Serializable
class NetworkRouteDto(
    val gtfsId: String,
    val shortName: String? = null,
    val longName: String? = null,
    val color: String? = null,
    val textColor: String? = null,
    val patterns: List<NetworkPatternDto> = emptyList(),
)

@Serializable
class NetworkPatternDto(val stops: List<StopDto> = emptyList())

// Járművek

@Serializable
class VehiclesData(val vehiclePositions: List<VehicleDto> = emptyList())

@Serializable
class VehicleDto(
    val vehicleId: String? = null,
    val label: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val heading: Double? = null,
    val speed: Double? = null,
    val lastUpdated: Long? = null,
    val stopRelationship: StopRelationshipDto? = null,
    val trip: VehicleTripDto? = null,
)

@Serializable
class StopRelationshipDto(val status: String? = null, val stop: StopDto? = null)

@Serializable
class VehicleTripDto(
    val gtfsId: String? = null,
    val tripHeadsign: String? = null,
    val route: RouteRefDto? = null,
)

// Indulások

@Serializable
class StoptimeDto(
    val scheduledDeparture: Int? = null,
    val realtimeDeparture: Int? = null,
    val departureDelay: Int? = null,
    val realtime: Boolean? = null,
    val serviceDay: Long? = null,
    val headsign: String? = null,
    val stop: IdDto? = null,
    val trip: StoptimeTripDto? = null,
)

@Serializable
class StoptimeTripDto(
    val gtfsId: String? = null,
    val tripHeadsign: String? = null,
    val route: RouteRefDto? = null,
)

@Serializable
class DeparturesData(val stops: List<StopDeparturesDto?> = emptyList())

@Serializable
class StopDeparturesDto(
    val gtfsId: String? = null,
    val stoptimesWithoutPatterns: List<StoptimeDto> = emptyList(),
)

// A környék megállói

@Serializable
class AreaStopsData(val stopsByBbox: List<AreaStopDto> = emptyList())

@Serializable
class AreaStopDto(
    val gtfsId: String? = null,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val vehicleMode: String? = null,
    val platformCode: String? = null,
)

// Járat

@Serializable
class TripData(val trip: TripDto? = null)

@Serializable
class TripDto(
    val gtfsId: String? = null,
    val tripHeadsign: String? = null,
    val wheelchairAccessible: String? = null,
    val route: RouteRefDto? = null,
    val pattern: TripPatternDto? = null,
    val stoptimesForDate: List<TripStoptimeDto> = emptyList(),
)

@Serializable
class TripPatternDto(val patternGeometry: GeometryDto? = null)

@Serializable
class TripStoptimeDto(
    val stop: StopDto? = null,
    val scheduledArrival: Int? = null,
    val realtimeArrival: Int? = null,
    val scheduledDeparture: Int? = null,
    val realtimeDeparture: Int? = null,
    val departureDelay: Int? = null,
    val realtime: Boolean? = null,
    val serviceDay: Long? = null,
)

// Vonal

@Serializable
class RouteData(val route: RouteDto? = null)

@Serializable
class RouteDto(
    val gtfsId: String? = null,
    val patterns: List<RoutePatternDto> = emptyList(),
    val alerts: List<AlertDto> = emptyList(),
)

@Serializable
class RoutePatternDto(
    val code: String? = null,
    val headsign: String? = null,
    val directionId: Int? = null,
    val patternGeometry: GeometryDto? = null,
    val stops: List<StopDto> = emptyList(),
    val tripsForDate: List<IdDto> = emptyList(),
)

// Menetrend

@Serializable
class TimetableData(val stops: List<TimetableStopDto?> = emptyList())

@Serializable
class TimetableStopDto(
    val gtfsId: String? = null,
    val stoptimesForServiceDate: List<StoptimesInPatternDto> = emptyList(),
)

@Serializable
class StoptimesInPatternDto(
    val pattern: TimetablePatternDto? = null,
    val stoptimes: List<StoptimeDto> = emptyList(),
)

@Serializable
class TimetablePatternDto(val headsign: String? = null, val route: RouteRefDto? = null)

// Útvonalterv

@Serializable
class PlanData(val plan: PlanDto? = null)

@Serializable
class PlanDto(val itineraries: List<ItineraryDto> = emptyList())

@Serializable
class ItineraryDto(
    val startTime: Long? = null,
    val endTime: Long? = null,
    val walkDistance: Double? = null,
    val legs: List<LegDto> = emptyList(),
)

@Serializable
class LegDto(
    val mode: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val realTime: Boolean? = null,
    val departureDelay: Int? = null,
    val distance: Double? = null,
    val serviceDate: String? = null,
    val headsign: String? = null,
    val from: LegPlaceDto? = null,
    val to: LegPlaceDto? = null,
    val route: RouteRefDto? = null,
    val trip: StoptimeTripDto? = null,
    val intermediateStops: List<StopDto>? = null,
    val legGeometry: GeometryDto? = null,
)

@Serializable
class LegPlaceDto(
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val stop: StopDto? = null,
)

// Figyelmeztetések

@Serializable
class AlertsData(val alerts: List<AlertDto> = emptyList())

@Serializable
class AlertDto(
    val id: String? = null,
    val alertHeaderText: String? = null,
    val alertDescriptionText: String? = null,
    val alertUrl: String? = null,
    val effectiveStartDate: Long? = null,
    val effectiveEndDate: Long? = null,
    val entities: List<AlertEntityDto>? = null,
)

@Serializable
class AlertEntityDto(
    @SerialName("__typename") val typename: String? = null,
    val gtfsId: String? = null,
    val shortName: String? = null,
    val agency: AgencyRefDto? = null,
)

// Photon címkereső

@Serializable
class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
class PhotonFeature(
    val geometry: PhotonGeometry? = null,
    val properties: PhotonProperties? = null,
)

@Serializable
class PhotonGeometry(val coordinates: List<Double> = emptyList())

@Serializable
class PhotonProperties(
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val city: String? = null,
    val district: String? = null,
    @SerialName("osm_value") val osmValue: String? = null,
)
