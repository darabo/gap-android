package com.gapmesh.droid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Ensure preferences are initialized on cold boot before reading values
        try { MeshServicePreferences.init(context.applicationContext) } catch (_: Exception) { }

        // Do not start services when decoy mode is active
        if (DecoyModeManager.isDecoyActive(context.applicationContext)) {
            android.util.Log.i("BootCompletedReceiver", "Decoy mode active — skipping service start")
            return
        }

        if (MeshServicePreferences.isAutoStartEnabled(true)) {
            if (Build.VERSION.SDK_INT >= 35) {
                // Android 15+: Use WorkManager to start service outside BOOT_COMPLETED context
                // This avoids the restriction on starting dataSync foreground services from boot
                android.util.Log.d("BootCompletedReceiver", "Android 15+ detected, using WorkManager to start service")
                MeshServiceStarter.scheduleStart(context.applicationContext)
            } else {
                // Pre-Android 15: Direct service start is allowed
                MeshForegroundService.start(context.applicationContext)
            }
        }
    }
}
