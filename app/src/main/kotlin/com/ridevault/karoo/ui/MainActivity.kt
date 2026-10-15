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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridevault.karoo.BuildConfig
import com.ridevault.karoo.mapping.SensorBikeMapper
import com.ridevault.karoo.strava.StravaApi
import com.ridevault.karoo.strava.StravaTokenStore
import com.ridevault.karoo.strava.StravaUploader
import com.ridevault.karoo.upload.FitFileFinder
import com.ridevault.karoo.upload.PendingUploadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main UI for RideVault on Karoo.
 *
 * Two modes:
 * - First run: setup wizard (Strava auth → manual upload reminder)
 * - After setup: status dashboard (pending uploads, retry, connection, mapping)
 *
 * Launched from:
 * - Settings → Apps → RideVault (first-time setup)
 * - Notification action (auth failure, upload failure, retry)
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tokenStore = StravaTokenStore(this)
        val mapper = SensorBikeMapper(this)
        val api = StravaApi(tokenStore)
        val pendingStore = PendingUploadStore(this)
        val uploader = StravaUploader(this)
        val fitFinder = FitFileFinder(this)
        val activity = this

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RideVaultMainScreen(
                        tokenStore = tokenStore,
                        mapper = mapper,
                        api = api,
                        pendingStore = pendingStore,
                        uploader = uploader,
                        fitFinder = fitFinder,
                        onFinish = { activity.finish() },
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
    pendingStore: PendingUploadStore,
    uploader: StravaUploader,
    fitFinder: FitFileFinder,
    onFinish: () -> Unit,
) {
    var isConnected by remember { mutableStateOf(tokenStore.hasToken()) }
    var showAuthScreen by remember { mutableStateOf(false) }

    // After auth completes, track whether we came from a re-auth prompt
    var authJustCompleted by remember { mutableStateOf(false) }

    if (showAuthScreen) {
        StravaAuthScreen(
            relayUrl = BuildConfig.AUTH_RELAY_URL,
            api = api,
            tokenStore = tokenStore,
            onConnected = {
                isConnected = true
                showAuthScreen = false
                authJustCompleted = true
                // Clear auth-failure flags on pending uploads
                pendingStore.clearAuthFlags()
            },
            onCancel = {
                showAuthScreen = false
            },
        )
        return
    }

    if (!isConnected) {
        // First-run setup: connect Strava
        FirstRunScreen(
            onConnectStrava = { showAuthScreen = true },
        )
    } else {
        // Normal dashboard: status, pending uploads, settings
        DashboardScreen(
            tokenStore = tokenStore,
            mapper = mapper,
            pendingStore = pendingStore,
            uploader = uploader,
            fitFinder = fitFinder,
            onReconnectStrava = { showAuthScreen = true },
            onDisconnectStrava = {
                tokenStore.clearToken()
                isConnected = false
            },
            onFinish = onFinish,
        )
    }
}

// ── First-run setup ─────────────────────────────────────────────

@Composable
fun FirstRunScreen(
    onConnectStrava: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "RideVault",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Auto-upload rides to Strava with the correct bike tagged.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Before you start",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "In Hammerhead Dashboard → Activity Upload Mode, set Strava to \"Manual\". " +
                        "RideVault will handle uploads with the correct bike.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onConnectStrava) {
            Text("Connect to Strava")
        }
    }
}

// ── Dashboard (after setup) ─────────────────────────────────────

@Composable
fun DashboardScreen(
    tokenStore: StravaTokenStore,
    mapper: SensorBikeMapper,
    pendingStore: PendingUploadStore,
    uploader: StravaUploader,
    fitFinder: FitFileFinder,
    onReconnectStrava: () -> Unit,
    onDisconnectStrava: () -> Unit,
    onFinish: () -> Unit,
) {
    val token = tokenStore.getToken()
    val mapping = mapper.loadMapping()
    var pendingUploads by remember { mutableStateOf(pendingStore.loadPending()) }
    var isRetrying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Strava connection status
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Strava",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        if (token != null) {
                            Text(
                                text = "Connected as ${token.athleteFirstName}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = onReconnectStrava,
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text("Reconnect", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(
                                    onClick = onDisconnectStrava,
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text("Disconnect", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            Text(
                                text = "Not connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onReconnectStrava) {
                                Text("Connect to Strava")
                            }
                        }
                    }
                }
            }

            // Pending uploads
            if (pendingUploads.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${pendingUploads.size} pending upload${if (pendingUploads.size > 1) "s" else ""}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                if (isRetrying) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Button(
                                        onClick = {
                                            isRetrying = true
                                            scope.launch {
                                                retryAllPending(
                                                    pendingStore = pendingStore,
                                                    uploader = uploader,
                                                    fitFinder = fitFinder,
                                                )
                                                pendingUploads = pendingStore.loadPending()
                                                isRetrying = false
                                            }
                                        },
                                        modifier = Modifier.height(32.dp),
                                    ) {
                                        Text("Retry all", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            pendingUploads.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                ) {
                                    Column {
                                        Text(
                                            text = entry.fitFileName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Text(
                                            text = "${entry.failureReason} · ${dateFormat.format(Date(entry.failedAtMs))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                                Divider(color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f))
                            }
                        }
                    }
                }
            }

            // Bike mapping
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Bike Mapping",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        if (mapping == null || mapping.mappings.isEmpty()) {
                            Text(
                                text = "No bikes configured yet. This will be set up after your first ride.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            mapping.mappings.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.sensorName,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = "→ ${entry.bikeName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Divider()
                            }
                        }
                    }
                }
            }

            // Done button — close the activity
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

/**
 * Retry all pending uploads from the dashboard.
 */
private suspend fun retryAllPending(
    pendingStore: PendingUploadStore,
    uploader: StravaUploader,
    fitFinder: FitFileFinder,
) {
    val pending = pendingStore.loadPending()
    for (entry in pending) {
        val file = File(entry.fitFilePath)
        if (!file.exists()) {
            pendingStore.removePending(entry.fitFileName)
            continue
        }
        if (fitFinder.isAlreadyUploaded(file)) {
            pendingStore.removePending(entry.fitFileName)
            continue
        }

        val result = withContext(Dispatchers.IO) {
            uploader.upload(file, entry.gearId, entry.bikeName)
        }
        when (result) {
            is StravaUploader.UploadResult.Success,
            is StravaUploader.UploadResult.Processing -> {
                fitFinder.markUploaded(file)
                pendingStore.removePending(entry.fitFileName)
            }
            is StravaUploader.UploadResult.Error -> {
                pendingStore.incrementAttempt(entry.fitFileName, result.message)
            }
        }
    }
}
