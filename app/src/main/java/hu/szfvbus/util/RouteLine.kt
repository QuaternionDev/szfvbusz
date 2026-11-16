package hu.szfvbus.util

import hu.szfvbus.data.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Egy útvonal vonala, amelyen méterben lehet haladni, mint egy navigációs alkalmazás útján: egy helyzet
 * rávetíthető, és lekérdezhető a vonal adott hosszánál lévő pont és a menetirány.
 */
class RouteLine(points: List<GeoPoint>) {
    private val points: List<GeoPoint> = points.filterIndexed { i, p -> i == 0 || p != points[i - 1] }

    /** Az egyes pontokig megtett hossz a vonal elejétől, méterben. */
    private val cumulative = DoubleArray(this.points.size).also { cum ->
        for (i in 1 until this.points.size) cum[i] = cum[i - 1] + distanceM(this.points[i - 1], this.points[i])
    }

    val length: Double get() = cumulative.lastOrNull() ?: 0.0
    val isUsable: Boolean get() = points.size >= 2 && length > 0.0

    /** Rávetítés: [alongM] a vonal elejétől mért hossz, [offM] a helyzet távolsága a vonaltól. */
    data class Projection(val alongM: Double, val offM: Double)

    /**
     * A [p] helyzethez legközelebbi pont a vonalon. Ha a vonal visszakanyarodik önmagához, a [nearAlongM]
     * közelében lévő szakaszt részesíti előnyben, hogy a nyíl ne ugorjon át a szemközti oldalra.
     */
    fun project(p: GeoPoint, nearAlongM: Double? = null): Projection? {
        if (!isUsable) return null
        val kx = cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_540.0
        var best: Projection? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.lon - p.lon) * kx
            val ay = (a.lat - p.lat) * ky
            val dx = (b.lon - a.lon) * kx
            val dy = (b.lat - a.lat) * ky
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) continue
            val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + dx * t
            val cy = ay + dy * t
            val off = sqrt(cx * cx + cy * cy)
            val along = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * t
            val score = off + (nearAlongM?.let { NEAR_WEIGHT * abs(along - it) } ?: 0.0)
            if (score < bestScore) {
                bestScore = score
                best = Projection(along, off)
            }
        }
        return best
    }

    /** A vonal elejétől [alongM] méterre lévő pont. */
    fun pointAt(alongM: Double): GeoPoint {
        if (points.size < 2) return points.first()
        val s = alongM.coerceIn(0.0, length)
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= s) lo = mid else hi = mid
        }
        val segment = cumulative[hi] - cumulative[lo]
        val t = if (segment > 0) (s - cumulative[lo]) / segment else 0.0
        val a = points[lo]
        val b = points[hi]
        return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /**
     * Menetirány [alongM] méternél. Kicsit előre is néz, így kanyar előtt a nyíl és a térkép már finoman
     * elkezd fordulni, ahogy egy navigációban.
     */
    fun bearingAt(alongM: Double, behindM: Double = 5.0, aheadM: Double = 12.0): Float {
        val from = (alongM - behindM).coerceIn(0.0, length)
        val to = (alongM + aheadM).coerceIn(0.0, length)
        return if (to - from < 1.0) {
            bearingDeg(pointAt(length - 2.0), pointAt(length))
        } else {
            bearingDeg(pointAt(from), pointAt(to))
        }
    }

    private companion object {
        /** Méterenként ennyi büntetés jár, ha a rávetítés messze esik a várt helytől. */
        const val NEAR_WEIGHT = 0.15
    }
}
