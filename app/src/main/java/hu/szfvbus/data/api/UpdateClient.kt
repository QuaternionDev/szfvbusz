package hu.szfvbus.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Egy kiadás a GitHubon, amennyit a frissítési értesítéshez tudni kell. */
@Serializable
data class AppRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val url: String,
) {
    /** A verziószám „v” nélkül, ahogy a BuildConfig.VERSION_NAME is áll. */
    val version: String get() = tag.removePrefix("v")
}

/**
 * Az alkalmazás kiadásai a GitHubon. Csak a legfrissebb végleges kiadást kérdezzük le; a
 * tesztverziókat (pre-release) a GitHub ebből a válaszból kihagyja.
 */
class UpdateClient(private val http: OkHttpClient, private val json: Json) {

    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub hiba: HTTP ${response.code}")
            json.decodeFromString(AppRelease.serializer(), response.body?.string() ?: throw IOException("Üres válasz a GitHubtól"))
        }
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/QuaternionDev/szfvbusz/releases/latest"

        /**
         * Igaz, ha a [candidate] verzió újabb a [current]-nél. Számonként hasonlít („0.10.0” > „0.9.1”);
         * a „-beta”-féle utótagokat elhagyja, a hiányzó részeket nullának veszi.
         */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (diff != 0) return diff > 0
            }
            return false
        }
    }
}
