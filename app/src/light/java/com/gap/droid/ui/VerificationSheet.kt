package com.gapmesh.droid.ui

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gapmesh.droid.R
import com.gapmesh.droid.services.VerificationService
import com.gapmesh.droid.core.ui.component.button.CloseButton
import com.gapmesh.droid.core.ui.component.sheet.BitchatBottomSheet
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Light-build VerificationSheet — uses Camera2 (native) + ZXing (already included)
 * for live QR scanning. Zero additional APK size cost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return

    val isDark = isSystemInDarkTheme()
    val accent = if (isDark) Color.Green else Color(0xFF008000)
    val boxColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f)

    var showingScanner by remember { mutableStateOf(false) }
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val npub = remember {
        viewModel.getCurrentNpub()
    }

    val qrString = remember(nickname, npub) {
        viewModel.buildMyQRString(nickname, npub)
    }

    BitchatBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VerificationHeader(
                accent = accent,
                onClose = onDismiss
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (showingScanner) {
                        QRScannerPanel(
                            accent = accent,
                            boxColor = boxColor,
                            onScan = { code ->
                                val qr = VerificationService.verifyScannedQR(code)
                                if (qr != null && viewModel.beginQRVerification(qr)) {
                                    showingScanner = false
                                }
                            }
                        )
                    } else {
                        MyQrPanel(
                            qrString = qrString,
                            accent = accent,
                            boxColor = boxColor,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ToggleVerificationModeButton(
                    showingScanner = showingScanner,
                    onToggle = { showingScanner = !showingScanner }
                )

                val peerID by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
                val fingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
                if (peerID != null) {
                    val fingerprint = viewModel.meshService.getPeerFingerprint(peerID!!)
                    if (fingerprint != null && fingerprints.contains(fingerprint)) {
                        Button(
                            onClick = { viewModel.unverifyFingerprint(peerID!!) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.verify_remove),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationHeader(
    accent: Color,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.verify_title).uppercase(),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = accent
        )
        CloseButton(onClick = onClose)
    }
}

@Composable
private fun MyQrPanel(
    qrString: String,
    accent: Color,
    boxColor: Color,
) {
    Text(
        text = stringResource(R.string.verify_my_qr_title),
        fontSize = 16.sp,
        fontFamily = FontFamily.Monospace,
        color = accent,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )

    QRCodeCard(qrString = qrString, boxColor = boxColor)
}

@Composable
private fun ToggleVerificationModeButton(
    showingScanner: Boolean,
    onToggle: () -> Unit
) {
    Button(
        onClick = onToggle,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (showingScanner) {
            Icon(
                imageVector = Icons.Outlined.QrCode,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.verify_show_my_qr),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.verify_scan_someone),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun QRCodeCard(qrString: String, boxColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(boxColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (qrString.isNotBlank()) {
            QRCodeImage(data = qrString, size = 220.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .background(Color.Transparent, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.verify_qr_unavailable),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        SelectionContainer {
            Text(
                text = qrString,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun QRCodeImage(data: String, size: Dp) {
    val sizePx = with(LocalDensity.current) { size.toPx().toInt() }
    val bitmap = remember(data, sizePx) { generateQrBitmap(data, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    }
}

private fun generateQrBitmap(data: String, sizePx: Int): Bitmap? {
    if (data.isBlank() || sizePx <= 0) return null
    return try {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        bitmapFromMatrix(matrix)
    } catch (_: Exception) {
        null
    }
}

private fun bitmapFromMatrix(matrix: BitMatrix): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val bitmap = createBitmap(width, height)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap[x, y] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return bitmap
}

// ─── Camera2 + ZXing QR Scanner ─────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun QRScannerPanel(
    onScan: (String) -> Unit,
    accent: Color,
    boxColor: Color,
    modifier: Modifier = Modifier
) {
    val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(boxColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.verify_scan_prompt_friend),
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = accent,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        if (permissionState.status.isGranted) {
            Camera2QRPreview(
                onScan = onScan,
                modifier = Modifier
                    .size(220.dp)
                    .clipToBounds()
            )
        } else {
            Text(
                text = stringResource(R.string.verify_camera_permission),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Button(
                onClick = { permissionState.launchPermissionRequest() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(
                    text = stringResource(R.string.verify_request_camera),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * Camera2-based QR preview: opens the back camera, renders preview on a TextureView,
 * and uses an ImageReader + ZXing MultiFormatReader to decode QR codes from YUV frames.
 * Zero additional dependencies — Camera2 is native Android, ZXing is already shared.
 */
@Composable
private fun Camera2QRPreview(
    onScan: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val onScanState = rememberUpdatedState(onScan)

    // Mutable holder shared between DisposableEffect and AndroidView
    val holder = remember { Camera2Holder() }

    val zxingReader = remember {
        MultiFormatReader().apply {
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
            setHints(hints)
        }
    }

    DisposableEffect(Unit) {
        val cameraManager = context.getSystemService(CameraManager::class.java)

        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()

        if (cameraId == null) {
            Log.w("VerificationSheet", "No camera found")
            return@DisposableEffect onDispose { }
        }

        val bgThread = HandlerThread("QRCameraThread").also { it.start() }
        val bgHandler = Handler(bgThread.looper)
        val mainHandler = Handler(android.os.Looper.getMainLooper())

        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                val w = image.width
                val h = image.height

                val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                try {
                    val result = zxingReader.decodeWithState(binaryBitmap)
                    val text = result.text
                    if (!text.isNullOrBlank() && text != holder.lastScanned) {
                        holder.lastScanned = text
                        mainHandler.post { onScanState.value(text) }
                    }
                } catch (_: Exception) {
                    // No QR found in this frame — normal
                } finally {
                    zxingReader.reset()
                }
            } finally {
                image.close()
            }
        }, bgHandler)

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                holder.cameraDevice = camera
                val tv = holder.textureView ?: return
                val surface = Surface(tv.surfaceTexture ?: return)
                val readerSurface = imageReader.surface

                try {
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(surface)
                    builder.addTarget(readerSurface)
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )

                    camera.createCaptureSession(
                        listOf(surface, readerSurface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.setRepeatingRequest(builder.build(), null, bgHandler)
                                } catch (e: Exception) {
                                    Log.w("VerificationSheet", "Preview start failed: ${e.message}")
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.w("VerificationSheet", "Camera session config failed")
                            }
                        },
                        bgHandler
                    )
                } catch (e: Exception) {
                    Log.w("VerificationSheet", "Capture session failed: ${e.message}")
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                holder.cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                holder.cameraDevice = null
                Log.w("VerificationSheet", "Camera error: $error")
            }
        }

        holder.openCamera = {
            try {
                cameraManager.openCamera(cameraId, stateCallback, bgHandler)
            } catch (e: SecurityException) {
                Log.w("VerificationSheet", "Camera permission denied: ${e.message}")
            } catch (e: Exception) {
                Log.w("VerificationSheet", "Failed to open camera: ${e.message}")
            }
        }

        // If the TextureView surface was already available before this effect ran, open now
        if (holder.textureView?.isAvailable == true) {
            holder.openCamera?.invoke()
        }

        onDispose {
            runCatching { holder.cameraDevice?.close() }
            holder.cameraDevice = null
            holder.openCamera = null
            imageReader.close()
            bgThread.quitSafely()
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                holder.textureView = tv
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: android.graphics.SurfaceTexture, width: Int, height: Int
                    ) {
                        holder.openCamera?.invoke()
                    }
                    override fun onSurfaceTextureSizeChanged(
                        surface: android.graphics.SurfaceTexture, width: Int, height: Int
                    ) {}
                    override fun onSurfaceTextureDestroyed(
                        surface: android.graphics.SurfaceTexture
                    ) = true
                    override fun onSurfaceTextureUpdated(
                        surface: android.graphics.SurfaceTexture
                    ) {}
                }
            }
        },
        modifier = modifier
    )
}

/** Mutable holder for sharing Camera2 lifecycle state within a single composable scope. */
private class Camera2Holder {
    var textureView: TextureView? = null
    var cameraDevice: CameraDevice? = null
    var openCamera: (() -> Unit)? = null
    var lastScanned: String? = null
}
