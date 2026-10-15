package com.ridevault.karoo.strava

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Persistent local cache of Strava bikes.
 *
 * The Strava API only returns bikes in GET /athlete when the token has
 * profile:read_all scope, which unverified apps may not get. The fallback
 * discovers bikes from recent activities, but that window is limited.
 *
 * This cache ensures that once a bike is seen — whether from the athlete
 * endpoint or the activities fallback — it stays available for selection
 * even after hundreds of rides on other bikes.
 *
 * Cache is keyed by athlete ID so it resets naturally on account switch.
 * Cleared explicitly on Strava disconnect (clearToken).
 */
class BikeCacheStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "ridevault_bike_cache"
        private const val KEY_PREFIX = "bikes_"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    data class CachedBike(
        val id: String,
        val name: String,
        val distance: Double = 0.0,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Merge newly discovered bikes into the cache.
     * New bikes are added; existing bikes have their name/distance updated.
     * Bikes not in the new list are kept (they may just not have been ridden recently).
     */
    fun mergeBikes(athleteId: Long, bikes: List<StravaApi.Bike>) {
        val existing = loadCached(athleteId).associateBy { it.id }.toMutableMap()
        for (bike in bikes) {
            existing[bike.id] = CachedBike(id = bike.id, name = bike.name, distance = bike.distance)
        }
        val merged = existing.values.toList()
        saveCached(athleteId, merged)
        Timber.i("Bike cache updated for athlete $athleteId: ${merged.size} bikes (${bikes.size} fresh)")
    }

    /**
     * Get all cached bikes for an athlete.
     */
    fun getCachedBikes(athleteId: Long): List<StravaApi.Bike> {
        return loadCached(athleteId).map {
            StravaApi.Bike(id = it.id, name = it.name, distance = it.distance)
        }
    }

    /**
     * Clear the cache (e.g. on Strava disconnect).
     */
    fun clear() {
        prefs.edit().clear().apply()
        Timber.i("Bike cache cleared")
    }

    private fun loadCached(athleteId: Long): List<CachedBike> {
        val data = prefs.getString("$KEY_PREFIX$athleteId", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<CachedBike>>(data)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read bike cache")
            emptyList()
        }
    }

    private fun saveCached(athleteId: Long, bikes: List<CachedBike>) {
        prefs.edit()
            .putString("$KEY_PREFIX$athleteId", json.encodeToString(bikes))
            .apply()
    }
}
