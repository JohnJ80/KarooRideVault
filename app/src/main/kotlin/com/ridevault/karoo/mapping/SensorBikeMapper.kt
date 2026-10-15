package com.ridevault.karoo.mapping

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Maps sensor IDs to Strava bikes (gear_ids).
 *
 * Each bike has at least one unique sensor (usually a power meter).
 * This mapper maintains a local JSON file that maps sensor IDs to
 * Strava gear_ids, enabling automatic bike detection per ride.
 *
 * Self-healing: if the mapping file is lost (device reset, update),
 * unknown sensors trigger a notification prompting the user to
 * reassign — one tap per bike.
 */
class SensorBikeMapper(private val context: Context) {

    companion object {
        private const val MAPPING_FILE = "sensor_bike_mapping.json"
        private const val DEFAULT_BIKE_PREF = "default_bike_gear_id"
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }

    /**
     * A single sensor-to-bike mapping entry.
     */
    @Serializable
    data class SensorMapping(
        val sensorId: String,
        val sensorName: String,
        val bikeName: String,
        val gearId: String,
    )

    /**
     * The full mapping file — list of sensor-to-bike assignments.
     */
    @Serializable
    data class MappingFile(
        val version: Int = 1,
        val mappings: List<SensorMapping> = emptyList(),
    )

    /**
     * Result of a bike detection attempt.
     */
    data class BikeMatch(
        val bikeName: String,
        val gearId: String,
        val matchedSensorId: String,
        val matchedSensorName: String,
    )

    /**
     * Load the mapping file from local storage.
     * Returns null if no mapping exists yet.
     */
    fun loadMapping(): MappingFile? {
        val file = File(context.filesDir, MAPPING_FILE)
        if (!file.exists()) {
            Timber.d("No mapping file found at ${file.absolutePath}")
            return null
        }
        return try {
            val content = file.readText()
            json.decodeFromString<MappingFile>(content)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read mapping file")
            null
        }
    }

    /**
     * Save the mapping file to local storage.
     */
    fun saveMapping(mapping: MappingFile) {
        val file = File(context.filesDir, MAPPING_FILE)
        try {
            file.writeText(json.encodeToString(mapping))
            Timber.i("Saved ${mapping.mappings.size} sensor-bike mappings")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save mapping file")
        }
    }

    /**
     * Get the default bike to use for uploads.
     *
     * Returns the first mapping entry, or null if no mappings exist.
     * The user can configure their default bike in the UI.
     */
    fun getDefaultBike(): BikeMatch? {
        val mapping = loadMapping() ?: return null
        val defaultGearId = context.getSharedPreferences("ridevault", Context.MODE_PRIVATE)
            .getString(DEFAULT_BIKE_PREF, null)

        // If user set a default, use that
        if (defaultGearId != null) {
            val match = mapping.mappings.find { it.gearId == defaultGearId }
            if (match != null) {
                return BikeMatch(
                    bikeName = match.bikeName,
                    gearId = match.gearId,
                    matchedSensorId = match.sensorId,
                    matchedSensorName = match.sensorName,
                )
            }
        }

        // Otherwise use the first mapping
        val first = mapping.mappings.firstOrNull() ?: return null
        return BikeMatch(
            bikeName = first.bikeName,
            gearId = first.gearId,
            matchedSensorId = first.sensorId,
            matchedSensorName = first.sensorName,
        )
    }

    /**
     * Set the default bike for uploads.
     */
    fun setDefaultBike(gearId: String) {
        context.getSharedPreferences("ridevault", Context.MODE_PRIVATE)
            .edit()
            .putString(DEFAULT_BIKE_PREF, gearId)
            .apply()
    }

    /**
     * Add or update a sensor-to-bike mapping.
     */
    fun assignSensorToBike(
        sensorId: String,
        sensorName: String,
        bikeName: String,
        gearId: String,
    ) {
        val current = loadMapping() ?: MappingFile()
        val updated = current.copy(
            mappings = current.mappings
                .filter { it.sensorId != sensorId }
                .plus(
                    SensorMapping(
                        sensorId = sensorId,
                        sensorName = sensorName,
                        bikeName = bikeName,
                        gearId = gearId,
                    ),
                ),
        )
        saveMapping(updated)
        Timber.i("Assigned sensor $sensorName ($sensorId) → $bikeName ($gearId)")
    }

    /**
     * Remove a sensor mapping.
     */
    fun removeSensorMapping(sensorId: String) {
        val current = loadMapping() ?: return
        val updated = current.copy(
            mappings = current.mappings.filter { it.sensorId != sensorId },
        )
        saveMapping(updated)
    }
}
