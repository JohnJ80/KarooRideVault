package com.ridevault.karoo.upload

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import timber.log.Timber
import java.io.File

/**
 * Finds .FIT files from completed rides and tracks upload history.
 *
 * On the Karoo, ride FIT files are saved to /sdcard/FitFiles/.
 * After a ride ends, we grab the most recent file and hand it off
 * to the Strava uploader. The upload history prevents re-uploading
 * the same ride if the extension restarts or the ride-end event
 * fires twice.
 */
class FitFileFinder(context: Context) {

    companion object {
        /** Karoo saves FIT files here after each ride */
        private val FIT_DIR = File(
            Environment.getExternalStorageDirectory(),
            "FitFiles",
        )
        private const val PREFS_NAME = "ridevault_uploads"
        private const val KEY_UPLOADED_FILES = "uploaded_files"

        /**
         * Don't look at FIT files older than this after a ride ends.
         * Rides rarely exceed 12 hours; anything older is a previous ride.
         */
        private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

        /**
         * Wait for the FIT file to be fully written.
         * The Karoo may still be flushing data when the ride-end event fires.
         * On K3, the Idle state fires before the FIT file is fully written —
         * 6 seconds gives enough headroom for both K2 and K3.
         */
        const val SETTLE_DELAY_MS = 6000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Find the most recently modified .FIT file in /sdcard/FitFiles/.
     * Returns null if no recent FIT file is found or if the directory
     * doesn't exist (shouldn't happen on Karoo, but defensive).
     */
    fun findLatestFitFile(): File? {
        // --- K3 diagnostics: log everything about directory access ---
        Timber.i("FIT_DIR path: ${FIT_DIR.absolutePath}")
        Timber.i("FIT_DIR.exists(): ${FIT_DIR.exists()}")
        Timber.i("FIT_DIR.isDirectory(): ${FIT_DIR.isDirectory}")
        Timber.i("FIT_DIR.canRead(): ${FIT_DIR.canRead()}")
        Timber.i("isExternalStorageManager: ${Environment.isExternalStorageManager()}")

        val allFiles = FIT_DIR.listFiles()
        Timber.i("FIT_DIR.listFiles() returned: ${allFiles?.size ?: "null"}")

        // Shell fallback: try ls if listFiles returns null/empty
        if (allFiles == null || allFiles.isEmpty()) {
            Timber.w("listFiles empty/null — trying shell ls fallback")
            try {
                val process = Runtime.getRuntime().exec(arrayOf("ls", "-la", FIT_DIR.absolutePath))
                val output = process.inputStream.bufferedReader().readText()
                val error = process.errorStream.bufferedReader().readText()
                process.waitFor()
                Timber.i("Shell ls output:\n$output")
                if (error.isNotBlank()) Timber.w("Shell ls stderr: $error")

                // Parse ls output to find .fit files
                val fitFiles = output.lines()
                    .filter { it.endsWith(".fit", ignoreCase = true) || it.endsWith(".FIT") }
                    .mapNotNull { line ->
                        // ls -la output: last field is the filename
                        val name = line.trim().split("\\s+".toRegex()).lastOrNull()
                        if (name != null) File(FIT_DIR, name) else null
                    }
                    .filter { it.exists() && it.length() > 0 }

                Timber.i("Shell fallback found ${fitFiles.size} .fit files")

                if (fitFiles.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val cutoff = now - MAX_AGE_MS
                    val latest = fitFiles
                        .filter { it.lastModified() > cutoff }
                        .maxByOrNull { it.lastModified() }

                    if (latest != null) {
                        Timber.i("Shell fallback found FIT file: ${latest.name} (${latest.length()} bytes)")
                        return latest
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Shell ls fallback failed")
            }
        }
        // --- end K3 diagnostics ---

        if (!FIT_DIR.exists() || !FIT_DIR.isDirectory) {
            Timber.w("FIT directory not found: ${FIT_DIR.absolutePath}")
            return null
        }

        val now = System.currentTimeMillis()
        val cutoff = now - MAX_AGE_MS

        val fitFile = FIT_DIR.listFiles { file ->
            file.isFile &&
                file.extension.equals("fit", ignoreCase = true) &&
                file.lastModified() > cutoff
        }
            ?.maxByOrNull { it.lastModified() }

        if (fitFile != null) {
            Timber.i("Found FIT file: ${fitFile.name} (${fitFile.length()} bytes)")
        } else {
            Timber.w("No recent FIT files found in ${FIT_DIR.absolutePath}")
        }

        return fitFile
    }

    /**
     * Check if a FIT file has already been uploaded to Strava.
     */
    fun isAlreadyUploaded(fitFile: File): Boolean {
        val uploaded = getUploadedFiles()
        return fitFile.name in uploaded
    }

    /**
     * Mark a FIT file as successfully uploaded.
     */
    fun markUploaded(fitFile: File) {
        val uploaded = getUploadedFiles().toMutableSet()
        uploaded.add(fitFile.name)

        // Keep only the last 100 entries to prevent unbounded growth
        val trimmed = if (uploaded.size > 100) {
            uploaded.toList().takeLast(100).toSet()
        } else {
            uploaded
        }

        prefs.edit()
            .putStringSet(KEY_UPLOADED_FILES, trimmed)
            .apply()
        Timber.i("Marked ${fitFile.name} as uploaded (${trimmed.size} total tracked)")
    }

    private fun getUploadedFiles(): Set<String> {
        return prefs.getStringSet(KEY_UPLOADED_FILES, emptySet()) ?: emptySet()
    }
}
