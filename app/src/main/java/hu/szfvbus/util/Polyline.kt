package hu.szfvbus.util

import hu.szfvbus.data.model.GeoPoint

/** A Google „encoded polyline” (5-ös pontosságú) dekódolója; az OTP ebben adja a geometriákat. */
object Polyline {
    fun decode(encoded: String?): List<GeoPoint> {
        if (encoded.isNullOrEmpty()) return emptyList()
        val result = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var shift = 0
            var acc = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                acc = acc or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (acc and 1 != 0) (acc shr 1).inv() else acc shr 1

            shift = 0
            acc = 0
            do {
                b = encoded[index++].code - 63
                acc = acc or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (acc and 1 != 0) (acc shr 1).inv() else acc shr 1

            result += GeoPoint(lat / 1e5, lon / 1e5)
        }
        return result
    }
}
