package com.gapmesh.droid.ui

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detects screenshot events by observing the MediaStore content provider.
 */
class ScreenshotDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onScreenshotDetected: () -> Unit
) {

    private var contentObserver: ContentObserver? = null
    
    // Debounce: track last screenshot time to prevent duplicates
    private var lastScreenshotTime: Long = 0
    private val DEBOUNCE_INTERVAL_MS = 3000L // 3 seconds

    fun start() {
        if (contentObserver != null) return

        val handler = Handler(Looper.getMainLooper())
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri != null) {
                    handleContentChange(uri)
                }
            }
        }

        val contentResolver = context.contentResolver
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    contentObserver!!
                )
            } else {
                contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    contentObserver!!
                )
            }
        } catch (e: Exception) {
            Log.e("ScreenshotDetector", "Failed to register content observer", e)
        }
    }

    fun stop() {
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    private fun handleContentChange(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                // Query the content resolver to inspect the file
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DATA // For path check
                    ),
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameColumn = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        val dateColumn = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                        val pathColumn = it.getColumnIndex(MediaStore.Images.Media.DATA)

                        val name = if (nameColumn >= 0) it.getString(nameColumn) else ""
                        val dateAdded = if (dateColumn >= 0) it.getLong(dateColumn) else 0L
                        val path = if (pathColumn >= 0) it.getString(pathColumn) else ""
                        
                        val currentTime = System.currentTimeMillis() / 1000
                        
                        // Check if it's recent (within last 10 seconds)
                        if (Math.abs(currentTime - dateAdded) <= 10) {
                            // Check name or path for "screenshot" keyword
                            if (name.contains("screenshot", ignoreCase = true) || 
                                path.contains("screenshot", ignoreCase = true)) {
                                
                                // Debounce check: ignore if within 3 seconds of last detection
                                val now = System.currentTimeMillis()
                                if (now - lastScreenshotTime < DEBOUNCE_INTERVAL_MS) {
                                    Log.d("ScreenshotDetector", "Debounced duplicate screenshot detection")
                                    return@launch
                                }
                                lastScreenshotTime = now
                                
                                withContext(Dispatchers.Main) {
                                    onScreenshotDetected()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenshotDetector", "Error handling content change", e)
            }
        }
    }
}
