package hu.szfvbus.data

import hu.szfvbus.data.api.GeocoderClient
import hu.szfvbus.data.api.MavinformClient
import hu.szfvbus.data.api.OtpClient
import hu.szfvbus.data.model.GeoPoint
import hu.szfvbus.data.model.LegMode
import hu.szfvbus.data.model.Place
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * A tárolót kitalált tesztadatokon (src/test/resources/fixtures) futtatja: egy kis város vonalain és
 * megállóin, a MÁV Plusz és a MÁVINFORM válaszainak szerkezetével. Így a DTO-k és a leképezés hálózat nélkül
 * ellenőrződnek.
 */
class TransitRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val requests = mutableListOf<String>()
    private lateinit var repo: TransitRepository

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name.json")) { "missing fixture $name" }.readText()

    private fun page(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/mavinform/$name.html")) { "missing page $name" }.readText()

    private val fakeServer = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host == "www.mavcsoport.hu") {
            // A MÁVINFORM lapjai: a lista, aztán hírenként egy oldal.
            val slug = request.url.pathSegments.drop(1).firstOrNull()
            requests += request.url.toString()
            return@Interceptor Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(page(slug ?: "list").toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        requests += body
        val operation = Regex("query (\\w+)").find(body)?.groupValues?.get(1)
        val payload = when (operation) {
            null -> """{"features":[]}"""
            else -> fixture(operation.lowercase())
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(payload.toResponseBody("application/json".toMediaType()))
            .build()
    }

    @Before
    fun setUp() {
        repo = newRepository()
    }

    /** Tároló ugyanazokon a fájlokon, mint az alkalmazás újraindítás után. */
    private fun newRepository(): TransitRepository {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        val http = OkHttpClient.Builder().addInterceptor(fakeServer).build()
        return TransitRepository(OtpClient(http, json), GeocoderClient(http, json), MavinformClient(http), tmp.root)
    }

    @Test
    fun buildsNetworkAndCachesIt() = runBlocking {
        val net = repo.network()
        // A rövidnév nélküli vonal kimarad.
        assertEquals(listOf("10", "12", "14", "14G", "20", "24", "35", "42"), net.lines.map { it.shortName })
        assertEquals(25, net.stops.size)
        val group = net.group("Fő tér")
        assertNotNull(group)
        assertEquals(listOf("10", "12", "35", "42"), group!!.lines)
        // Másodszorra a memóriából jön, és a következő indításhoz lemezre íródik.
        repo.network()
        assertEquals(1, requests.size)
        assertTrue(java.io.File(tmp.root, TransitRepository.NETWORK_FILE).exists())
    }

    @Test
    fun knowsThePlatformsOfStations() = runBlocking {
        val net = repo.network()
        assertEquals(listOf("11", "12", "13", "14"), net.group("Vasútállomás")!!.stops.map { it.platformCode })
        assertEquals(listOf("1", "2", "3"), net.group("Autóbusz-állomás")!!.stops.map { it.platformCode })
        assertTrue(net.group("Fő tér")!!.stops.all { it.platformCode == null })
    }

    @Test
    fun mapsVehicles() = runBlocking {
        val vehicles = repo.vehicles()
        // A vonat kimarad; a két helyközi busz megmarad.
        assertEquals(9, vehicles.size)
        assertEquals(7, vehicles.count { it.isLocal })
        val local = vehicles.first { it.isLocal }
        assertTrue(local.label.isNotEmpty())
        assertFalse(local.headsign.startsWith("Székesfehérvár, "))
        assertNotNull(local.tripId)
    }

    @Test
    fun mapsDeparturesSortedByExpectedTime() = runBlocking {
        val deps = repo.departures(listOf("hkir:hkir_900101_1", "hkir:hkir_900101_2"))
        // A kétszer kapott indulás egyszer szerepel.
        assertEquals(8, deps.size)
        assertEquals(deps.sortedBy { it.expected }, deps)
        assertTrue(deps.all { it.isLocal && it.serviceDay > 0 && it.expected > it.serviceDay })
    }

    @Test
    fun mapsTrip() = runBlocking {
        val trip = repo.trip("hkir:hkir_T-35-01", LocalDate.of(2026, 10, 22))
        assertNotNull(trip)
        trip!!
        assertEquals("35", trip.line)
        assertTrue(trip.wheelchair)
        assertTrue(trip.shape.size > 10)
        assertEquals(trip.stops.sortedBy { it.scheduledDeparture }, trip.stops)
        assertTrue(trip.stops.zipWithNext().none { (a, b) -> a.name == b.name })
        // A busz a vasútállomás 14-es kocsiállásáról indul, és a 11-esen is megáll: egy sor, mindkét
        // kocsiállással.
        assertEquals("Vasútállomás", trip.stops.first().name)
        assertEquals("14. és 11.\u00A0kocsiállás", trip.stops.first().platformLabel)
        assertTrue(trip.stops.drop(1).all { it.platformLabel == null })
    }

    @Test
    fun mapsRouteWithOneMainPatternPerDirection() = runBlocking {
        val route = repo.route("hkir:hkir_V-14827-R-42")
        assertNotNull(route)
        assertEquals("42", route!!.line.shortName)
        assertEquals(4, route.patterns.size)
        // Irányonként a legtöbb járatú változat.
        assertEquals(listOf(0, 1), route.mainPatterns.map { it.directionId })
        assertEquals(listOf("Temető", "Autóbusz-állomás"), route.mainPatterns.map { it.headsign })
        assertTrue(route.mainPatterns.all { it.shape.isNotEmpty() && it.stops.isNotEmpty() })
    }

    @Test
    fun mergesTimetablePatternsWithSameHeadsign() = runBlocking {
        val table = repo.timetable(listOf("hkir:hkir_900101_1"), LocalDate.of(2026, 10, 21))
        val keys = table.map { it.line to it.headsign }
        assertEquals(keys.distinct(), keys)
        val line42 = table.first { it.line == "42" }
        assertEquals(10, line42.departures.size)
        assertEquals(line42.departures.sorted(), line42.departures)
        // Indulás nélküli változat nem kerül a menetrendbe.
        assertFalse(table.any { it.line == "35" })
    }

    @Test
    fun mapsItineraries() = runBlocking {
        val from = Place("A", 47.1925, 18.4105)
        val to = Place("B", 47.2004, 18.3976)
        val list = repo.plan(from, to, LocalDateTime.of(2026, 10, 22, 7, 30), arriveBy = false, includeIntercity = true)
        assertEquals(6, list.size)
        val withBus = list.first { it.transitLegs.isNotEmpty() }
        assertEquals("A", withBus.legs.first().from.name)
        assertEquals("B", withBus.legs.last().to.name)
        assertEquals(LegMode.BUS, withBus.transitLegs.first().mode)
        assertNotNull(withBus.transitLegs.first().serviceDay)
        assertTrue(requests.last().contains("\"COACH\""))
        assertTrue(requests.last().contains("\"walkReluctance\":2.0"))
    }

    @Test
    fun leastWalkingPutsShortestWalkFirst() = runBlocking {
        val from = Place("A", 47.1925, 18.4105)
        val to = Place("B", 47.2004, 18.3976)
        val list = repo.plan(
            from, to, LocalDateTime.of(2026, 10, 22, 7, 30),
            arriveBy = false, includeIntercity = true, leastWalking = true,
        )
        assertTrue(requests.last().contains("\"walkReluctance\":8.0"))
        val buckets = list.map { (it.walkDistanceM / 100).toInt() }
        assertEquals(buckets.sorted(), buckets)
    }

    @Test
    fun labelsPlatformsByTheirNextStop() = runBlocking {
        val net = repo.network()
        val platforms = net.group("Fő tér")!!.stops
        // A tér két oldala más-más megálló felé vezet.
        assertEquals(2, platforms.mapNotNull { it.directionLabel }.distinct().size)
        assertTrue(platforms.all { it.directionLabel!!.endsWith(" felé") })
        assertTrue(platforms.none { p -> p.towards.any { it == "Fő tér" } })
    }

    @Test
    fun loadsTrafficChangesOfTheCity() = runBlocking {
        val alerts = repo.alerts()
        // Öt székesfehérvári hír; az oldalsáv híre, a dunaújvárosi hír és a helyközi figyelmeztetés kimarad.
        assertEquals(5, alerts.size)
        assertTrue(alerts.all { it.id.startsWith("mavinform:") })
        fun linesOf(path: String) = alerts.first { it.url!!.endsWith(path) }.lines
        // Dobozban felsorolva.
        assertEquals(listOf("14", "14G", "20"), linesOf("szekesfehervar-oktober-26-atol-ideiglenes-forgalmi-valtozas"))
        // A helyközi vonalak doboza kimarad, akkor is, ha egy városi vonal száma is benne van.
        assertEquals(listOf("12", "42"), linesOf("szekesfehervar-oktober-22-etol-torlodas-varhato-a-fo-ter-kornyeken"))
        // Félkövéren.
        assertEquals(listOf("10", "35"), linesOf("szekesfehervar-november-2-atol-forgalmi-valtozas"))
        // A szövegben, ragozva; a házszám nem vonal.
        assertEquals(listOf("12", "24"), linesOf("szekesfehervar-oktober-20-atol-ideiglenes-megalloathelyezes"))
        // A cím nem említi a várost, a szöveg igen.
        assertEquals(listOf("20"), linesOf("torlodas-a-kertvarosban"))

        val detour = alerts.first { it.url!!.endsWith("szekesfehervar-oktober-26-atol-ideiglenes-forgalmi-valtozas") }
        assertTrue(detour.description.contains("• Malom utca"))
        assertFalse(detour.description.contains("<img"))
        // "2026.10.19. 13:39" budapesti nyári időben.
        assertEquals(1792409940L, detour.start)

        // Másodszorra a memóriából jön.
        val count = requests.size
        repo.alerts()
        assertEquals(count, requests.size)
    }

    @Test
    fun findsNearbyStopsWithoutSendingTheLocation() = runBlocking {
        val nearby = repo.nearby(GeoPoint(47.1925, 18.4105))
        assertEquals(nearby.sortedBy { it.distanceM }, nearby)
        assertTrue(nearby.all { it.distanceM <= 700 })
        // A telefon méri a pontos helyzetből.
        assertEquals("hkir:hkir_900101_1", nearby.first().stop.id)
        assertTrue(abs(nearby.first().distanceM - 140) <= 2)
        // A tesztadatok indulásai a Fő téréi.
        assertTrue(nearby.first().departures.isNotEmpty())
        assertTrue(nearby.drop(2).all { it.departures.isEmpty() })

        assertTrue(requests.last().contains("query Departures"))
        assertTrue(requests.none { it.contains("47.19") || it.contains("18.41") })
    }

    @Test
    fun findsStopsOfIntercityBusesOnlyWhenAsked() = runBlocking {
        // A város szélén lévő Homokpusztán csak helyközi buszok járnak.
        val field = GeoPoint(47.1207, 18.4622)
        assertTrue(repo.nearby(field, 300).isEmpty())
        val stops = repo.nearby(field, 300, includeIntercity = true)
        assertEquals(listOf("hkir:hkir_900901_1", "hkir:hkir_900901_2"), stops.map { it.stop.id }.sorted())
        // A vonatok kimaradnak, és a lista egész napra megmarad.
        assertTrue(repo.areaStops().all { it.id.startsWith("hkir:") })
        assertEquals(1, requests.count { it.contains("query AreaStops") })
    }

    @Test
    fun keepsNoticesOnDiskForTheBackgroundCheck() = runBlocking {
        repo.alerts()
        // Az időszakos ellenőrzés gyakran friss folyamatban fut.
        val restarted = newRepository()
        requests.clear()
        assertEquals(5, restarted.alerts().size)
        // Csak a lista töltődik le újra: egyik hír sem változott.
        assertEquals(listOf(MavinformClient.LIST_URL), requests.filter { it.startsWith(MavinformClient.BASE_URL) })
    }
}
