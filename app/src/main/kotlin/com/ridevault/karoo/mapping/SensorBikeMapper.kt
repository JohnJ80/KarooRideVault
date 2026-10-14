package com.ridevault.karoo.mapping

import android.content.Context
import io.hammerhead.karooext.models.SavedDevices.SavedDevice
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
     * Try to detect which bike is being used based on connected sensors.
     *
     * Matches enabled sensors against the mapping table.
     * Returns the first match (usually the power meter).
     */
    fun detectBike(
        enabledDevices: List<SavedDevice>,
        mapping: MappingFile,
    ): BikeMatch? {
        for (device in enabledDevices) {
            val match = mapping.mappings.find { it.sensorId == device.id }
            if (match != null) {
                return BikeMatch(
                    bikeName = match.bikeName,
                    gearId = match.gearId,
                    matchedSensorId = device.id,
                    matchedSensorName = device.name ?: match.sensorName,
                )
            }
        }
        return null
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

    /**
     * Check if there are any unmapped sensors in the enabled device list.
     */
    fun findUnmappedSensors(
        enabledDevices: List<SavedDevice>,
        mapping: MappingFile?,
    ): List<SavedDevice> {
        if (mapping == null) return enabledDevices
        val mappedIds = mapping.mappings.map { it.sensorId }.toSet()
        return enabledDevices.filter { it.id !in mappedIds }
    }
}
