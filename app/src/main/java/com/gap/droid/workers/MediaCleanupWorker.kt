package com.gapmesh.droid.workers

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodically deletes **outgoing** media files older than 1 hour.
 *
 * Outgoing files are copies the user selected for sending — once they've been
 * transmitted over BLE/Nostr the on-disk copy is no longer needed and keeping it
 * around increases the forensic attack surface.
 *
 * Incoming files are left untouched (the user may still want to view them).
 *
 * Reference: Noghteha ships similar `MessageCleanupWorker` logic.
 */
class MediaCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MediaCleanupWorker"
        private const val UNIQUE_WORK_NAME = "media_cleanup"

        /** Maximum age of outgoing files before they are deleted (1 hour). */
        private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(1)

        /**
         * Enqueue a periodic 1-hour media cleanup.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MediaCleanupWorker>(
                1, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Scheduled periodic media cleanup (1h)")
        }
    }

    override suspend fun doWork(): Result {
        val filesDir = applicationContext.filesDir
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS

        val outgoingDirs = listOf(
            File(filesDir, "images/outgoing"),
            File(filesDir, "files/outgoing")
        )

        var deleted = 0
        var errors = 0

        for (dir in outgoingDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    if (file.delete()) {
                        deleted++
                    } else {
                        errors++
                    }
                }
            }
        }

        // Also clean the cache directory of stale temp files (relay downloads, etc.)
        applicationContext.cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                if (file.delete()) deleted++
            }
        }

        Log.i(TAG, "Media cleanup complete: deleted=$deleted, errors=$errors")
        return Result.success()
    }
}
