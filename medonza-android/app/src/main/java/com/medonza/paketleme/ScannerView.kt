package com.medonza.paketleme

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun BarcodeCamera(active: Boolean, onBarcode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val processing = remember { AtomicBoolean(false) }
    val detected = remember { AtomicBoolean(false) }
    val options = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_CODE_128, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_QR_CODE)
            .build()
    }
    val scanner = remember { BarcodeScanning.getClient(options) }
    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        providerHolder[0] = future.get()
                        if (!active) return@addListener
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { proxy ->
                            if (!active || detected.get() || !processing.compareAndSet(false, true)) { proxy.close(); return@setAnalyzer }
                            val media = proxy.image
                            if (media == null) { processing.set(false); proxy.close(); return@setAnalyzer }
                            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            scanner.process(input)
                                .addOnSuccessListener { codes ->
                                    val value = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                                    if (value != null && detected.compareAndSet(false, true)) onBarcode(value)
                                }
                                .addOnCompleteListener { processing.set(false); proxy.close() }
                        }
                        try {
                            providerHolder[0]?.unbindAll()
                            providerHolder[0]?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        } catch (_: Exception) { }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        )
        ScanTargetOverlay()
    }

    DisposableEffect(Unit) {
        onDispose { providerHolder[0]?.unbindAll(); scanner.close(); executor.shutdown() }
    }
}

@Composable
private fun ScanTargetOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * .82f
        val h = size.height * .26f
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        drawRect(Color.White.copy(alpha=.16f), topLeft=Offset(left,top), size=Size(w,h), style=Stroke(width=3f))
        val l = 42f
        val sw = 8f
        fun line(a:Offset,b:Offset)=drawLine(Color.White,a,b,strokeWidth=sw,cap=StrokeCap.Round)
        line(Offset(left,top),Offset(left+l,top)); line(Offset(left,top),Offset(left,top+l))
        line(Offset(left+w,top),Offset(left+w-l,top)); line(Offset(left+w,top),Offset(left+w,top+l))
        line(Offset(left,top+h),Offset(left+l,top+h)); line(Offset(left,top+h),Offset(left,top+h-l))
        line(Offset(left+w,top+h),Offset(left+w-l,top+h)); line(Offset(left+w,top+h),Offset(left+w,top+h-l))
    }
}
