package com.gapmesh.droid.ui.media

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.gapmesh.droid.features.media.ImageUtils
import java.io.File



@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePickerButton(
    modifier: Modifier = Modifier,
    onImageReady: (String) -> Unit
) {
    val context = LocalContext.current
    var capturedImagePath by remember { mutableStateOf<String?>(null) }
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val outPath = ImageUtils.downscaleAndSaveToAppFiles(context, uri)
            if (!outPath.isNullOrBlank()) onImageReady(outPath)
        }
    }
    
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val path = capturedImagePath
        if (success && !path.isNullOrBlank()) {
            // Downscale + correct orientation, then send; delete original
            val outPath = com.gapmesh.droid.features.media.ImageUtils.downscalePathAndSaveToAppFiles(context, path)
            if (!outPath.isNullOrBlank()) {
                onImageReady(outPath)
            }
            runCatching { File(path).delete() }
        } else {
            // Cleanup on cancel/failure
            path?.let { runCatching { File(it).delete() } }
        }
        capturedImagePath = null
    }

    fun startCameraCapture() {
        try {
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            capturedImagePath = file.absolutePath
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            android.util.Log.e("ImagePickerButton", "Camera capture failed", e)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraCapture()
        }
    }

    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(40.dp)
            .clickable { showMenu = true },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = stringResource(com.gapmesh.droid.R.string.pick_image),
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
        
        androidx.compose.material3.DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text(stringResource(com.gapmesh.droid.R.string.take_photo)) },
                onClick = {
                    showMenu = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startCameraCapture()
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                leadingIcon = { 
                    Icon(
                        imageVector = Icons.Filled.Camera,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    ) 
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text(stringResource(com.gapmesh.droid.R.string.choose_from_gallery)) },
                onClick = {
                    showMenu = false
                    imagePicker.launch("image/*")
                },
                leadingIcon = { 
                    Icon(
                        imageVector = Icons.Filled.Photo,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    ) 
                }
            )
        }
    }

    // No custom preview: native camera UI handles confirmation
}
