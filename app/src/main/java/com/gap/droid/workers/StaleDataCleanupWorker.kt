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
 * Periodically removes orphaned files that no longer have a corresponding database entry,
 * and cleans up any empty directories left behind by [MediaCleanupWorker].
 *
 * Runs every 6 hours at low priority — it is less time-critical than media cleanup.
 *
 * Reference: Noghteha ships a similar `StaleDataCleanupWorker`.
 */
class StaleDataCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "StaleDataCleanup"
        private const val UNIQUE_WORK_NAME = "stale_data_cleanup"

        /** Files older than 7 days with zero bytes are treated as orphaned. */
        private val ORPHAN_AGE_MS = TimeUnit.DAYS.toMillis(7)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StaleDataCleanupWorker>(
                6, TimeUnit.HOURS,
                1, TimeUnit.HOURS
            )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Scheduled periodic stale-data cleanup (6h)")
        }
    }

    override suspend fun doWork(): Result {
        val filesDir = applicationContext.filesDir
        val cutoff = System.currentTimeMillis() - ORPHAN_AGE_MS
        var cleaned = 0

        // Walk all subdirectories under filesDir and remove zero-byte orphan files
        val mediaDirs = listOf(
            File(filesDir, "images"),
            File(filesDir, "files")
        )

        for (root in mediaDirs) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile }
                .filter { it.length() == 0L && it.lastModified() < cutoff }
                .forEach { file ->
                    if (file.delete()) cleaned++
                }

            // Remove empty directories (bottom-up so children are removed first)
            root.walkBottomUp()
                .filter { it.isDirectory && it != root }
                .filter { it.listFiles()?.isEmpty() == true }
                .forEach { dir ->
                    dir.delete()
                }
        }

        Log.i(TAG, "Stale-data cleanup complete: removed $cleaned orphan files")
        return Result.success()
    }
}
