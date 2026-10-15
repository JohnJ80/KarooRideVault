package com.ridevault.karoo.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridevault.karoo.BuildConfig
import com.ridevault.karoo.mapping.SensorBikeMapper
import com.ridevault.karoo.strava.StravaAuthActivity
import com.ridevault.karoo.strava.StravaTokenStore

/**
 * Main configuration UI for RideVault on Karoo.
 *
 * Provides:
 * - Strava connection status and OAuth flow
 * - Sensor-to-bike mapping management
 * - Recent upload history
 * - Settings (auto-upload toggle, etc.)
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tokenStore = StravaTokenStore(this)
        val mapper = SensorBikeMapper(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RideVaultMainScreen(
                        tokenStore = tokenStore,
                        mapper = mapper,
                        onConnectStrava = { connectStrava() },
                        onDisconnectStrava = {
                            tokenStore.clearToken()
                        },
                    )
                }
            }
        }
    }

    private fun connectStrava() {
        val authUrl = StravaAuthActivity.buildAuthUrl()
        val intent = Intent(Intent.ACTION_VIEW, authUrl)
        startActivity(intent)
    }
}

@Composable
fun RideVaultMainScreen(
    tokenStore: StravaTokenStore,
    mapper: SensorBikeMapper,
    onConnectStrava: () -> Unit,
    onDisconnectStrava: () -> Unit,
) {
    var isConnected by remember { mutableStateOf(tokenStore.hasToken()) }
    val token = tokenStore.getToken()
    val mapping = mapper.loadMapping()

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
                            }) {
                                Text("Disconnect")
                            }
                        } else {
                            Text(
                                text = "Connect to enable automatic ride uploads with correct bike tagging",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onConnectStrava) {
                                Text("Connect to Strava")
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
