package com.ridevault.karoo.strava

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.ridevault.karoo.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Handles Strava OAuth callback.
 *
 * Flow:
 * 1. User taps "Connect to Strava" in MainActivity
 * 2. Opens Strava authorization URL in browser
 * 3. User authorizes → redirects to karooridevault://strava-auth?code=...
 * 4. This activity catches the redirect, exchanges code for tokens
 * 5. Saves tokens and finishes back to MainActivity
 */
class StravaAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data
        if (uri == null) {
            Timber.w("StravaAuthActivity opened without URI")
            finish()
            return
        }

        val code = uri.getQueryParameter("code")
        if (code == null) {
            val error = uri.getQueryParameter("error")
            Timber.e("Strava auth error: $error")
            finish()
            return
        }

        Timber.i("Received Strava auth code, exchanging for token...")

        val tokenStore = StravaTokenStore(this)
        val api = StravaApi(tokenStore)

        CoroutineScope(Dispatchers.IO).launch {
            val result = api.exchangeToken(
                clientId = BuildConfig.STRAVA_CLIENT_ID,
                clientSecret = BuildConfig.STRAVA_CLIENT_SECRET,
                code = code,
            )

            result.onSuccess { tokenResponse ->
                tokenStore.saveToken(
                    StravaTokenStore.TokenData(
                        accessToken = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken,
                        expiresAt = tokenResponse.expiresAt,
                        athleteId = tokenResponse.athlete?.id ?: 0,
                        athleteFirstName = tokenResponse.athlete?.firstName ?: "",
                    ),
                )
                Timber.i("Strava auth complete for ${tokenResponse.athlete?.firstName}")
            }

            result.onFailure { e ->
                Timber.e(e, "Failed to exchange Strava auth code")
            }

            finish()
        }
    }

    companion object {
        private const val STRAVA_AUTH_URL = "https://www.strava.com/oauth/authorize"
        private const val REDIRECT_URI = "http://localhost/ridevault-karoo"
        private const val SCOPE = "read,activity:write,activity:read_all"

        /**
         * Build the Strava authorization URL to open in a browser.
         */
        fun buildAuthUrl(): Uri {
            return Uri.parse(STRAVA_AUTH_URL)
                .buildUpon()
                .appendQueryParameter("client_id", BuildConfig.STRAVA_CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("approval_prompt", "auto")
                .appendQueryParameter("scope", SCOPE)
                .build()
        }
    }
}
