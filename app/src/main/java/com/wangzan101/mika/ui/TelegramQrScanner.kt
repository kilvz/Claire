package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaType

/**
 * Full-screen camera scanner for a Telegram bot setup code. Point it at the
 * QR code holding the bot token (optionally `token|chatId`) and the code is
 * handed to [onCode]; a code that holds no bot token is reported through
 * [errorMessage] instead and scanning pauses until [onClearError] runs.
 */
@Composable
fun TelegramQrScanner(
    onClose: () -> Unit,
    errorMessage: String?,
    onClearError: () -> Unit,
    onCode: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraFailed by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // QR codes only; anything else in view is ignored.
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    DisposableEffect(scanner) { onDispose { scanner.close() } }

    val previewView = remember { PreviewView(context) }
    val enabledState = rememberUpdatedState(errorMessage == null)
    val onCodeState = rememberUpdatedState(onCode)
    var lastValue by remember { mutableStateOf<String?>(null) }
    var lastAt by remember { mutableLongStateOf(0L) }
    val analyzer = remember(scanner) {
        ImageAnalysis.Analyzer { proxy ->
            val mediaImage = proxy.image
            if (mediaImage == null || !enabledState.value) {
                proxy.close()
                return@Analyzer
            }
            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val value = barcodes.firstOrNull()?.rawValue ?: return@addOnSuccessListener
                    // One report per code; the same code in view is not re-reported
                    // for two seconds, so a rejected code does not spam the caller.
                    val now = SystemClock.elapsedRealtime()
                    if (value == lastValue && now - lastAt < 2000L) return@addOnSuccessListener
                    lastValue = value
                    lastAt = now
                    onCodeState.value(value)
                }
                .addOnCompleteListener { proxy.close() }
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(cameraProviderFuture, hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose {}
        val executor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            try {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
            } catch (_: Exception) {
                cameraFailed = true
            }
        }
        cameraProviderFuture.addListener(listener, executor)
        onDispose {
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
        }
    }

    BackHandler { onClose() }

    Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("telegram-qr-scanner"),
    ) {
        when {
            !hasPermission -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.telegram_scan_camera_needed),
                    style = MikaType.Body, color = Color.White, textAlign = TextAlign.Center,
                )
                MikaButton(
                    stringResource(R.string.telegram_scan_allow_camera),
                    { requestPermission.launch(Manifest.permission.CAMERA) },
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                TextButton(onClose, Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.telegram_scan_close), color = Color.White)
                }
            }
            cameraFailed -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.telegram_scan_camera_failed),
                    style = MikaType.Body, color = Color.White, textAlign = TextAlign.Center,
                )
                TextButton(onClose, Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.telegram_scan_close), color = Color.White)
                }
            }
            else -> {
                AndroidView(factory = { previewView }, Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.Center).size(260.dp)
                        .border(2.dp, Color.White, RoundedCornerShape(16.dp)),
                )
                IconButton(onClose, Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Icon(MikaIcons.Close, contentDescription = stringResource(R.string.telegram_scan_close), tint = Color.White)
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.telegram_scan_help),
                        style = MikaType.Body, color = Color.White, textAlign = TextAlign.Center,
                    )
                    errorMessage?.let {
                        Text(
                            it, style = MikaType.Body, color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                        )
                        TextButton(onClearError, Modifier.padding(top = 4.dp)) {
                            Text(stringResource(R.string.telegram_scan_try_again), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
