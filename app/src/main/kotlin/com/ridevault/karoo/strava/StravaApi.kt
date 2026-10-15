package com.ridevault.karoo.strava

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Strava API client for RideVault.
 *
 * Handles:
 * - OAuth token exchange and refresh
 * - GET /athlete (bike list for sensor mapping setup)
 * - POST /uploads (FIT file upload with gear_id)
 * - PUT /activities/{id} (set gear_id if not set on upload)
 */
class StravaApi(private val tokenStore: StravaTokenStore) {

    companion object {
        private const val BASE_URL = "https://www.strava.com/api/v3"
        private const val AUTH_URL = "https://www.strava.com/oauth/token"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    // ── Data models ──────────────────────────────────────────────

    @Serializable
    data class Athlete(
        val id: Long,
        @SerialName("firstname") val firstName: String = "",
        @SerialName("lastname") val lastName: String = "",
        val bikes: List<Bike> = emptyList(),
        @SerialName("resource_state") val resourceState: Int = 0,
    )

    @Serializable
    data class Bike(
        val id: String, // gear_id, e.g. "b12345"
        val name: String,
        val distance: Double = 0.0,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_at") val expiresAt: Long,
        val athlete: Athlete? = null,
    )

    @Serializable
    data class UploadResponse(
        val id: Long,
        @SerialName("id_str") val idStr: String = "",
        @SerialName("external_id") val externalId: String = "",
        @SerialName("activity_id") val activityId: Long? = null,
        val status: String = "",
        val error: String? = null,
    )

    @Serializable
    data class UploadStatus(
        val id: Long,
        @SerialName("activity_id") val activityId: Long? = null,
        val status: String = "",
        val error: String? = null,
    )

    // ── HTTP client ──────────────────────────────────────────────

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor { message ->
                    Timber.tag("StravaApi").d(message)
                }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
            .build()
    }

    // ── Auth ─────────────────────────────────────────────────────

    /**
     * Exchange authorization code for tokens.
     */
    fun exchangeToken(
        clientId: String,
        clientSecret: String,
        code: String,
    ): Result<TokenResponse> {
        val body = """
            {
                "client_id": "$clientId",
                "client_secret": "$clientSecret",
                "code": "$code",
                "grant_type": "authorization_code"
            }
        """.trimIndent().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(AUTH_URL)
            .post(body)
            .build()

        return executeRequest(request) { responseBody ->
            json.decodeFromString<TokenResponse>(responseBody)
        }
    }

    /**
     * Refresh an expired access token.
     */
    fun refreshToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String,
    ): Result<TokenResponse> {
        val body = """
            {
                "client_id": "$clientId",
                "client_secret": "$clientSecret",
                "refresh_token": "$refreshToken",
                "grant_type": "refresh_token"
            }
        """.trimIndent().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(AUTH_URL)
            .post(body)
            .build()

        return executeRequest(request) { responseBody ->
            json.decodeFromString<TokenResponse>(responseBody)
        }
    }

    // ── Athlete / Bikes ──────────────────────────────────────────

    /**
     * Get authenticated athlete info including bike list.
     * Used during sensor-bike mapping setup.
     */
    fun getAthlete(accessToken: String): Result<Athlete> {
        val request = Request.Builder()
            .url("$BASE_URL/athlete")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return executeRequest(request) { responseBody ->
            Timber.d("GET /athlete response: $responseBody")
            json.decodeFromString<Athlete>(responseBody)
        }
    }

    // ── Upload ───────────────────────────────────────────────────

    /**
     * Upload a .FIT file to Strava with the correct gear_id.
     *
     * This is the core of RideVault — setting gear_id on the initial
     * POST /uploads so Strava assigns the correct bike immediately,
     * fixing what Hammerhead's native sync fails to do.
     */
    fun uploadActivity(
        accessToken: String,
        fitFile: File,
        gearId: String?,
        name: String? = null,
        description: String? = null,
    ): Result<UploadResponse> {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fitFile.name,
                fitFile.asRequestBody("application/octet-stream".toMediaType()),
            )
            .addFormDataPart("data_type", "fit")

        if (gearId != null) {
            builder.addFormDataPart("gear_id", gearId)
        }
        if (name != null) {
            builder.addFormDataPart("name", name)
        }
        if (description != null) {
            builder.addFormDataPart("description", description)
        }

        val request = Request.Builder()
            .url("$BASE_URL/uploads")
            .header("Authorization", "Bearer $accessToken")
            .post(builder.build())
            .build()

        return executeRequest(request) { responseBody ->
            json.decodeFromString<UploadResponse>(responseBody)
        }
    }

    /**
     * Check upload processing status.
     */
    fun getUploadStatus(accessToken: String, uploadId: Long): Result<UploadStatus> {
        val request = Request.Builder()
            .url("$BASE_URL/uploads/$uploadId")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return executeRequest(request) { responseBody ->
            json.decodeFromString<UploadStatus>(responseBody)
        }
    }

    /**
     * Update an activity's gear_id after upload (fallback if not set during upload).
     */
    fun updateActivityGear(
        accessToken: String,
        activityId: Long,
        gearId: String,
    ): Result<Unit> {
        val body = """{"gear_id": "$gearId"}""".toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$BASE_URL/activities/$activityId")
            .header("Authorization", "Bearer $accessToken")
            .put(body)
            .build()

        return executeRequest(request) { }
    }

    // ── Relay auth ────────────────────────────────────────────────

    /**
     * Fetch tokens from the auth relay using the 6-digit PIN.
     * The relay has already exchanged the auth code for tokens;
     * this just retrieves them.
     */
    fun fetchTokenFromRelay(relayUrl: String, pin: String): Result<TokenResponse> {
        val request = Request.Builder()
            .url("$relayUrl/token?pin=$pin")
            .get()
            .build()

        return executeRequest(request) { responseBody ->
            json.decodeFromString<TokenResponse>(responseBody)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun <T> executeRequest(
        request: Request,
        parser: (String) -> T,
    ): Result<T> {
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                Result.success(parser(body))
            } else {
                Timber.e("Strava API error ${response.code}: $body")
                Result.failure(IOException("Strava API ${response.code}: $body"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Strava API request failed")
            Result.failure(e)
        }
    }
}
