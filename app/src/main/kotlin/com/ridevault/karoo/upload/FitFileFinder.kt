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
class FitFileFinder(private val context: Context) {

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
     *
     * On K3 (Android API 32), scoped storage hides other apps' files
     * from the Java File API even though the directory is readable.
     * We first try the normal listFiles() path (works on K2), and if
     * that finds no .fit files we fall back to shell commands to list
     * and copy the file into our app cache where Java can read it.
     */
    fun findLatestFitFile(): File? {
        Timber.i("FIT_DIR path: ${FIT_DIR.absolutePath}")
        Timber.i("isExternalStorageManager: ${Environment.isExternalStorageManager()}")

        // --- Try normal Java File API first (works on K2) ---
        if (FIT_DIR.exists() && FIT_DIR.isDirectory) {
            val now = System.currentTimeMillis()
            val cutoff = now - MAX_AGE_MS

            val fitFile = FIT_DIR.listFiles { file ->
                file.isFile &&
                    file.extension.equals("fit", ignoreCase = true) &&
                    file.lastModified() > cutoff
            }
                ?.maxByOrNull { it.lastModified() }

            if (fitFile != null) {
                Timber.i("Found FIT file via File API: ${fitFile.name} (${fitFile.length()} bytes)")
                return fitFile
            }

            val allCount = FIT_DIR.listFiles()?.size ?: 0
            Timber.w("File API found 0 .fit files ($allCount total items) — trying shell fallback")
        }

        // --- Shell fallback for K3 scoped storage ---
        return findViaShell()
    }

    /**
     * Use shell commands to find the newest .fit file and copy it to
     * app cache. Shell processes bypass Android's scoped storage filter,
     * so they can see files that listFiles() hides.
     */
    private fun findViaShell(): File? {
        try {
            // List .fit files sorted newest-first
            val lsProcess = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "ls -t ${FIT_DIR.absolutePath}/*.fit 2>/dev/null"),
            )
            val lsOutput = lsProcess.inputStream.bufferedReader().readText().trim()
            val lsError = lsProcess.errorStream.bufferedReader().readText().trim()
            lsProcess.waitFor()

            if (lsOutput.isBlank()) {
                Timber.w("Shell ls found no .fit files. stderr: $lsError")
                return null
            }

            // First line is the newest file (ls -t sorts by mtime)
            val newestPath = lsOutput.lines().first().trim()
            Timber.i("Shell found newest .fit: $newestPath")

            // Copy to app cache so Java File API can read it for upload
            val cacheDir = File(context.cacheDir, "fit_files")
            cacheDir.mkdirs()
            val srcName = File(newestPath).name
            val cachedFile = File(cacheDir, srcName)

            val cpProcess = Runtime.getRuntime().exec(
                arrayOf("cp", newestPath, cachedFile.absolutePath),
            )
            cpProcess.waitFor()

            if (cachedFile.exists() && cachedFile.length() > 0) {
                Timber.i("Shell copied FIT file to cache: ${cachedFile.name} (${cachedFile.length()} bytes)")

                // Check age — don't return stale files
                val now = System.currentTimeMillis()
                val cutoff = now - MAX_AGE_MS
                if (cachedFile.lastModified() < cutoff) {
                    Timber.w("Cached FIT file is too old (${cachedFile.lastModified()} < $cutoff)")
                    cachedFile.delete()
                    return null
                }

                return cachedFile
            } else {
                Timber.w("Shell cp failed — cached file missing or empty")
                return null
            }
        } catch (e: Exception) {
            Timber.e(e, "Shell fallback failed")
            return null
        }
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
