package hu.szfvbus.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import hu.szfvbus.data.model.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Egy helymeghatározás. A [bearing] és a [speedMps] csak akkor van meg, ha a GPS mérte; a [receivedMs]
 * a beérkezés ideje [SystemClock.elapsedRealtime] szerint, ebből becsülhető a mozgás két mérés között.
 */
data class LocationFix(
    val point: GeoPoint,
    val bearing: Float? = null,
    val speedMps: Float? = null,
    val receivedMs: Long = SystemClock.elapsedRealtime(),
)

/**
 * Helymeghatározás a rendszer LocationManagerével (GPS + hálózat), így nem kell hozzá Play Services.
 * A fejlesztői mód hamis helyzete ([DevState.fakeLocation]) mindkét lekérdezésben felülírja a valódit.
 */
class LocationRepository(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(): GeoPoint? {
        DevTools.current.fakeLocation?.let { return it }
        if (!hasPermission()) return null
        return providers()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toGeoPoint()
    }

    /** Kiadja a legjobb helyzetet, ahogy pontosodik; engedély nélkül üres. */
    fun updates(): Flow<GeoPoint> = fixes(navigation = false).map { it.point }.distinctUntilChanged()

    /**
     * Helymeghatározások iránnyal és sebességgel. [navigation] módban másodpercenként kéri a GPS-t, és
     * minden GPS-mérést továbbad, mint egy navigációs alkalmazás; egyébként takarékosabban, 3 másodpercenként.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun fixes(navigation: Boolean): Flow<LocationFix> =
        DevTools.state.map { it.fakeLocation }.distinctUntilChanged().flatMapLatest { fake ->
            if (fake != null) flowOf(LocationFix(fake)) else systemUpdates(navigation)
        }

    @SuppressLint("MissingPermission")
    private fun systemUpdates(navigation: Boolean): Flow<LocationFix> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        var best: Location? = null
        var lastGpsMs = 0L
        val listener = LocationListener { loc ->
            val now = SystemClock.elapsedRealtime()
            val gps = loc.provider == LocationManager.GPS_PROVIDER
            if (gps) lastGpsMs = now
            val current = best
            val accept = if (navigation) {
                // Navigációnál minden GPS-mérés kell; a pontatlanabb hálózati helyzet csak akkor, ha a GPS elhallgatott.
                gps || now - lastGpsMs > GPS_SILENT_MS
            } else {
                // A friss helymeghatározás az előnyös; pontatlanabbat csak akkor fogad el, ha az előző már régi.
                current == null || loc.accuracy <= current.accuracy || loc.time - current.time > 15_000
            }
            if (accept) {
                best = loc
                trySend(
                    LocationFix(
                        point = loc.toGeoPoint(),
                        bearing = if (gps && loc.hasBearing()) loc.bearing else null,
                        speedMps = if (gps && loc.hasSpeed()) loc.speed else null,
                        receivedMs = now,
                    ),
                )
            }
        }
        lastKnown()?.let { trySend(LocationFix(it)) }
        for (provider in providers()) {
            runCatching {
                if (navigation) {
                    manager.requestLocationUpdates(provider, NAVIGATION_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
                } else {
                    manager.requestLocationUpdates(provider, NORMAL_INTERVAL_MS, 5f, listener, Looper.getMainLooper())
                }
            }
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    private fun providers(): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

    private companion object {
        const val NORMAL_INTERVAL_MS = 3000L
        const val NAVIGATION_INTERVAL_MS = 1000L
        /** Ennyi GPS-csend után a navigáció a hálózati helyzetet is elfogadja. */
        const val GPS_SILENT_MS = 5000L
    }
}
