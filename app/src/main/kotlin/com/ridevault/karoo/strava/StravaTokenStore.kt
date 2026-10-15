package com.ridevault.karoo.strava

import android.content.Context
import android.content.SharedPreferences
import com.ridevault.karoo.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Stores Strava OAuth tokens securely in SharedPreferences.
 */
class StravaTokenStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "ridevault_strava"
        private const val KEY_TOKEN_DATA = "token_data"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    data class TokenData(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val athleteId: Long,
        val athleteFirstName: String = "",
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasToken(): Boolean {
        return prefs.contains(KEY_TOKEN_DATA)
    }

    fun getToken(): TokenData? {
        val data = prefs.getString(KEY_TOKEN_DATA, null) ?: return null
        return try {
            json.decodeFromString<TokenData>(data)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read token data")
            null
        }
    }

    fun saveToken(token: TokenData) {
        prefs.edit()
            .putString(KEY_TOKEN_DATA, json.encodeToString(token))
            .apply()
        Timber.i("Saved Strava token for athlete ${token.athleteId}")
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN_DATA).apply()
        Timber.i("Cleared Strava token")
    }

    /**
     * Check if the stored access token has expired.
     */
    fun isExpired(): Boolean {
        val token = getToken() ?: return true
        return System.currentTimeMillis() / 1000 >= token.expiresAt
    }

    /**
     * Get a valid access token, refreshing if expired.
     * Used by both the uploader and the UI when fetching bikes.
     */
    fun getValidAccessToken(api: StravaApi): String? {
        val token = getToken() ?: return null

        if (!isExpired()) {
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
                saveToken(
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
