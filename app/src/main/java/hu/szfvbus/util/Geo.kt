package hu.szfvbus.util

import hu.szfvbus.data.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** Távolság méterben; sík közelítés, a városon belül pontos. */
fun distanceM(a: GeoPoint, b: GeoPoint): Double {
    val dx = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111_320.0
    val dy = (b.lat - a.lat) * 110_540.0
    return sqrt(dx * dx + dy * dy)
}

/** Irányszög [a]-ból [b] felé fokban, északtól az óramutató járásával (0–360); sík közelítés. */
fun bearingDeg(a: GeoPoint, b: GeoPoint): Float {
    val dx = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111_320.0
    val dy = (b.lat - a.lat) * 110_540.0
    return ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0).toFloat()
}
