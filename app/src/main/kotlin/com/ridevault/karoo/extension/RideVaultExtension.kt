package com.ridevault.karoo.extension

import android.content.Intent
import com.ridevault.karoo.ui.MainActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import com.ridevault.karoo.mapping.SensorBikeMapper
import com.ridevault.karoo.strava.StravaTokenStore
import com.ridevault.karoo.upload.FitFileFinder
import com.ridevault.karoo.upload.PendingUploadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * RideVault Karoo Extension
 *
 * Runs silently after first-time setup. Monitors ride state to:
 * 1. Detect when a ride ends (Recording/Paused → Idle)
 * 2. Find the .FIT file
 * 3. Launch the upload confirmation screen so the user can
 *    verify the bike and tap Upload
 *
 * The actual upload happens in the UI — the extension just
 * detects the ride end and launches the activity.
 */
class RideVaultExtension : KarooExtension("ridevault", "0.2.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var mapper: SensorBikeMapper
    private lateinit var tokenStore: StravaTokenStore
    private lateinit var fitFinder: FitFileFinder
    private lateinit var pendingStore: PendingUploadStore

    private var rideMonitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        mapper = SensorBikeMapper(this)
        tokenStore = StravaTokenStore(this)
        fitFinder = FitFileFinder(this)
        pendingStore = PendingUploadStore(this)

        karooSystem.connect { connected ->
            if (connected) {
                Timber.i("RideVault connected to Karoo system")

                if (tokenStore.hasToken()) {
                    val pendingCount = pendingStore.pendingCount()
                    val subText = if (pendingCount > 0) {
                        "$pendingCount pending upload${if (pendingCount > 1) "s" else ""}"
                    } else {
                        "Monitoring rides"
                    }
                    karooSystem.dispatch(
                        SystemNotification(
                            "rv-started",
                            "RideVault active",
                            subText = subText,
                        ),
                    )
                }
            }
        }

        // Monitor ride state transitions
        rideMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            monitorRideState()
        }
    }

    /**
     * Create a Flow of RideState updates using the karoo-ext addConsumer API.
     */
    private fun KarooSystemService.streamRideState(): Flow<RideState> {
        return callbackFlow {
            val listenerId = addConsumer { rideState: RideState ->
                trySendBlocking(rideState)
            }
            awaitClose {
                removeConsumer(listenerId)
            }
        }
    }

    /**
     * Watch ride state. When a ride ends (Recording/Paused -> Idle),
     * find the FIT file and launch the upload confirmation screen.
     */
    private suspend fun monitorRideState() {
        var wasRiding = false
        karooSystem.streamRideState()
            .distinctUntilChanged()
            .collectLatest { state ->
                when (state) {
                    is RideState.Recording -> {
                        wasRiding = true
                        val bike = mapper.getDefaultBike()
                        Timber.i("Ride started/resumed — configured bike: ${bike?.bikeName ?: "none"}")
                    }
                    is RideState.Paused -> {
                        wasRiding = true
                    }
                    is RideState.Idle -> {
                        if (wasRiding) {
                            wasRiding = false
                            onRideEnded()
                        }
                    }
                }
            }
    }

    /**
     * Called when a ride transitions to Idle after recording.
     * Waits for the FIT file to settle, then launches the
     * upload confirmation UI.
     */
    private fun onRideEnded() {
        if (!tokenStore.hasToken()) {
            Timber.w("Strava not connected — prompting setup")
            karooSystem.dispatch(
                SystemNotification(
                    "rv-no-strava",
                    "Ride saved — connect Strava to upload",
                    subText = "Open RideVault to set up",
                    action = "Set up",
                    actionIntent = "com.ridevault.karoo.MAIN",
                ),
            )
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Wait for the Karoo to finish writing the FIT file
            delay(FitFileFinder.SETTLE_DELAY_MS)

            val fitFile = fitFinder.findLatestFitFile()
            if (fitFile == null) {
                Timber.w("No FIT file found after ride")
                karooSystem.dispatch(
                    SystemNotification(
                        "rv-no-fit",
                        "Ride ended but no FIT file found",
                        subText = "Check that rides are being saved",
                    ),
                )
                return@launch
            }

            if (fitFinder.isAlreadyUploaded(fitFile)) {
                Timber.i("FIT file ${fitFile.name} already uploaded — skipping")
                return@launch
            }

            // Launch the upload confirmation screen
            val bikeMatch = mapper.getDefaultBike()
            Timber.i("Launching upload confirmation — file: ${fitFile.name}, bike: ${bikeMatch?.bikeName}")

            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("action", "confirm_upload")
                putExtra("fit_file_path", fitFile.absolutePath)
                putExtra("fit_file_name", fitFile.name)
                putExtra("gear_id", bikeMatch?.gearId)
                putExtra("bike_name", bikeMatch?.bikeName ?: "Unknown bike")
            }
            try {
                applicationContext.startActivity(intent)
                Timber.i("Upload confirmation activity launched")
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch upload confirmation activity")
            }
        }
    }

    override fun onDestroy() {
        rideMonitorJob?.cancel()
        rideMonitorJob = null
        karooSystem.disconnect()
        super.onDestroy()
    }
}
