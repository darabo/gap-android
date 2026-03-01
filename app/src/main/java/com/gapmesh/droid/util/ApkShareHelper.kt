package com.gapmesh.droid.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Helper to share the app's installed APK with other devices.
 *
 * On the **light** build this shares the ~4.5 MB light APK itself.
 * On the **full** build it shares the full APK (larger but still functional).
 *
 * Flow:
 *   1. Copy the installed APK from `applicationInfo.sourceDir` into the
 *      app-internal cache (so FileProvider can serve it).
 *   2. Create a content:// URI via FileProvider.
 *   3. Launch an ACTION_SEND chooser so the user can pick Bluetooth,
 *      Nearby Share, or any other file transport.
 */
object ApkShareHelper {

    private const val LIGHT_APK_FILE_NAME = "gapmesh-light.apk"
    private const val FULL_APK_FILE_NAME  = "gapmesh-full.apk"

    /**
     * Shares the current app's APK as the **light** variant via the system share sheet.
     *
     * @param context  Activity or application context.
     */
    fun shareApk(context: Context) {
        shareApkAs(context, LIGHT_APK_FILE_NAME, "Share Gap Mesh Light APK")
    }

    /**
     * Shares the current app's APK as the **full** variant via the system share sheet.
     *
     * @param context  Activity or application context.
     */
    fun shareFullApk(context: Context) {
        shareApkAs(context, FULL_APK_FILE_NAME, "Share Gap Mesh Full APK")
    }

    private fun shareApkAs(context: Context, fileName: String, chooserTitle: String) {
        val sourceApk = File(context.applicationInfo.sourceDir)
        val cacheDir = File(context.cacheDir, "shared_apk").apply { mkdirs() }
        val destApk = File(cacheDir, fileName)

        // Copy the APK into our cache directory (FileProvider can only serve
        // paths that are declared in file_paths.xml – "cache-path" covers this).
        sourceApk.copyTo(destApk, overwrite = true)

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destApk
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, apkUri)
            putExtra(Intent.EXTRA_SUBJECT, "Gap Mesh")
            putExtra(
                Intent.EXTRA_TEXT,
                "Install Gap Mesh — decentralized mesh messaging with end-to-end encryption."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, chooserTitle)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
