package com.ridevault.karoo.strava

import android.content.Context
import com.ridevault.karoo.BuildConfig
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File

/**
 * Handles uploading .FIT files to Strava with correct gear_id.
 *
 * This is the core value proposition of Karoo RideVault:
 * Hammerhead's native Strava sync does NOT reliably set the bike/equipment,
 * so RideVault handles the upload itself with the gear_id from
 * the sensor-to-bike mapping.
 */
class StravaUploader(private val context: Context) {

    private val tokenStore = StravaTokenStore(context)
    private val api = StravaApi(tokenStore)

    sealed class UploadResult {
        data class Success(val activityId: Long, val bikeName: String) : UploadResult()
        data class Processing(val uploadId: Long) : UploadResult()
        data class Error(val message: String) : UploadResult()
    }

    /**
     * Upload a .FIT file to Strava with the detected gear_id.
     *
     * Handles token refresh if needed, then uploads and polls for completion.
     */
    suspend fun upload(
        fitFile: File,
        gearId: String?,
        bikeName: String?,
    ): UploadResult {
        // Ensure we have a valid token
        val accessToken = getValidAccessToken()
            ?: return UploadResult.Error("Not connected to Strava")

        Timber.i("Uploading ${fitFile.name} with gear_id=$gearId ($bikeName)")

        // Upload the .FIT file
        val uploadResult = api.uploadActivity(
            accessToken = accessToken,
            fitFile = fitFile,
            gearId = gearId,
        )

        return uploadResult.fold(
            onSuccess = { response ->
                if (response.error != null) {
                    Timber.e("Upload error: ${response.error}")
                    UploadResult.Error(response.error)
                } else {
                    // Poll for processing completion
                    pollUploadStatus(accessToken, response.id, bikeName ?: "Unknown")
                }
            },
            onFailure = { e ->
                Timber.e(e, "Upload failed")
                UploadResult.Error(e.message ?: "Upload failed")
            },
        )
    }

    /**
     * Poll Strava upload status until processing is complete.
     * Strava processes uploads asynchronously — the initial POST returns
     * an upload ID, and we need to poll GET /uploads/{id} until
     * activity_id is populated.
     */
    private suspend fun pollUploadStatus(
        accessToken: String,
        uploadId: Long,
        bikeName: String,
        maxAttempts: Int = 10,
    ): UploadResult {
        repeat(maxAttempts) { attempt ->
            delay(2000L * (attempt + 1)) // backoff: 2s, 4s, 6s...

            val statusResult = api.getUploadStatus(accessToken, uploadId)
            statusResult.onSuccess { status ->
                if (status.activityId != null) {
                    Timber.i("Upload complete — activity ${status.activityId}")
                    return UploadResult.Success(status.activityId, bikeName)
                }
                if (status.error != null) {
                    Timber.e("Upload processing error: ${status.error}")
                    return UploadResult.Error(status.error)
                }
                Timber.d("Upload still processing (attempt ${attempt + 1}/$maxAttempts)")
            }
            statusResult.onFailure { e ->
                Timber.w(e, "Failed to check upload status (attempt ${attempt + 1})")
            }
        }

        return UploadResult.Processing(uploadId)
    }

    /**
     * Get a valid access token, refreshing if expired.
     */
    private fun getValidAccessToken(): String? {
        val token = tokenStore.getToken() ?: return null

        if (!tokenStore.isExpired()) {
            return token.accessToken
        }

        Timber.i("Access token expired, refreshing...")
        val refreshResult = api.refreshToken(
            clientId = BuildConfig.STRAVA_CLIENT_ID,
            clientSecret = BuildConfig.STRAVA_CLIENT_SECRET,
            refreshToken = token.refreshToken,
        )

        return refreshResult.fold(
            onSuccess = { response ->
                tokenStore.saveToken(
                    token.copy(
                        accessToken = response.accessToken,
                        refreshToken = response.refreshToken,
                        expiresAt = response.expiresAt,
                    ),
                )
                response.accessToken
            },
            onFailure = { e ->
                Timber.e(e, "Token refresh failed")
                null
            },
        )
    }
}
