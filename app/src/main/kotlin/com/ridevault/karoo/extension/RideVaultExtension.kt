package com.ridevault.karoo.extension

import android.content.Intent
import android.os.Bundle
import com.ridevault.karoo.ui.MainActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.KarooSystemListener
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.util.UUID

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

    // The bike name extracted from the ride profile's raw JSON — the SDK's
    // RideProfile class drops this field because of ignoreUnknownKeys, but
    // the Karoo system app includes it in the JSON payload.
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
                // Track the active ride profile — we use a raw JSON consumer
                // to intercept the full payload from the Karoo system app,
                // because the SDK's RideProfile class drops the bike field
                // (ignoreUnknownKeys silently discards it during deserialization)
                addRawProfileConsumer()

                // Track all bike profiles — we use odometer changes to
                // infer which bike was ridden (the SDK doesn't expose
                // which bike is assigned to a ride profile)
                karooSystem.addConsumer { event: Bikes ->
                    currentBikes = event.bikes
                    Timber.i("Karoo bikes updated: ${event.bikes.map { "${it.name}(${it.odometer}m)" }}")
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
     * Register a raw JSON consumer for the ActiveRideProfile event.
     *
     * The Karoo system app sends the full ride profile JSON including a bike
     * field, but the SDK's RideProfile data class doesn't declare it and
     * DefaultJson's ignoreUnknownKeys=true silently drops it. By creating
     * our own IHandler and reading the raw bundle string, we can parse the
     * full JSON and extract the bike name the SDK hides.
     */
    private fun addRawProfileConsumer() {
        val consumerId = UUID.randomUUID().toString()
        val handler = object : IHandler.Stub() {
            override fun onNext(bundle: Bundle) {
                val json = bundle.getString("value")
                Timber.i("Raw ActiveRideProfile JSON: $json")
                if (json != null) {
                    try {
                        val root = Json.parseToJsonElement(json).jsonObject
                        // The profile object is nested under "profile"
                        val profile = root["profile"]?.jsonObject
                        activeRideProfileName = profile?.get("name")?.jsonPrimitive?.content
                        Timber.i("Active ride profile: $activeRideProfileName")

                        // Log ALL keys in the profile object to discover hidden fields
                        val profileKeys = profile?.keys ?: emptySet()
                        Timber.i("Profile JSON keys: $profileKeys")

                        // Look for bike-related fields — the Karoo may use any of these names
                        val bikeFieldNames = listOf(
                            "bike", "bikeId", "bike_id", "bikeName", "bike_name",
                            "defaultBike", "default_bike", "defaultBikeId", "default_bike_id",
                            "bikeProfile", "bike_profile", "bikeProfileId", "bike_profile_id",
                            "bikeProfileName", "bike_profile_name", "equipment", "equipmentId",
                        )
                        for (fieldName in bikeFieldNames) {
                            val value = profile?.get(fieldName)
                            if (value != null) {
                                Timber.i("Found bike field '$fieldName' = $value")
                                // Try to extract the bike name — could be a string or nested object
                                profileBikeName = try {
                                    value.jsonPrimitive.content
                                } catch (_: Exception) {
                                    // It's a nested object — look for a "name" field
                                    try {
                                        value.jsonObject["name"]?.jsonPrimitive?.content
                                    } catch (_: Exception) {
                                        value.toString()
                                    }
                                }
                                Timber.i("Profile default bike name: $profileBikeName")
                                break
                            }
                        }
                        if (profileBikeName == null) {
                            // Log the full profile JSON so we can inspect it
                            Timber.i("No known bike field found in profile. Full profile: $profile")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse raw ActiveRideProfile JSON")
                    }
                }
            }

            override fun onError(msg: String?) {
                Timber.w("ActiveRideProfile error: $msg")
            }

            override fun onComplete() {
                Timber.i("ActiveRideProfile stream complete")
            }
        }

        // Build the params bundle the same way the SDK does internally:
        // ActiveRideProfile.Params serializes to "{}" via DefaultJson
        val params = Bundle().apply {
            putString("value", "{}")
            putString("package", applicationContext.packageName)
        }

        karooSystem.addConsumer(object : KarooSystemListener(consumerId) {
            override fun register(controller: IKarooSystem?) {
                controller?.addEventConsumer(id, params, handler)
            }

            override fun unregister(controller: IKarooSystem?) {
                controller?.removeEventConsumer(id)
            }
        })
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
            Timber.i("Launching upload confirmation — file: ${fitFile.name}, rideProfile: ${activeRideProfileName ?: "unknown"}")

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
