package com.ridevault.karoo.upload

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

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
         * With All Files Access granted, file discovery is fast. A short
         * delay gives the OS time to finish writing and close the file.
         */
        const val SETTLE_DELAY_MS = 2000L
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

        // --- MediaStore fallback for K3 scoped storage ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Timber.i("Trying MediaStore fallback (API ${Build.VERSION.SDK_INT})")
            val mediaStoreResult = findViaMediaStore()
            if (mediaStoreResult != null) return mediaStoreResult
        }

        Timber.w("All methods failed to find a FIT file")
        return null
    }

    /**
     * Query MediaStore.Files for .fit files in the FitFiles directory.
     * On Android 10+ (API 29+), MediaStore is the correct way to access
     * files created by other apps. The Karoo system writes FIT files
     * which get indexed by MediaStore automatically.
     *
     * We copy the file to app cache via ContentResolver so the upload
     * code can read it with normal File I/O.
     */
    private fun findViaMediaStore(): File? {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri("external")

            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
            )

            // Look for .fit files in the FitFiles directory, sorted newest first
            val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("FitFiles/%", "%.fit")
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                Timber.i("MediaStore query returned ${cursor.count} results")

                if (cursor.count == 0) {
                    // Try broader search without path filter in case path format differs
                    Timber.i("Trying broader MediaStore query without path filter")
                    resolver.query(
                        collection,
                        projection,
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                        arrayOf("%.fit"),
                        sortOrder,
                    )?.use { broadCursor ->
                        Timber.i("Broad MediaStore query returned ${broadCursor.count} results")
                        val nameIdx = broadCursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val pathIdx = broadCursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                        var logged = 0
                        while (broadCursor.moveToNext() && logged < 5) {
                            Timber.i("  MediaStore .fit file: ${broadCursor.getString(pathIdx)}${broadCursor.getString(nameIdx)}")
                            logged++
                        }
                    }
                    return null
                }

                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)

                val now = System.currentTimeMillis()
                val cutoffSec = (now - MAX_AGE_MS) / 1000 // DATE_MODIFIED is in seconds

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx)
                    val dateMod = cursor.getLong(dateIdx)
                    val size = cursor.getLong(sizeIdx)
                    val path = cursor.getString(pathIdx)

                    Timber.i("MediaStore candidate: $path$name (id=$id, size=$size, date=$dateMod)")

                    if (dateMod < cutoffSec) {
                        Timber.w("MediaStore file too old: $name")
                        continue
                    }

                    // Copy via ContentResolver to app cache
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    val cacheDir = File(context.cacheDir, "fit_files")
                    cacheDir.mkdirs()
                    val cachedFile = File(cacheDir, name)

                    resolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(cachedFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (cachedFile.exists() && cachedFile.length() > 0) {
                        Timber.i("MediaStore copied FIT to cache: ${cachedFile.name} (${cachedFile.length()} bytes)")
                        return cachedFile
                    }
                }
            }

            Timber.w("MediaStore found no usable FIT files")
        } catch (e: Exception) {
            Timber.e(e, "MediaStore fallback failed")
        }
        return null
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
