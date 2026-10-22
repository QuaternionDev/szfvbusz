package hu.szfvbus.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Serializable
private class GqlError(val message: String = "")

@Serializable
private class GqlResponse<T>(val data: T? = null, val errors: List<GqlError>? = null)

/** Minimális GraphQL-kliens a MÁV Plusz OpenTripPlanner 2 végponthoz. */
class OtpClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val endpoint: String = ENDPOINT,
) {
    suspend fun <T> query(query: String, variables: JsonObject, serializer: KSerializer<T>): T {
        val payload = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_TYPE))
            .build()
        val body = http.newCall(request).await()
        return withContext(Dispatchers.Default) { parse(body, serializer) }
    }

    /** A nyers válaszszöveg, a statikus hálózat lemezes tárolásához. */
    suspend fun queryRaw(query: String, variables: JsonObject): String {
        val payload = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(JSON_TYPE))
            .build()
        return http.newCall(request).await()
    }

    fun <T> parse(body: String, serializer: KSerializer<T>): T {
        val response = json.decodeFromString(GqlResponse.serializer(serializer), body)
        response.data?.let { return it }
        throw ApiException(response.errors?.joinToString { it.message } ?: "Üres válasz a szervertől")
    }

    companion object {
        const val ENDPOINT = "https://mavplusz.hu/otp2-backend/otp/routers/default/index/graphql"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Photon (OSM) címkereső, Székesfehérvár környékére szűkítve. */
class GeocoderClient(private val http: OkHttpClient, private val json: Json) {
    suspend fun search(query: String, limit: Int = 8): List<PhotonFeature> {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("bbox", "18.28,47.11,18.58,47.27")
            .addQueryParameter("lat", "47.19")
            .addQueryParameter("lon", "18.41")
            .build()
        val body = http.newCall(Request.Builder().url(url).build()).await()
        return withContext(Dispatchers.Default) {
            json.decodeFromString(PhotonResponse.serializer(), body).features
        }
    }
}

private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(ApiException("Hálózati hiba: ${e.message}", e))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!it.isSuccessful) {
                    cont.resumeWithException(ApiException("Szerverhiba (${it.code})"))
                    return
                }
                val text = it.body?.string().orEmpty()
                cont.resume(text)
            }
        }
    })
    cont.invokeOnCancellation { cancel() }
}
