package hu.szfvbus.data

import hu.szfvbus.data.api.MavinformParser
import hu.szfvbus.data.model.GeoPoint
import hu.szfvbus.data.model.TripDetails
import hu.szfvbus.data.model.TripStop
import hu.szfvbus.util.DevClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DevFeaturesTest {
    @After
    fun tearDown() {
        DevClock.reset()
        DevTools.reset()
    }

    private val knownLines = listOf("10", "13G", "14", "14G", "16", "20", "24", "26", "37", "42")

    @Test
    fun findsAffectedLinesButNotDatesOrHouseNumbers() {
        val body = """
            <p><strong>2026. október 26-án 8:00-tól</strong> a 16-os és 37-es autóbuszok terelve közlekednek.</p>
            <div class="bluebox"><strong>Érintett helyi vonalak:&nbsp;13G, 20</strong></div>
            <div class="bluebox"><strong>Érintett helyközi vonalak:&nbsp;10, 1507</strong></div>
            <ul><li>Seregélyesi út 24. – 14 ► Köfém lakótelep</li></ul>
        """.trimIndent()
        assertEquals(listOf("13G", "20", "16", "37"), MavinformParser.affectedLines(body, knownLines))
    }

    @Test
    fun cleansNoticeBodyForDisplay() {
        val html = MavinformParser.displayHtml(
            """<p style="x">Kimarad:</p><ul><li>Bácskai utca</li><li>Zimonyi utca</li></ul><img src="a.png"></div>  </div>""",
        )
        assertEquals("<p>Kimarad:</p><br>• Bácskai utca<br>• Zimonyi utca", html)
        assertEquals("Kimarad:\n• Bácskai utca\n• Zimonyi utca", MavinformParser.htmlToLines(html))
    }

    @Test
    fun tellsCityNoticesFromOtherTowns() {
        assertTrue(MavinformParser.isAboutTheCity("Székesfehérvár – október 5-étől változás", ""))
        assertTrue(MavinformParser.isAboutTheCity("Torlódás a Sóstónál", "<p>Székesfehérvár, Sóstói út</p>"))
        assertFalse(MavinformParser.isAboutTheCity("Dunaújváros – éjszakai járatok", "<p>Dunaújváros</p>"))
    }

    private fun trip(): TripDetails {
        // Három megálló egymástól 1 km-re észak felé, köztük két perc, B-ben egy perc megállás.
        fun ts(id: String, lat: Double, arr: Long, dep: Long) = TripStop(id, id, lat, 18.4, arr, arr, dep, dep, 0, false)
        return TripDetails(
            "t1", "r1", "42", "C", true, false,
            shape = listOf(GeoPoint(47.0, 18.4), GeoPoint(47.009, 18.4), GeoPoint(47.018, 18.4)),
            stops = listOf(ts("A", 47.0, 1000, 1000), ts("B", 47.009, 1120, 1180), ts("C", 47.018, 1300, 1300)),
        )
    }

    @Test
    fun simulatedBusFollowsTheTimetable() {
        val sim = SimulatedTrip(trip())
        assertNull(sim.vehicleAt(1000L - SimulatedTrip.WAIT_AT_START_SEC - 1))
        // Indulás előtt vár a végállomáson.
        val waiting = sim.vehicleAt(900)!!
        assertEquals("A", waiting.nextStopName)
        assertEquals("STOPPED_AT", waiting.stopStatus)
        // Félúton A és B között, észak felé, B irányába.
        val moving = sim.vehicleAt(1060)!!
        assertEquals(47.0045, moving.lat, 0.0002)
        assertEquals("B", moving.nextStopName)
        assertEquals("IN_TRANSIT_TO", moving.stopStatus)
        assertTrue(abs(moving.heading!!) < 1f || abs(moving.heading!! - 360f) < 1f)
        assertTrue(moving.speedMps!! in 7.0..9.0)
        // B-ben áll, aztán indul C felé.
        assertEquals("B", sim.vehicleAt(1150)!!.nextStopName)
        assertEquals("C", sim.vehicleAt(1200)!!.nextStopName)
        assertNull(sim.vehicleAt(2000))
        assertEquals("SIM-t1", moving.id)
    }

    @Test
    fun devClockShiftsAndSpeedsUpTime() {
        val target = System.currentTimeMillis() + 5 * 3_600_000L
        DevClock.set(target, speed = 60.0)
        assertTrue(DevClock.isShifted)
        assertTrue(abs(DevClock.nowMs() - target) < 5_000)
        Thread.sleep(50)
        // 50 ms valós idő nagyjából 3 s a gyorsított órán.
        assertTrue(DevClock.nowMs() - target >= 2_500)
        DevClock.reset()
        assertFalse(DevClock.isShifted)
        assertTrue(abs(DevClock.nowMs() - System.currentTimeMillis()) < 1_000)
    }

    @Test
    fun devStateKnowsWhenSomethingIsFaked() {
        assertFalse(DevTools.current.isActive)
        DevTools.update { it.copy(fakeDelayMin = 5) }
        assertTrue(DevTools.current.isActive)
    }
}
