package com.ridevault.karoo.extension

import android.content.Intent
import android.os.Bundle
import com.ridevault.karoo.ui.MainActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.KarooSystemListener
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooSystem
import com.ridevault.karoo.mapping.SensorBikeMapper
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
 * The actual upload happens in the UI — the extension just
 * detects the ride end and launches the activity.
 */
class RideVaultExtension : KarooExtension("ridevault", "0.2.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var mapper: SensorBikeMapper
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
        mapper = SensorBikeMapper(this)
        tokenStore = StravaTokenStore(this)
        fitFinder = FitFileFinder(this)
        pendingStore = PendingUploadStore(this)

        karooSystem.connect { connected ->
            Timber.i("RideVault Karoo connected: $connected")
            if (connected) {
                // Track the active ride profile so we know which profile was used when ride ends
                karooSystem.addConsumer { profile: ActiveRideProfile ->
                    activeRideProfileName = profile.profile.name
                    Timber.i("Active ride profile: ${profile.profile.name} (type=${profile.profile.defaultActivityType})")
                }

                // DEBUG: Log raw JSON for ActiveRideProfile and Bikes to discover hidden fields
                // The SDK uses ignoreUnknownKeys=true, so extra fields (like bikeId) would be silently dropped.
                // This interceptor captures the raw payload before deserialization.
                logRawEventJson("ActiveRideProfile", ActiveRideProfile.Params.bundleWithSerializable(applicationContext.packageName))
                logRawEventJson("Bikes", Bikes.Params.bundleWithSerializable(applicationContext.packageName))

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
     *
     * Uses plain .collect (not .collectLatest) to match the working
     * karoo-ride-namer reference — collectLatest cancels the previous
     * collector on each new emission, which can swallow the Idle state.
     */
    private suspend fun monitorRideState() {
        karooSystem.streamRideState().collect { state ->
            Timber.i("RideVault ride state: $state (sessionActive=$sessionActive)")
            when (state) {
                is RideState.Recording -> {
                    if (!sessionActive) {
                        sessionActive = true
                        Timber.i("Ride started — session active")
                    }
                }
                is RideState.Paused -> {
                    if (!sessionActive) {
                        sessionActive = true
                        Timber.i("Ride paused (was not active) — marking active")
                    }
                }
                is RideState.Idle -> {
                    if (sessionActive) {
                        sessionActive = false
                        Timber.i("Ride ended — transitioning to onRideEnded()")
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
        Timber.i("onRideEnded() called — checking Strava token")
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
            Timber.i("Launching upload confirmation — file: ${fitFile.name}, rideProfile: ${activeRideProfileName ?: "unknown"}")

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

    /**
     * DEBUG: Register a raw IHandler to intercept the JSON payload from the Karoo system
     * BEFORE the SDK deserializes it (and drops unknown fields via ignoreUnknownKeys=true).
     * This lets us see if fields like bikeId exist in the actual data.
     */
    private fun logRawEventJson(eventName: String, paramsBundle: Bundle) {
        val listenerId = "raw-json-$eventName"
        karooSystem.addConsumer(object : KarooSystemListener(listenerId) {
            override fun register(controller: IKarooSystem?) {
                val rawHandler = object : IHandler.Stub() {
                    override fun onNext(bundle: Bundle) {
                        val rawJson = bundle.getString("value")
                        Timber.i("========== RAW JSON [$eventName] ==========")
                        Timber.i("$rawJson")
                        Timber.i("============================================")
                    }

                    override fun onError(msg: String?) {
                        Timber.w("RAW JSON [$eventName] error: $msg")
                    }

                    override fun onComplete() {
                        Timber.i("RAW JSON [$eventName] complete")
                    }
                }
                try {
                    controller?.addEventConsumer(listenerId, paramsBundle, rawHandler)
                    Timber.i("Raw JSON interceptor registered for $eventName")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to register raw JSON interceptor for $eventName")
                }
            }

            override fun unregister(controller: IKarooSystem?) {
                try {
                    controller?.removeEventConsumer(listenerId)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to unregister raw JSON interceptor for $eventName")
                }
            }
        })
    }

    override fun onDestroy() {
        rideMonitorJob?.cancel()
        rideMonitorJob = null
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
