package hu.szfvbus.util

import hu.szfvbus.data.LineColors
import hu.szfvbus.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UtilTest {
    @Test
    fun decodesGoogleReferencePolyline() {
        // Példa a Google polyline-algoritmus dokumentációjából.
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertPoint(GeoPoint(38.5, -120.2), points[0])
        assertPoint(GeoPoint(40.7, -120.95), points[1])
        assertPoint(GeoPoint(43.252, -126.453), points[2])
    }

    @Test
    fun bearingPointsTheWayOfTravel() {
        val center = GeoPoint(47.19, 18.41)
        assertEquals(0f, bearingDeg(center, GeoPoint(47.20, 18.41)), 0.5f)
        assertEquals(90f, bearingDeg(center, GeoPoint(47.19, 18.42)), 0.5f)
        assertEquals(180f, bearingDeg(center, GeoPoint(47.18, 18.41)), 0.5f)
        assertEquals(270f, bearingDeg(center, GeoPoint(47.19, 18.40)), 0.5f)
    }

    @Test
    fun decodesEmptyPolyline() {
        assertTrue(Polyline.decode(null).isEmpty())
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test
    fun sortsLineNamesNaturally() {
        val sorted = listOf("12Y", "100", "11A", "9", "11", "26A", "26", "M").sortedWith(LineNameComparator)
        assertEquals(listOf("9", "11", "11A", "12Y", "26", "26A", "100", "M"), sorted)
    }

    @Test
    fun cleansCityPrefixOnly() {
        assertEquals("Országzászló tér", cleanStopName("Székesfehérvár, Országzászló tér"))
        assertEquals("Jancsár utca", cleanStopName("Székesfehérvár, Jancsár  utca"))
        assertEquals("Vasútállomás", cleanStopName("Székesfehérvár, vasútállomás"))
        assertEquals("Kisfalud, iskola", cleanStopName("Kisfalud, iskola"))
        assertEquals("", cleanStopName(null))
    }

    @Test
    fun searchKeyIgnoresAccentsAndCase() {
        assertEquals("csapo utca", searchKey("Csapó utca"))
        assertTrue(searchKey("Székesfehérvár, Szent Gellért utca").contains(searchKey("gellert")))
    }

    @Test
    fun formatsDelays() {
        assertEquals("pontos", TimeUtil.delayText(20))
        assertEquals("3 perc késés", TimeUtil.delayText(170))
        assertEquals("2 perc siet", TimeUtil.delayText(-147))
    }

    @Test
    fun formatsRelativeTimes() {
        val now = 1_000_000L
        assertEquals("most", TimeUtil.relative(now + 30, now))
        assertEquals("5 perc", TimeUtil.relative(now + 5 * 60 + 10, now))
        assertEquals(TimeUtil.clock(now + 2 * 3600), TimeUtil.relative(now + 2 * 3600, now))
    }

    @Test
    fun formatsDurations() {
        assertEquals("5 perc", TimeUtil.durationText(300))
        assertEquals("1 ó 1 p", TimeUtil.durationText(3601))
    }

    @Test
    fun serviceDateRoundTrip() {
        val date = LocalDate.of(2026, 10, 22)
        assertEquals("20261022", TimeUtil.serviceDate(date))
        val midnight = date.atStartOfDay(TimeUtil.ZONE).toEpochSecond()
        assertEquals(date, TimeUtil.serviceDateOf(midnight))
    }

    @Test
    fun lineVariantsShareColor() {
        assertEquals(LineColors.forLine("11"), LineColors.forLine("11A"))
        assertNotEquals(LineColors.forLine("11"), LineColors.forLine("12"))
        assertEquals(LineColors.INTERCITY, LineColors.forLine("8097", isLocal = false))
        assertEquals("#E53935", LineColors.hex(0xFFE53935.toInt()))
    }

    @Test
    fun namesPlatformsInHungarian() {
        assertEquals(null, platformText(emptyList()))
        assertEquals("12.\u00A0kocsiállás", platformText(listOf("12")))
        assertEquals("11. és 12.\u00A0kocsiállás", platformText(listOf("11", "12")))
        assertEquals("2., 3. és 10.\u00A0kocsiállás", platformText(listOf("2", "3", "10")))
        assertEquals("A\u00A0kocsiállás", platformText(listOf("A")))
    }

    @Test
    fun measuresDistancesInTheCity() {
        // 0,009° észak felé nagyjából egy kilométer; 0,01° kelet felé ezen a szélességen nagyjából 757 m.
        assertEquals(995.0, distanceM(GeoPoint(47.19, 18.41), GeoPoint(47.199, 18.41)), 2.0)
        assertEquals(757.0, distanceM(GeoPoint(47.19, 18.41), GeoPoint(47.19, 18.42)), 2.0)
    }

    private fun assertPoint(expected: GeoPoint, actual: GeoPoint) {
        assertEquals(expected.lat, actual.lat, 1e-5)
        assertEquals(expected.lon, actual.lon, 1e-5)
    }
}
