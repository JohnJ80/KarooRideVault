package com.ridevault.karoo.mapping

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

/**
 * Learns and persists ride profile → Karoo bike name mappings.
 *
 * The Karoo SDK doesn't expose which bike is assigned to a ride profile,
 * so we learn it: when odometer inference detects which bike was ridden
 * on a given profile, we save that association. Future rides on the same
 * profile get instant bike selection without waiting for odometer changes.
 *
 * Storage: SharedPreferences under "ridevault_profile_bikes"
 *   Key:   ride profile name (e.g. "Road Domane")
 *   Value: Karoo bike name  (e.g. "Trek Domane+")
 */
class ProfileBikeMapper(context: Context) {

    companion object {
        private const val PREFS_NAME = "ridevault_profile_bikes"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Look up the learned bike for a ride profile.
     * Returns null if we haven't learned this profile's bike yet.
     */
    fun getBikeForProfile(profileName: String): String? {
        val bikeName = prefs.getString(profileName, null)
        if (bikeName != null) {
            Timber.i("ProfileBikeMapper: '$profileName' → '$bikeName' (learned)")
        }
        return bikeName
    }

    /**
     * Save or update a profile → bike mapping.
     * Called when odometer inference successfully identifies the bike
     * ridden on a given profile.
     */
    fun learnProfileBike(profileName: String, bikeName: String) {
        val existing = prefs.getString(profileName, null)
        if (existing == bikeName) {
            Timber.d("ProfileBikeMapper: '$profileName' → '$bikeName' (already known)")
            return
        }
        prefs.edit().putString(profileName, bikeName).apply()
        if (existing != null) {
            Timber.i("ProfileBikeMapper: '$profileName' updated '$existing' → '$bikeName'")
        } else {
            Timber.i("ProfileBikeMapper: '$profileName' → '$bikeName' (new mapping)")
        }
    }

    /**
     * Remove a learned mapping (e.g. if the user reassigns bikes).
     */
    fun forgetProfile(profileName: String) {
        prefs.edit().remove(profileName).apply()
        Timber.i("ProfileBikeMapper: forgot '$profileName'")
    }

    /**
     * Get all learned mappings — useful for a settings/debug screen.
     */
    fun getAllMappings(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return prefs.all.filterValues { it is String } as Map<String, String>
    }

    /**
     * Clear all learned mappings.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Timber.i("ProfileBikeMapper: cleared all mappings")
    }
}
