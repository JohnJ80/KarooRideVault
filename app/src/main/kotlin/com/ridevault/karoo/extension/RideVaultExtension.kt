package com.ridevault.karoo.extension

import android.content.Intent
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import com.ridevault.karoo.mapping.SensorBikeMapper
import com.ridevault.karoo.strava.StravaTokenStore
import com.ridevault.karoo.strava.StravaUploader
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
import java.io.File

/**
 * RideVault Karoo Extension
 *
 * Runs silently after first-time setup. Monitors ride state to:
 * 1. Detect when a ride ends (Recording/Paused → Idle)
 * 2. Upload .FIT file to Strava with the correct gear_id (bike)
 * 3. Queue failed uploads for retry
 * 4. Trigger re-auth if Strava token is invalid
 */
class RideVaultExtension : KarooExtension("ridevault", "0.2.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var mapper: SensorBikeMapper
    private lateinit var tokenStore: StravaTokenStore
    private lateinit var uploader: StravaUploader
    private lateinit var fitFinder: FitFileFinder
    private lateinit var pendingStore: PendingUploadStore

    private var rideMonitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        mapper = SensorBikeMapper(this)
        tokenStore = StravaTokenStore(this)
        uploader = StravaUploader(this)
        fitFinder = FitFileFinder(this)
        pendingStore = PendingUploadStore(this)

        karooSystem.connect { connected ->
            if (connected) {
                Timber.i("RideVault connected to Karoo system")

                // Only show notification if setup is complete
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
     * trigger the upload flow with the configured bike.
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
     * Finds the most recent .FIT file, uploads to Strava with gear_id,
     * and retries any previously failed uploads.
     */
    private fun onRideEnded() {
        // Check if setup has been completed
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

        // Look up the configured bike
        val bikeMatch = mapper.getDefaultBike()
        val bikeName = bikeMatch?.bikeName ?: "Unknown bike"
        val gearId = bikeMatch?.gearId

        Timber.i("Ride ended — bike: $bikeName, gear_id: $gearId")

        // Launch upload on IO dispatcher
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
            } else {
                uploadFitFile(fitFile, gearId, bikeName)
            }

            // Also retry any pending uploads from previous failures
            retryPendingUploads()
        }
    }

    /**
     * Upload a single FIT file to Strava. On failure, queue it for retry.
     */
    private suspend fun uploadFitFile(fitFile: File, gearId: String?, bikeName: String) {
        Timber.i("Uploading ${fitFile.name} as $bikeName (gear_id=$gearId)")
        karooSystem.dispatch(
            SystemNotification(
                "rv-uploading",
                "Uploading to Strava…",
                subText = "$bikeName — ${fitFile.name}",
            ),
        )

        when (val result = uploader.upload(fitFile, gearId, bikeName)) {
            is StravaUploader.UploadResult.Success -> {
                fitFinder.markUploaded(fitFile)
                pendingStore.removePending(fitFile.name)
                Timber.i("Upload complete — activity ${result.activityId}")
                karooSystem.dispatch(
                    SystemNotification(
                        "rv-upload-ok",
                        "Ride uploaded to Strava ✓",
                        subText = "${result.bikeName} — activity ${result.activityId}",
                    ),
                )
            }
            is StravaUploader.UploadResult.Processing -> {
                fitFinder.markUploaded(fitFile)
                pendingStore.removePending(fitFile.name)
                Timber.i("Upload submitted, Strava still processing (id=${result.uploadId})")
                karooSystem.dispatch(
                    SystemNotification(
                        "rv-upload-ok",
                        "Ride sent to Strava",
                        subText = "$bikeName — processing on Strava",
                    ),
                )
            }
            is StravaUploader.UploadResult.Error -> {
                Timber.e("Upload failed: ${result.message}")
                pendingStore.addPending(
                    fitFile = fitFile,
                    gearId = gearId,
                    bikeName = bikeName,
                    failureReason = result.message,
                    needsAuth = result.isAuthFailure,
                )

                if (result.isAuthFailure) {
                    // Auth failed — prompt user to re-authenticate
                    karooSystem.dispatch(
                        SystemNotification(
                            "rv-auth-fail",
                            "Strava login expired",
                            subText = "Open RideVault to reconnect",
                            action = "Reconnect",
                            actionIntent = "com.ridevault.karoo.MAIN",
                        ),
                    )
                } else {
                    karooSystem.dispatch(
                        SystemNotification(
                            "rv-upload-fail",
                            "Strava upload failed",
                            subText = "${result.message} — will retry next ride",
                            action = "Retry",
                            actionIntent = "com.ridevault.karoo.MAIN",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Retry previously failed uploads. Called after each ride ends
     * and when the user opens RideVault and taps Retry.
     */
    private suspend fun retryPendingUploads() {
        val pending = pendingStore.loadPending()
        if (pending.isEmpty()) return

        // Skip retry if any pending uploads need re-auth
        if (pendingStore.hasAuthFailures() && !tokenStore.hasToken()) {
            Timber.i("Skipping pending retry — auth needed")
            return
        }

        Timber.i("Retrying ${pending.size} pending upload(s)")
        for (entry in pending) {
            val file = File(entry.fitFilePath)
            if (!file.exists()) {
                Timber.w("Pending file no longer exists: ${entry.fitFileName}")
                pendingStore.removePending(entry.fitFileName)
                continue
            }

            if (fitFinder.isAlreadyUploaded(file)) {
                Timber.i("Pending file already uploaded: ${entry.fitFileName}")
                pendingStore.removePending(entry.fitFileName)
                continue
            }

            uploadFitFile(file, entry.gearId, entry.bikeName ?: "Unknown bike")
        }
    }

    override fun onDestroy() {
        rideMonitorJob?.cancel()
        rideMonitorJob = null
        karooSystem.disconnect()
        super.onDestroy()
    }
}
