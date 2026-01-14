package com.gap.droid.features.apk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.gap.droid.model.BitchatFilePacket
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Manager for sharing the installed APK file with other devices
 * Enables offline distribution of the app via Bluetooth mesh
 */
object ApkSharingManager {
    
    private const val TAG = "ApkSharingManager"
    private const val APK_SHARE_DIR = "apk_share"
    
    /**
     * Get the installed APK file information
     */
    fun getInstalledApkInfo(context: Context): ApkInfo? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val sourceDir = context.applicationInfo.sourceDir
            val apkFile = File(sourceDir)
            
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found at: $sourceDir")
                return null
            }
            
            ApkInfo(
                fileName = "${context.packageName}-v${packageInfo.versionName}.apk",
                filePath = sourceDir,
                fileSize = apkFile.length(),
                versionName = packageInfo.versionName ?: "unknown",
                versionCode = packageInfo.longVersionCode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get APK info", e)
            null
        }
    }
    
    /**
     * Copy APK to shareable location and return File
     * This is needed because the installed APK location may not be accessible
     */
    fun prepareApkForSharing(context: Context): File? {
        return try {
            val apkInfo = getInstalledApkInfo(context) ?: return null
            val sourceFile = File(apkInfo.filePath)
            
            // Create share directory in cache
            val shareDir = File(context.cacheDir, APK_SHARE_DIR)
            if (!shareDir.exists()) {
                shareDir.mkdirs()
            }
            
            // Copy APK to shareable location
            val destFile = File(shareDir, apkInfo.fileName)
            
            // Copy file if it doesn't exist or is different size
            if (!destFile.exists() || destFile.length() != sourceFile.length()) {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "APK copied to: ${destFile.absolutePath}")
            } else {
                Log.d(TAG, "APK already exists at: ${destFile.absolutePath}")
            }
            
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare APK for sharing", e)
            null
        }
    }
    
    /**
     * Create a BitchatFilePacket from the installed APK
     * This allows sending the APK through the mesh network
     */
    fun createApkFilePacket(context: Context): BitchatFilePacket? {
        return try {
            val apkFile = prepareApkForSharing(context) ?: return null
            val apkInfo = getInstalledApkInfo(context) ?: return null
            
            // Read APK content
            val content = apkFile.readBytes()
            
            BitchatFilePacket(
                fileName = apkInfo.fileName,
                fileSize = apkInfo.fileSize,
                mimeType = "application/vnd.android.package-archive",
                content = content
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create APK file packet", e)
            null
        }
    }
    
    /**
     * Share APK using Android share intent (for WiFi Direct, Bluetooth, etc.)
     * This uses the system share dialog for maximum compatibility
     */
    fun shareApkViaIntent(context: Context): Intent? {
        return try {
            val apkFile = prepareApkForSharing(context) ?: return null
            
            // Get shareable URI using FileProvider
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            
            // Create share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, apkUri)
                putExtra(Intent.EXTRA_SUBJECT, "Gap Mesh APK")
                putExtra(Intent.EXTRA_TEXT, "Install Gap Mesh - Offline encrypted messaging app")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            Intent.createChooser(shareIntent, "Share Gap Mesh APK via...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create share intent", e)
            null
        }
    }
    
    /**
     * Get formatted APK size string
     */
    fun getFormattedApkSize(context: Context): String {
        val apkInfo = getInstalledApkInfo(context) ?: return "Unknown"
        return formatFileSize(apkInfo.fileSize)
    }
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    /**
     * Clean up old APK copies from cache
     */
    fun cleanupOldApkCopies(context: Context) {
        try {
            val shareDir = File(context.cacheDir, APK_SHARE_DIR)
            if (shareDir.exists()) {
                shareDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".apk")) {
                        val ageMillis = System.currentTimeMillis() - file.lastModified()
                        // Delete APK files older than 1 hour
                        if (ageMillis > 60 * 60 * 1000) {
                            file.delete()
                            Log.d(TAG, "Deleted old APK copy: ${file.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old APK copies", e)
        }
    }
    
    /**
     * Data class containing APK information
     */
    data class ApkInfo(
        val fileName: String,
        val filePath: String,
        val fileSize: Long,
        val versionName: String,
        val versionCode: Long
    )
}
