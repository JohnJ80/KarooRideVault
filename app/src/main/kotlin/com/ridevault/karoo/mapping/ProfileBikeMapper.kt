package com.ridevault.karoo.mapping

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

/**
 * Persists ride profile → Strava gear_id mappings.
 *
 * The first time a ride profile is used, the user picks which Strava bike
 * to tag on the upload confirmation screen. That choice is saved here so
 * future rides on the same profile auto-select the correct bike.
 *
 * Storage: SharedPreferences under "ridevault_profile_gear"
 *   Key:   ride profile name (e.g. "Road Domane")
 *   Value: Strava gear_id     (e.g. "b2390326")
 */
class ProfileBikeMapper(context: Context) {

    companion object {
        private const val PREFS_NAME = "ridevault_profile_gear"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Look up the saved Strava gear_id for a ride profile.
     * Returns null if this profile hasn't been mapped yet.
     */
    fun getGearIdForProfile(profileName: String): String? {
        val gearId = prefs.getString(profileName, null)
        if (gearId != null) {
            Timber.i("ProfileBikeMapper: '$profileName' → gear_id '$gearId'")
        }
        return gearId
    }

    /**
     * Save or update a profile → gear_id mapping.
     * Called after the user selects a bike on the upload confirmation screen.
     */
    fun saveProfileGearId(profileName: String, gearId: String) {
        val existing = prefs.getString(profileName, null)
        if (existing == gearId) {
            Timber.d("ProfileBikeMapper: '$profileName' → '$gearId' (already saved)")
            return
        }
        prefs.edit().putString(profileName, gearId).apply()
        if (existing != null) {
            Timber.i("ProfileBikeMapper: '$profileName' updated '$existing' → '$gearId'")
        } else {
            Timber.i("ProfileBikeMapper: '$profileName' → '$gearId' (new mapping)")
        }
    }

    /**
     * Remove a saved mapping (e.g. if the user reassigns bikes).
     */
    fun forgetProfile(profileName: String) {
        prefs.edit().remove(profileName).apply()
        Timber.i("ProfileBikeMapper: forgot '$profileName'")
    }

    /**
     * Get all saved mappings — useful for a settings/setup screen.
     * Returns Map<profileName, gearId>.
     */
    fun getAllMappings(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return prefs.all.filterValues { it is String } as Map<String, String>
    }

    /**
     * Clear all saved mappings.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Timber.i("ProfileBikeMapper: cleared all mappings")
    }
}
