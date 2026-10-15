package com.ridevault.karoo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main UI for RideVault on Karoo.
 *
 * Three modes:
 * - First run: setup wizard (Strava auth → manual upload reminder)
 * - Upload confirmation: after ride ends, shows bike & Upload button
 * - Dashboard: status, pending uploads, retry, connection, mapping
 *
 * Launched from:
 * - Settings → Apps → RideVault (first-time setup / dashboard)
 * - RideVaultExtension after ride ends (upload confirmation)
 * - Notification action (auth failure, upload failure, retry)
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val STORAGE_PERMISSION_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure we have storage permission for reading FIT files
        ensureStoragePermission()

        val tokenStore = StravaTokenStore(this)
        val mapper = SensorBikeMapper(this)
        val api = StravaApi(tokenStore)
        val pendingStore = PendingUploadStore(this)
        val uploader = StravaUploader(this)
        val fitFinder = FitFileFinder(this)
        val activity = this

        // Check if launched with upload confirmation intent
        val action = intent.getStringExtra("action")
        val fitFilePath = intent.getStringExtra("fit_file_path")
        val fitFileName = intent.getStringExtra("fit_file_name")
        val initialGearId = intent.getStringExtra("gear_id")
        val initialBikeName = intent.getStringExtra("bike_name") ?: "Unknown bike"

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (action == "confirm_upload" && fitFilePath != null && fitFileName != null) {
                        // Ride just ended — show upload confirmation
                        UploadConfirmationScreen(
                            fitFilePath = fitFilePath,
                            fitFileName = fitFileName,
                            initialGearId = initialGearId,
                            initialBikeName = initialBikeName,
                            tokenStore = tokenStore,
                            api = api,
                            uploader = uploader,
                            fitFinder = fitFinder,
                            pendingStore = pendingStore,
                            onDone = { activity.finish() },
                            onNeedsAuth = {
                                // Will be handled inside the composable
                            },
                        )
                    } else {
                        // Normal launch — setup or dashboard
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

    private fun ensureStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.i("Requesting READ_EXTERNAL_STORAGE permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST,
            )
        }
    }
}

// ── Upload Confirmation (after ride ends) ──────────────────────

@Composable
fun UploadConfirmationScreen(
    fitFilePath: String,
    fitFileName: String,
    initialGearId: String?,
    initialBikeName: String,
    tokenStore: StravaTokenStore,
    api: StravaApi,
    uploader: StravaUploader,
    fitFinder: FitFileFinder,
    pendingStore: PendingUploadStore,
    onDone: () -> Unit,
    onNeedsAuth: () -> Unit,
) {
    var selectedGearId by remember { mutableStateOf(initialGearId) }
    var selectedBikeName by remember { mutableStateOf(initialBikeName) }
    var bikes by remember { mutableStateOf<List<StravaApi.Bike>>(emptyList()) }
    var loadingBikes by remember { mutableStateOf(true) }
    var uploading by remember { mutableStateOf(false) }
    var uploadDone by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var showAuthScreen by remember { mutableStateOf(false) }
    var bikeMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Fetch the user's Strava bikes on first load
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val token = tokenStore.getToken()
            if (token != null) {
                val result = api.getAthlete(token.accessToken)
                result.onSuccess { athlete ->
                    bikes = athlete.bikes
                    Timber.i("Loaded ${athlete.bikes.size} bikes from Strava")
                }
                result.onFailure { e ->
                    Timber.w(e, "Failed to load bikes — using detected bike only")
                }
            }
            loadingBikes = false
        }
    }

    // Auth screen overlay
    if (showAuthScreen) {
        StravaAuthScreen(
            relayUrl = BuildConfig.AUTH_RELAY_URL,
            api = api,
            tokenStore = tokenStore,
            onConnected = {
                showAuthScreen = false
                uploadError = null
                // Reload bikes after re-auth
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val token = tokenStore.getToken()
                        if (token != null) {
                            api.getAthlete(token.accessToken).onSuccess { athlete ->
                                bikes = athlete.bikes
                            }
                        }
                    }
                }
            },
            onCancel = {
                showAuthScreen = false
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Title
        Text(
            text = "Upload Ride",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // FIT file info
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Ride file",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = fitFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bike selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Bike",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (loadingBikes) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Loading bikes…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    // Current selection — tap to change
                    Box {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = bikes.isNotEmpty()) {
                                    bikeMenuExpanded = true
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = selectedBikeName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                if (bikes.size > 1) {
                                    Text(
                                        text = "Change ▾",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }

                        // Dropdown menu for bike selection
                        DropdownMenu(
                            expanded = bikeMenuExpanded,
                            onDismissRequest = { bikeMenuExpanded = false },
                        ) {
                            bikes.forEach { bike ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = bike.name,
                                            fontWeight = if (bike.id == selectedGearId) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        )
                                    },
                                    onClick = {
                                        selectedGearId = bike.id
                                        selectedBikeName = bike.name
                                        bikeMenuExpanded = false
                                        Timber.i("User selected bike: ${bike.name} (${bike.id})")
                                    },
                                )
                            }
                            // Option to upload with no bike
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "No bike",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    selectedGearId = null
                                    selectedBikeName = "No bike"
                                    bikeMenuExpanded = false
                                },
                            )
                        }
                    }

                    if (bikes.isEmpty() && selectedGearId == null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No bikes found on Strava. Upload will proceed without a bike tag.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Upload status / error
        if (uploadError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Upload failed",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = uploadError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (uploadDone) {
            // Success state
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "✓ Uploaded to Strava",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = selectedBikeName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        } else {
            // Action buttons
            Button(
                onClick = {
                    uploading = true
                    uploadError = null
                    scope.launch {
                        val fitFile = File(fitFilePath)
                        if (!fitFile.exists()) {
                            uploadError = "FIT file not found"
                            uploading = false
                            return@launch
                        }

                        val result = withContext(Dispatchers.IO) {
                            uploader.upload(fitFile, selectedGearId, selectedBikeName)
                        }

                        when (result) {
                            is StravaUploader.UploadResult.Success -> {
                                fitFinder.markUploaded(fitFile)
                                uploadDone = true
                                Timber.i("Upload success — activity ${result.activityId}")
                            }
                            is StravaUploader.UploadResult.Processing -> {
                                // Still processing but accepted — treat as success
                                fitFinder.markUploaded(fitFile)
                                uploadDone = true
                                Timber.i("Upload accepted — processing (id ${result.uploadId})")
                            }
                            is StravaUploader.UploadResult.Error -> {
                                if (result.isAuthFailure) {
                                    uploadError = "Strava auth expired — please reconnect"
                                    // Queue for retry too
                                    pendingStore.addPending(
                                        fitFile = fitFile,
                                        gearId = selectedGearId,
                                        bikeName = selectedBikeName,
                                        failureReason = result.message,
                                        needsAuth = true,
                                    )
                                } else {
                                    uploadError = result.message
                                    pendingStore.addPending(
                                        fitFile = fitFile,
                                        gearId = selectedGearId,
                                        bikeName = selectedBikeName,
                                        failureReason = result.message,
                                    )
                                }
                            }
                        }
                        uploading = false
                    }
                },
                enabled = !uploading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uploading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text("Uploading…")
                    }
                } else {
                    Text("Upload to Strava")
                }
            }

            // Reconnect button if auth failed
            if (uploadError != null && uploadError!!.contains("auth", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showAuthScreen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reconnect Strava")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    // Save as pending so user can retry later from dashboard
                    val fitFile = File(fitFilePath)
                    if (fitFile.exists() && !uploadDone) {
                        pendingStore.addPending(
                            fitFile = fitFile,
                            gearId = selectedGearId,
                            bikeName = selectedBikeName,
                            failureReason = "Skipped by user",
                        )
                    }
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Upload Later")
            }
        }
    }
}

// ── Normal app flow (setup or dashboard) ───────────────────────

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
