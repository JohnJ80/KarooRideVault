package com.ridevault.karoo.extension

import android.content.Intent
import com.ridevault.karoo.ui.MainActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import com.ridevault.karoo.strava.StravaTokenStore
import com.ridevault.karoo.upload.FitFileFinder
import com.ridevault.karoo.upload.PendingUploadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 * Bike selection is handled entirely in the upload UI — the user
 * picks which Strava bike to tag, and that choice is saved per
 * ride profile by ProfileBikeMapper. Future rides on the same
 * profile auto-select the saved bike.
 */
class RideVaultExtension : KarooExtension("ridevault", "0.2.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var tokenStore: StravaTokenStore
    private lateinit var fitFinder: FitFileFinder
    private lateinit var pendingStore: PendingUploadStore

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var rideMonitorJob: Job? = null
    private var sessionActive = false
    private var activeRideProfileName: String? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        tokenStore = StravaTokenStore(this)
        fitFinder = FitFileFinder(this)
        pendingStore = PendingUploadStore(this)

        karooSystem.connect { connected ->
            Timber.i("RideVault Karoo connected: $connected")
            if (connected) {
                // Track the active ride profile
                karooSystem.addConsumer { event: ActiveRideProfile ->
                    val profile = event.profile
                    activeRideProfileName = profile.name
                    Timber.i(
                        "Active ride profile: name='${profile.name}' id='${profile.id}' " +
                            "type=${profile.defaultActivityType} indoor=${profile.indoor}",
                    )
                }

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
        rideMonitorJob = scope.launch {
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
        karooSystem.streamRideState().collect { state ->
            Timber.i("RideVault ride state: $state (sessionActive=$sessionActive)")
            when (state) {
                is RideState.Recording -> {
                    if (!sessionActive) {
                        sessionActive = true
                        Timber.i("Ride started on profile: $activeRideProfileName")
                    }
                }
                is RideState.Paused -> {
                    if (!sessionActive) {
                        sessionActive = true
                        Timber.i("Ride paused (was not active) on profile: $activeRideProfileName")
                    }
                }
                is RideState.Idle -> {
                    if (sessionActive) {
                        sessionActive = false
                        Timber.i("Ride ended on profile: $activeRideProfileName")
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

        Timber.i("Strava token present — waiting for FIT file to settle")
        scope.launch {
            // Wait for the Karoo to finish writing the FIT file
            delay(FitFileFinder.SETTLE_DELAY_MS)
            Timber.i("FIT settle delay complete — searching for FIT file")

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
            Timber.i("Found FIT file: ${fitFile.name} (${fitFile.length()} bytes)")

            if (fitFinder.isAlreadyUploaded(fitFile)) {
                Timber.i("FIT file ${fitFile.name} already uploaded — skipping")
                return@launch
            }

            // Launch the upload confirmation screen
            Timber.i("Launching upload confirmation — file: ${fitFile.name}, profile=$activeRideProfileName")

            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("action", "confirm_upload")
                putExtra("fit_file_path", fitFile.absolutePath)
                putExtra("fit_file_name", fitFile.name)
                putExtra("ride_profile_name", activeRideProfileName)
            }
            try {
                applicationContext.startActivity(intent)
                Timber.i("Upload confirmation activity launched successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch upload confirmation activity")
            }
        }
    }

    override fun onDestroy() {
        rideMonitorJob?.cancel()
        rideMonitorJob = null
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
