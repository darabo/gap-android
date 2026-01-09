package com.gapmesh.droid.service

import android.content.Context
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Helper to start MeshForegroundService outside of BOOT_COMPLETED context.
 * 
 * On Android 15+, foreground services with `dataSync` type cannot be started
 * directly from BOOT_COMPLETED receivers. This uses WorkManager to schedule
 * immediate service start, which bypasses the restriction.
 */
object MeshServiceStarter {
    private const val WORK_NAME = "mesh_service_start"

    /**
     * Schedule immediate start of the mesh foreground service.
     * Uses WorkManager to avoid BOOT_COMPLETED FGS restrictions on Android 15+.
     */
    fun scheduleStart(context: Context) {
        val request = OneTimeWorkRequestBuilder<MeshServiceStartWorker>()
            .build()
        
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * Worker that starts the MeshForegroundService.
 * This runs outside the BOOT_COMPLETED broadcast context, allowing
 * foreground services with restricted types to be started on Android 15+.
 */
class MeshServiceStartWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {
    
    override fun doWork(): Result {
        return try {
            android.util.Log.d("MeshServiceStartWorker", "Starting MeshForegroundService via WorkManager")
            MeshForegroundService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("MeshServiceStartWorker", "Failed to start MeshForegroundService: ${e.message}")
            Result.failure()
        }
    }
}
