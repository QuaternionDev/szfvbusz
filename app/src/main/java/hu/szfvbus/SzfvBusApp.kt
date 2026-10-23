package hu.szfvbus

import android.app.Application
import android.content.Context
import hu.szfvbus.data.LocationRepository
import hu.szfvbus.data.TransitRepository
import hu.szfvbus.data.UserPrefs
import hu.szfvbus.data.api.GeocoderClient
import hu.szfvbus.data.api.MavinformClient
import hu.szfvbus.data.api.OtpClient
import hu.szfvbus.data.api.UpdateClient
import hu.szfvbus.notify.AlertCheckWorker
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import java.io.File
import java.util.concurrent.TimeUnit

/** Kézzel összekötött függőségek, amelyeken az egész alkalmazás osztozik. */
class AppContainer(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024))
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "SzfvBusz/${BuildConfig.VERSION_NAME} (Android)")
                    // Az OTP e fejléc szerint fordítja a figyelmeztetéseket és más szövegeket.
                    .header("Accept-Language", "hu")
                    .build(),
            )
        }
        .build()

    val transit = TransitRepository(OtpClient(http, json), GeocoderClient(http, json), MavinformClient(http), context.filesDir)
    val prefs = UserPrefs(context)
    val location = LocationRepository(context)
    val updates = UpdateClient(http, json)
}

class SzfvBusApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
        AlertCheckWorker.schedule(this)
    }
}
