package com.ridevault.karoo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridevault.karoo.BuildConfig
import com.ridevault.karoo.mapping.SensorBikeMapper
import com.ridevault.karoo.strava.StravaApi
import com.ridevault.karoo.strava.StravaAuthActivity
import com.ridevault.karoo.strava.StravaTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main configuration UI for RideVault on Karoo.
 *
 * Strava auth uses a manual code-entry flow since the Karoo
 * doesn't have the Strava app and its browser is limited:
 * 1. User opens the auth URL on their phone
 * 2. Authorizes with Strava
 * 3. Copies the code from the redirect URL
 * 4. Enters it here on the Karoo
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tokenStore = StravaTokenStore(this)
        val mapper = SensorBikeMapper(this)
        val api = StravaApi(tokenStore)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RideVaultMainScreen(
                        tokenStore = tokenStore,
                        mapper = mapper,
                        api = api,
                        onDisconnectStrava = {
                            tokenStore.clearToken()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun RideVaultMainScreen(
    tokenStore: StravaTokenStore,
    mapper: SensorBikeMapper,
    api: StravaApi,
    onDisconnectStrava: () -> Unit,
) {
    var isConnected by remember { mutableStateOf(tokenStore.hasToken()) }
    val token = tokenStore.getToken()
    val mapping = mapper.loadMapping()

    // Auth flow state
    var showAuthFlow by remember { mutableStateOf(false) }
    var authCode by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }
    var isExchanging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val authUrl = StravaAuthActivity.buildAuthUrl().toString()

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "RideVault",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Automated Strava gear tagging for Karoo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Strava Connection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Strava",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isConnected && token != null) {
                            Text(
                                text = "Connected as ${token.athleteFirstName}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                onDisconnectStrava()
                                isConnected = false
                                showAuthFlow = false
                            }) {
                                Text("Disconnect")
                            }
                        } else if (!showAuthFlow) {
                            Text(
                                text = "Connect to enable automatic ride uploads with correct bike tagging",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { showAuthFlow = true }) {
                                Text("Connect to Strava")
                            }
                        } else {
                            // Manual code-entry auth flow
                            Text(
                                text = "Step 1: Open this URL on your phone:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = authUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Step 2: Authorize RideVault on Strava",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Step 3: Copy the \"code\" from the redirect URL and paste it below:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "The URL will look like: http://localhost/...?code=COPY_THIS_PART",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = authCode,
                                onValueChange = {
                                    authCode = it
                                    authError = null
                                },
                                label = { Text("Authorization code") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isExchanging,
                            )

                            if (authError != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = authError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        val code = authCode.trim()
                                        if (code.isEmpty()) {
                                            authError = "Enter the authorization code"
                                            return@Button
                                        }
                                        isExchanging = true
                                        authError = null
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                api.exchangeToken(
                                                    clientId = BuildConfig.STRAVA_CLIENT_ID,
                                                    clientSecret = BuildConfig.STRAVA_CLIENT_SECRET,
                                                    code = code,
                                                )
                                            }
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
                                                isConnected = true
                                                showAuthFlow = false
                                                authCode = ""
                                            }
                                            result.onFailure { e ->
                                                authError = "Failed: ${e.message}"
                                            }
                                            isExchanging = false
                                        }
                                    },
                                    enabled = !isExchanging,
                                ) {
                                    if (isExchanging) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.padding(end = 8.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                    Text("Submit")
                                }
                                OutlinedButton(
                                    onClick = {
                                        showAuthFlow = false
                                        authCode = ""
                                        authError = null
                                    },
                                    enabled = !isExchanging,
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            // Sensor-Bike Mapping
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Sensor → Bike Mapping",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (mapping == null || mapping.mappings.isEmpty()) {
                            Text(
                                text = "No mappings configured yet. Start a ride with your sensors connected — RideVault will detect them and ask you to assign each to a Strava bike.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            mapping.mappings.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            text = entry.sensorName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = entry.sensorId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = "→ ${entry.bikeName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Divider()
                            }
                        }
                    }
                }
            }

            // Status / Info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "How it works",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "1. Disable Strava sync in Karoo settings\n" +
                                "2. Connect your Strava account above\n" +
                                "3. Ride — RideVault detects your bike from connected sensors\n" +
                                "4. After the ride, RideVault uploads to Strava with the correct bike tagged\n\n" +
                                "Hammerhead dashboard sync continues normally.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
