package hu.szfvbus.data.api

/** Az OTP2 végpontnak küldött GraphQL-dokumentumok. A mezőnevek az OTP „GTFS” GraphQL-sémáját követik. */
object Queries {
    /** A buszok adatforrása a MÁV adataiban; a vonatoké egy másik. */
    const val BUS_FEED = "hkir"

    /** A székesfehérvári helyi buszok üzemeltetője a MÁV „hkir” adatforrásában. */
    const val CITY_AGENCY = "hkir:hkir_V-14827"

    // A város és az elővárosok befoglaló téglalapja, az élő járművekhez és a környék megállóihoz.
    const val SW_LAT = 47.11
    const val SW_LON = 18.28
    const val NE_LAT = 47.27
    const val NE_LON = 18.58

    val NETWORK = """
        query Network(${'$'}agency: String!) {
          agency(id: ${'$'}agency) {
            routes {
              gtfsId shortName longName color textColor
              patterns { stops { gtfsId name lat lon platformCode } }
            }
          }
        }
    """.trimIndent()

    val VEHICLES = """
        query Vehicles(${'$'}swLat: Float!, ${'$'}swLon: Float!, ${'$'}neLat: Float!, ${'$'}neLon: Float!) {
          vehiclePositions(swLat: ${'$'}swLat, swLon: ${'$'}swLon, neLat: ${'$'}neLat, neLon: ${'$'}neLon) {
            vehicleId label lat lon heading speed lastUpdated
            stopRelationship { status stop { name } }
            trip { gtfsId tripHeadsign route { gtfsId shortName mode agency { gtfsId } } }
          }
        }
    """.trimIndent()

    private const val STOPTIME_FIELDS = """
        scheduledDeparture realtimeDeparture departureDelay realtime serviceDay headsign
        stop { gtfsId }
        trip { gtfsId tripHeadsign route { gtfsId shortName agency { gtfsId } } }
    """

    val DEPARTURES = """
        query Departures(${'$'}ids: [String]!, ${'$'}n: Int!, ${'$'}range: Int!, ${'$'}start: Long!) {
          stops(ids: ${'$'}ids) {
            gtfsId
            stoptimesWithoutPatterns(startTime: ${'$'}start, numberOfDepartures: ${'$'}n, timeRange: ${'$'}range, omitNonPickups: true) {
              $STOPTIME_FIELDS
            }
          }
        }
    """.trimIndent()

    /** Minden megálló a város körül, a helyköziek is: a felhasználóhoz közelieket a telefon keresi meg. */
    val AREA_STOPS = """
        query AreaStops(${'$'}minLat: Float!, ${'$'}minLon: Float!, ${'$'}maxLat: Float!, ${'$'}maxLon: Float!) {
          stopsByBbox(minLat: ${'$'}minLat, minLon: ${'$'}minLon, maxLat: ${'$'}maxLat, maxLon: ${'$'}maxLon) {
            gtfsId name lat lon vehicleMode platformCode
          }
        }
    """.trimIndent()

    val TRIP = """
        query Trip(${'$'}id: String!, ${'$'}date: String!) {
          trip(id: ${'$'}id) {
            gtfsId tripHeadsign wheelchairAccessible
            route { gtfsId shortName agency { gtfsId } }
            pattern { patternGeometry { points } }
            stoptimesForDate(serviceDate: ${'$'}date) {
              stop { gtfsId name lat lon platformCode }
              scheduledArrival realtimeArrival scheduledDeparture realtimeDeparture
              departureDelay realtime serviceDay
            }
          }
        }
    """.trimIndent()

    val ROUTE = """
        query Route(${'$'}id: String!, ${'$'}date: String!) {
          route(id: ${'$'}id) {
            gtfsId
            patterns {
              code headsign directionId
              patternGeometry { points }
              stops { gtfsId name lat lon }
              tripsForDate(serviceDate: ${'$'}date) { gtfsId }
            }
            alerts { id alertHeaderText alertDescriptionText alertUrl effectiveStartDate effectiveEndDate }
          }
        }
    """.trimIndent()

    val TIMETABLE = """
        query Timetable(${'$'}ids: [String]!, ${'$'}date: String!) {
          stops(ids: ${'$'}ids) {
            gtfsId
            stoptimesForServiceDate(date: ${'$'}date, omitNonPickups: true) {
              pattern { headsign route { gtfsId shortName agency { gtfsId } } }
              stoptimes { scheduledDeparture serviceDay }
            }
          }
        }
    """.trimIndent()

    val PLAN = """
        query Plan(${'$'}from: InputCoordinates!, ${'$'}to: InputCoordinates!, ${'$'}date: String!, ${'$'}time: String!,
                   ${'$'}arriveBy: Boolean!, ${'$'}modes: [TransportMode], ${'$'}walkReluctance: Float!) {
          plan(from: ${'$'}from, to: ${'$'}to, date: ${'$'}date, time: ${'$'}time, arriveBy: ${'$'}arriveBy,
               numItineraries: 6, transportModes: ${'$'}modes, walkReluctance: ${'$'}walkReluctance) {
            itineraries {
              startTime endTime walkDistance
              legs {
                mode startTime endTime realTime departureDelay distance serviceDate headsign
                from { name lat lon stop { gtfsId platformCode } }
                to { name lat lon stop { gtfsId platformCode } }
                route { gtfsId shortName agency { gtfsId } }
                trip { gtfsId tripHeadsign }
                intermediateStops { name }
                legGeometry { points }
              }
            }
          }
        }
    """.trimIndent()

    val ALERTS = """
        query Alerts {
          alerts(feeds: ["$BUS_FEED"]) {
            id alertHeaderText alertDescriptionText alertUrl effectiveStartDate effectiveEndDate
            entities {
              __typename
              ... on Route { gtfsId shortName agency { gtfsId } }
              ... on Agency { gtfsId }
              ... on Stop { gtfsId }
            }
          }
        }
    """.trimIndent()
}
