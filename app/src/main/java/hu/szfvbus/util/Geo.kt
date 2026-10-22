package hu.szfvbus.util

import hu.szfvbus.data.model.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

/** Távolság méterben; sík közelítés, a városon belül pontos. */
fun distanceM(a: GeoPoint, b: GeoPoint): Double {
    val dx = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111_320.0
    val dy = (b.lat - a.lat) * 110_540.0
    return sqrt(dx * dx + dy * dy)
}
