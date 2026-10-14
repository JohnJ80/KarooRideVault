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
import com.ridevault.karoo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private var serviceJob: Job? = null
    private var detectedBikeName: String? = null
    private var detectedGearId: String? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        mapper = SensorBikeMapper(this)
        tokenStore = StravaTokenStore(this)

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
     * Finds the most recent .FIT file and queues it for Strava upload.
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
        karooSystem.dispatch(
            SystemNotification(
                "rv-ride-complete",
                getString(R.string.notification_ride_complete, bikeName),
                action = "Upload",
                actionIntent = "com.ridevault.karoo.MAIN",
            ),
        )

        // TODO: Find latest .FIT file and queue for upload
        // The actual upload will be triggered from the UI or auto-upload setting
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        serviceJob = null
        karooSystem.disconnect()
        super.onDestroy()
    }
}
