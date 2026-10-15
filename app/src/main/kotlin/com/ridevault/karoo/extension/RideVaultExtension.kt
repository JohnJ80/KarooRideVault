package com.ridevault.karoo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import com.ridevault.karoo.R
import com.ridevault.karoo.mapping.SensorBikeMapper
import com.ridevault.karoo.strava.StravaTokenStore
import com.ridevault.karoo.strava.StravaUploader
import com.ridevault.karoo.upload.FitFileFinder
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
 * Monitors ride state to:
 * 1. Detect when a ride ends
 * 2. Upload .FIT file to Strava with the correct gear_id (bike)
 * 3. Handle stuck/failed uploads
 *
 * Bike selection uses the user's configured default bike mapping.
 */
class RideVaultExtension : KarooExtension("ridevault", "0.1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var mapper: SensorBikeMapper
    private lateinit var tokenStore: StravaTokenStore
    private lateinit var uploader: StravaUploader
    private lateinit var fitFinder: FitFileFinder

    private var rideMonitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        mapper = SensorBikeMapper(this)
        tokenStore = StravaTokenStore(this)
        uploader = StravaUploader(this)
        fitFinder = FitFileFinder(this)

        karooSystem.connect { connected ->
            if (connected) {
                Timber.i("RideVault connected to Karoo system")
                karooSystem.dispatch(
                    SystemNotification(
                        "rv-started",
                        "RideVault active",
                        action = "Open",
                        actionIntent = "com.ridevault.karoo.MAIN",
                    ),
                )
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
     * Finds the most recent .FIT file and uploads to Strava with gear_id.
     */
    private fun onRideEnded() {
        // Look up the configured bike
        val bikeMatch = mapper.getDefaultBike()
        val bikeName = bikeMatch?.bikeName ?: "Unknown bike"
        val gearId = bikeMatch?.gearId

        Timber.i("Ride ended — bike: $bikeName, gear_id: $gearId")

        if (!tokenStore.hasToken()) {
            Timber.w("Strava not connected — skipping upload")
            karooSystem.dispatch(
                SystemNotification(
                    "rv-no-strava",
                    "Ride saved but Strava not connected",
                    subText = "Connect Strava in RideVault to auto-upload",
                    action = "Connect",
                    actionIntent = "com.ridevault.karoo.MAIN",
                ),
            )
            return
        }

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
                return@launch
            }

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
                    karooSystem.dispatch(
                        SystemNotification(
                            "rv-upload-fail",
                            "Strava upload failed",
                            subText = result.message,
                            action = "Retry",
                            actionIntent = "com.ridevault.karoo.MAIN",
                        ),
                    )
                }
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
