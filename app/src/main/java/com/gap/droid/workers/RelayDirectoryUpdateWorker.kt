package com.gapmesh.droid.workers

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gapmesh.droid.nostr.RelayDirectory
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based periodic worker that refreshes the geo-relay CSV from GitHub
 * every 24 hours. Replaces the previous in-process `CoroutineScope` polling loop
 * in RelayDirectory, which died with the process.
 *
 * Reference: Noghteha's RelayDirectoryUpdateWorker (CoroutineWorker with identical logic).
 */
class RelayDirectoryUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RelayDirUpdateWorker"
        private const val UNIQUE_WORK_NAME = "relay_directory_update"

        /**
         * Enqueue a periodic 24-hour relay directory refresh with a 6-hour flex window.
         * Uses KEEP policy so existing schedule is preserved across app restarts.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<RelayDirectoryUpdateWorker>(
                24, TimeUnit.HOURS,
                6, TimeUnit.HOURS   // flex window — can run anytime in last 6h of interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Scheduled periodic relay directory update (24h / 6h flex)")
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? Application
        if (app == null) {
            Log.e(TAG, "applicationContext is not an Application; failing")
            return Result.failure()
        }

        return try {
            Log.i(TAG, "Starting relay directory refresh…")
            val updated = RelayDirectory.refreshIfStale(app)
            if (updated) {
                Log.i(TAG, "✅ Relay directory updated successfully")
            } else {
                Log.i(TAG, "Relay directory still fresh; no update needed")
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Relay directory update failed: ${e.message}", e)
            Result.retry()
        }
    }
}
