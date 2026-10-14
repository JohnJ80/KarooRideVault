package com.ridevault.karoo.extension

import android.content.Intent
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.SystemNotification
import com.ridevault.karoo.R
import com.ridevault.karoo.mapping.SensorBikeMapper
import com.ridevault.karoo.strava.StravaTokenStore
import com.ridevault.karoo.strava.StravaUploader
import com.ridevault.karoo.upload.FitFileFinder
import com.ridevault.karoo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * RideVault Karoo Extension
 *
 * Monitors ride state and sensor connections to:
 * 1. Detect which bike is being ridden (via sensor-to-bike mapping)
 * 2. After ride ends, upload .FIT file to Strava with correct gear_id
 * 3. Handle stuck/failed uploads
 */
class RideVaultExtension : KarooExtension("ridevault", "0.1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var mapper: SensorBikeMapper
    private lateinit var tokenStore: StravaTokenStore
    private lateinit var uploader: StravaUploader
    private lateinit var fitFinder: FitFileFinder

    private var serviceJob: Job? = null
    private var detectedBikeName: String? = null
    private var detectedGearId: String? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        mapper = SensorBikeMapper(this)
        tokenStore = StravaTokenStore(this)
        uploader = StravaUploader(this)
        fitFinder = FitFileFinder(this)

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
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
            launch { monitorRideState() }

            // Monitor sensor connections for bike detection
            launch { monitorSensors() }
        }
    }

    /**
     * Watch ride state. When a ride ends (Recording/Paused → Idle),
     * trigger the upload flow with the detected bike.
     */
    private suspend fun monitorRideState() {
        var wasRiding = false
        karooSystem.consumerFlow<RideState>()
            .distinctUntilChanged()
            .collectLatest { state ->
                when (state) {
                    is RideState.Recording -> {
                        wasRiding = true
                        Timber.i("Ride started/resumed — detected bike: $detectedBikeName")
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
     * Watch connected sensors. When sensors are connected,
     * look up the mapping to determine which bike is being used.
     */
    private suspend fun monitorSensors() {
        karooSystem.consumerFlow<SavedDevices>()
            .collectLatest { savedDevices ->
                val enabledDevices = savedDevices.devices.filter { it.enabled }
                if (enabledDevices.isEmpty()) return@collectLatest

                val mapping = mapper.loadMapping()
                if (mapping == null) {
                    Timber.w("No sensor-bike mapping found")
                    return@collectLatest
                }

                // Try to match enabled sensors against our mapping
                val match = mapper.detectBike(enabledDevices, mapping)
                if (match != null) {
                    detectedBikeName = match.bikeName
                    detectedGearId = match.gearId
                    Timber.i("Bike detected: ${match.bikeName} (gear_id: ${match.gearId})")
                } else {
                    // Unknown sensors — notify user to set up mapping
                    val unknownIds = enabledDevices.map { it.id }
                    Timber.w("No bike match for sensors: $unknownIds")
                    detectedBikeName = null
                    detectedGearId = null

                    if (tokenStore.hasToken()) {
                        karooSystem.dispatch(
                            SystemNotification(
                                "rv-unknown-sensor",
                                getString(R.string.notification_unknown_sensor),
                                action = "Set up",
                                actionIntent = "com.ridevault.karoo.MAIN",
                            ),
                        )
                    }
                }
            }
    }

    /**
     * Called when a ride transitions to Idle after recording.
     * Finds the most recent .FIT file and uploads to Strava with gear_id.
     */
    private fun onRideEnded() {
        Timber.i("Ride ended — bike: $detectedBikeName, gear_id: $detectedGearId")

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

        val bikeName = detectedBikeName ?: "Unknown bike"
        val gearId = detectedGearId

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
                    // Strava is still processing — mark uploaded to avoid retries,
                    // it will appear on Strava shortly
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
        serviceJob?.cancel()
        serviceJob = null
        karooSystem.disconnect()
        super.onDestroy()
    }
}
