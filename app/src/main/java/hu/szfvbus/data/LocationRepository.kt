package hu.szfvbus.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import hu.szfvbus.data.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Helymeghatározás a rendszer LocationManagerével (GPS + hálózat), így nem kell hozzá Play Services. */
class LocationRepository(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(): GeoPoint? {
        if (!hasPermission()) return null
        return providers()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toGeoPoint()
    }

    /** Kiadja a legjobb helyzetet, ahogy pontosodik; engedély nélkül üres. */
    @SuppressLint("MissingPermission")
    fun updates(minTimeMs: Long = 3000): Flow<GeoPoint> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        var best: Location? = null
        val listener = LocationListener { loc ->
            val current = best
            // A friss helymeghatározás az előnyös; pontatlanabbat csak akkor fogad el, ha az előző már régi.
            if (current == null || loc.accuracy <= current.accuracy || loc.time - current.time > 15_000) {
                best = loc
                trySend(loc.toGeoPoint())
            }
        }
        lastKnown()?.let { trySend(it) }
        for (provider in providers()) {
            runCatching {
                manager.requestLocationUpdates(provider, minTimeMs, 5f, listener, Looper.getMainLooper())
            }
        }
        awaitClose { manager.removeUpdates(listener) }
    }.distinctUntilChanged()

    private fun providers(): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)
}
