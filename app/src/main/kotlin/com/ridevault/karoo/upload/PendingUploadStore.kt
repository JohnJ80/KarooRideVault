package com.ridevault.karoo.upload

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Tracks FIT files that failed to upload to Strava.
 *
 * When an upload fails (no connectivity, auth expired, Strava down),
 * the FIT file path and failure reason are stored here. On the next
 * ride end — or when the user opens RideVault — pending uploads
 * are retried automatically.
 */
class PendingUploadStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "ridevault_pending_uploads"
        private const val KEY_PENDING = "pending_uploads"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    @Serializable
    data class PendingUpload(
        val fitFilePath: String,
        val fitFileName: String,
        val gearId: String? = null,
        val bikeName: String? = null,
        val failureReason: String,
        val failedAtMs: Long,
        val attemptCount: Int = 1,
        val needsAuth: Boolean = false,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Add a failed upload to the pending queue.
     */
    fun addPending(
        fitFile: File,
        gearId: String?,
        bikeName: String?,
        failureReason: String,
        needsAuth: Boolean = false,
    ) {
        val pending = loadPending().toMutableList()

        // Don't duplicate — update existing entry for same file
        pending.removeAll { it.fitFileName == fitFile.name }

        pending.add(
            PendingUpload(
                fitFilePath = fitFile.absolutePath,
                fitFileName = fitFile.name,
                gearId = gearId,
                bikeName = bikeName,
                failureReason = failureReason,
                failedAtMs = System.currentTimeMillis(),
                needsAuth = needsAuth,
            ),
        )

        savePending(pending)
        Timber.i("Queued pending upload: ${fitFile.name} — $failureReason (needsAuth=$needsAuth)")
    }

    /**
     * Get all pending uploads.
     */
    fun loadPending(): List<PendingUpload> {
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<PendingUpload>>(raw)
        } catch (e: Exception) {
            Timber.e(e, "Failed to read pending uploads")
            emptyList()
        }
    }

    /**
     * Remove a successfully uploaded file from the pending queue.
     */
    fun removePending(fitFileName: String) {
        val pending = loadPending().toMutableList()
        pending.removeAll { it.fitFileName == fitFileName }
        savePending(pending)
        Timber.i("Removed $fitFileName from pending queue (${pending.size} remaining)")
    }

    /**
     * Increment attempt count for a pending upload.
     */
    fun incrementAttempt(fitFileName: String, newReason: String) {
        val pending = loadPending().toMutableList()
        val index = pending.indexOfFirst { it.fitFileName == fitFileName }
        if (index >= 0) {
            val entry = pending[index]
            pending[index] = entry.copy(
                attemptCount = entry.attemptCount + 1,
                failureReason = newReason,
                failedAtMs = System.currentTimeMillis(),
            )
            savePending(pending)
        }
    }

    /**
     * Check if any pending uploads need re-auth.
     */
    fun hasAuthFailures(): Boolean {
        return loadPending().any { it.needsAuth }
    }

    /**
     * Clear the needsAuth flag on all pending uploads (after successful re-auth).
     */
    fun clearAuthFlags() {
        val pending = loadPending().map { it.copy(needsAuth = false) }
        savePending(pending)
    }

    /**
     * Check if there are any pending uploads at all.
     */
    fun hasPending(): Boolean {
        return loadPending().isNotEmpty()
    }

    /**
     * Number of pending uploads.
     */
    fun pendingCount(): Int {
        return loadPending().size
    }

    private fun savePending(pending: List<PendingUpload>) {
        prefs.edit()
            .putString(KEY_PENDING, json.encodeToString(pending))
            .apply()
    }
}
