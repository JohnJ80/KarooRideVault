package com.ridevault.karoo.extension

import android.content.Intent
import com.ridevault.karoo.ui.MainActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
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
    private var activeRideProfileId: String? = null

    // The bike name resolved from the ride profile's ID — if the profile ID
    // matches a Bikes.Bike.id, we know which bike is assigned to this profile
    private var profileBikeName: String? = null

    // Bike odometer tracking — snapshot at ride start, compare at ride end
    // to infer which bike was ridden (the one whose odometer increased)
    private var currentBikes: List<Bikes.Bike> = emptyList()
    private var preRideOdometers: Map<String, Double> = emptyMap() // bike id → odometer
    private var inferredBikeName: String? = null

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
                // Track the active ride profile — log ALL exposed fields
                // including the id, which may reference a bike profile
                karooSystem.addConsumer { event: ActiveRideProfile ->
                    val profile = event.profile
                    activeRideProfileName = profile.name
                    activeRideProfileId = profile.id
                    Timber.i(
                        "Active ride profile: name='${profile.name}' id='${profile.id}' " +
                            "type=${profile.defaultActivityType} routing=${profile.routingPreference} " +
                            "indoor=${profile.indoor} pages=${profile.pages.size}",
                    )

                    // Check if profile.id matches any bike id — that would
                    // mean the profile's id IS the default bike reference
                    resolveProfileBike()
                }

                // Track all bike profiles — log IDs and names so we can
                // compare them against the ride profile ID
                karooSystem.addConsumer { event: Bikes ->
                    currentBikes = event.bikes
                    Timber.i("Karoo bikes: ${event.bikes.map { "id='${it.id}' name='${it.name}' odo=${it.odometer}m" }}")

                    // Re-check profile→bike mapping whenever bikes update
                    resolveProfileBike()
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
     * Try to resolve the ride profile's default bike by checking if the
     * profile ID matches a bike ID, or if the profile ID string appears
     * in any bike's id or name.
     */
    private fun resolveProfileBike() {
        val profileId = activeRideProfileId ?: return
        if (currentBikes.isEmpty()) return

        // Direct match: profile.id == bike.id
        val directMatch = currentBikes.find { it.id == profileId }
        if (directMatch != null) {
            profileBikeName = directMatch.name
            Timber.i("Profile→bike resolved via ID match: profile.id='$profileId' → bike='${directMatch.name}'")
            return
        }

        // No direct match — log for diagnostics
        Timber.i(
            "No profile→bike ID match. profile.id='$profileId', " +
                "bike IDs=${currentBikes.map { it.id }}",
        )
        profileBikeName = null
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
                        inferredBikeName = null
                        // Snapshot odometers at ride start so we can detect
                        // which bike's odometer increased when the ride ends
                        preRideOdometers = currentBikes.associate { it.id to it.odometer }
                        Timber.i("Ride started — snapshot odometers: ${currentBikes.map { "${it.name}=${it.odometer}m" }}")
                    }
                }
                is RideState.Paused -> {
                    if (!sessionActive) {
                        sessionActive = true
                        inferredBikeName = null
                        preRideOdometers = currentBikes.associate { it.id to it.odometer }
                        Timber.i("Ride paused (was not active) — snapshot odometers")
                    }
                }
                is RideState.Idle -> {
                    if (sessionActive) {
                        sessionActive = false
                        // Infer which bike was ridden by comparing odometers
                        inferredBikeName = inferBikeFromOdometer()
                        Timber.i("Ride ended — inferred bike: ${inferredBikeName ?: "unknown"}")
                        onRideEnded()
                    }
                }
            }
        }
    }

    /**
     * Compare current bike odometers against the pre-ride snapshot.
     * The bike whose odometer increased is the one that was just ridden.
     */
    private fun inferBikeFromOdometer(): String? {
        if (preRideOdometers.isEmpty() || currentBikes.isEmpty()) {
            Timber.w("Cannot infer bike — no odometer data (pre=${preRideOdometers.size}, current=${currentBikes.size})")
            return null
        }

        val changed = currentBikes.filter { bike ->
            val before = preRideOdometers[bike.id] ?: return@filter false
            bike.odometer > before
        }

        return when {
            changed.size == 1 -> {
                val bike = changed.first()
                val delta = bike.odometer - (preRideOdometers[bike.id] ?: 0.0)
                Timber.i("Inferred bike '${bike.name}' — odometer increased by ${delta}m")
                bike.name
            }
            changed.isEmpty() -> {
                Timber.w("No bike odometer changed — cannot infer bike")
                null
            }
            else -> {
                // Multiple odometers changed — pick the one with the largest increase
                val best = changed.maxByOrNull { it.odometer - (preRideOdometers[it.id] ?: 0.0) }
                Timber.w("Multiple odometers changed (${changed.map { it.name }}), picking '${best?.name}'")
                best?.name
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
            Timber.i(
                "Launching upload confirmation — file: ${fitFile.name}, " +
                    "profileBike=$profileBikeName, inferred=$inferredBikeName, " +
                    "rideProfile=$activeRideProfileName",
            )

            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("action", "confirm_upload")
                putExtra("fit_file_path", fitFile.absolutePath)
                putExtra("fit_file_name", fitFile.name)
                putExtra("ride_profile_name", activeRideProfileName)
                putExtra("profile_bike_name", profileBikeName)
                putExtra("inferred_bike_name", inferredBikeName)
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
