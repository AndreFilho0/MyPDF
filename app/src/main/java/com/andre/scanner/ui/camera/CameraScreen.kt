package com.andre.scanner.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andre.scanner.processing.CornerDetector
import com.andre.scanner.processing.ImageLoader
import com.andre.scanner.viewmodel.DocumentViewModel
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/** Contorno detectado ao vivo, em coordenadas 0..1 do quadro, mais a proporção do quadro. */
private data class LiveQuad(val points: List<Offset>, val aspect: Float)

@Composable
fun CameraScreen(vm: DocumentViewModel, onDone: (captured: Int) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    if (granted) {
        CameraContent(vm, onDone)
    } else {
        BackHandler { onDone(0) }
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("O app precisa da câmera para fotografar as folhas.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text("Permitir câmera") }
            if (asked) {
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    )
                }) { Text("Abrir configurações") }
            }
            OutlinedButton(onClick = { onDone(0) }) { Text("Voltar") }
        }
    }
}

@Composable
private fun CameraContent(vm: DocumentViewModel, onDone: (Int) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var captured by remember { mutableIntStateOf(0) }
    var pending by remember { mutableIntStateOf(0) }
    var quad by remember { mutableStateOf<LiveQuad?>(null) }
    var flashKey by remember { mutableIntStateOf(0) }
    var flashOn by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(ctx).apply {
            // FIT_CENTER: o preview mostra exatamente o que a foto vai pegar, e o contorno
            // desenhado por cima fica alinhado.
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val ratio4x3 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio4x3)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio4x3).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio4x3)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor, LiveAnalyzer { q -> previewView.post { quad = q } })
            p.unbindAll()
            try {
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            } catch (e: Exception) {
                // Alguns aparelhos não aguentam 3 fluxos juntos: fica sem o contorno ao vivo.
                Log.w("CameraScreen", "sem análise ao vivo", e)
                p.unbindAll()
                runCatching { p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture) }
                    .onFailure { vm.notify("Não foi possível abrir a câmera") }
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { provider?.unbindAll() }
    }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    val finish = { if (pending == 0) onDone(captured) }
    BackHandler { finish() }

    fun shoot() {
        val file = vm.storage.captureFile()
        pending++
        flashOn = true
        flashKey++
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    pending--
                    captured++
                    vm.addFromCapture(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    pending--
                    file.delete()
                    Log.e("CameraScreen", "falha na foto", exception)
                    vm.notify("Falha ao tirar a foto")
                }
            },
        )
    }

    LaunchedEffect(flashKey) {
        delay(90)
        flashOn = false
    }
    val flashAlpha by animateFloatAsState(if (flashOn) 0.7f else 0f, tween(if (flashOn) 30 else 200), label = "flash")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        QuadOverlay(quad, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().alpha(flashAlpha).background(Color.White))

        Row(
            modifier = Modifier.fillMaxWidth().systemBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { finish() },
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
            ) { Icon(Icons.Default.Close, "Fechar") }
            Spacer(Modifier.weight(1f))
            Text(
                if (captured == 1) "1 página" else "$captured páginas",
                color = Color.White,
                modifier = Modifier.background(Color(0x88000000), CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.width(96.dp))
            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(Color.White, CircleShape)
                    .clickable(onClickLabel = "Tirar foto") { shoot() }
            )
            Button(onClick = { finish() }, enabled = pending == 0, modifier = Modifier.width(96.dp)) {
                Text("Concluir")
            }
        }
    }
}

@Composable
private fun QuadOverlay(quad: LiveQuad?, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val q = quad ?: return@Canvas
        // Mesmo encaixe do PreviewView em FIT_CENTER.
        val boxAspect = size.width / size.height
        val (w, h) = if (boxAspect > q.aspect) size.height * q.aspect to size.height else size.width to size.width / q.aspect
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        val pts = q.points.map { Offset(left + it.x * w, top + it.y * h) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, color.copy(alpha = 0.25f))
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
    }
}

/** Detecta a folha a cada ~300 ms numa cópia pequena do quadro da câmera. */
private class LiveAnalyzer(private val onQuad: (LiveQuad?) -> Unit) : ImageAnalysis.Analyzer {
    private var last = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - last < INTERVAL_MS) return
            last = now
            val result = runCatching {
                val frame = ImageLoader.rotateAndFit(
                    image.toBitmap(), image.imageInfo.rotationDegrees,
                    CornerDetector.LIVE_SIDE, CornerDetector.LIVE_SIDE,
                )
                try {
                    val q = CornerDetector.detect(frame, CornerDetector.LIVE_SIDE)
                    val w = frame.width.toFloat()
                    val h = frame.height.toFloat()
                    q?.let { pts -> LiveQuad(pts.map { Offset(it.x / w, it.y / h) }, w / h) }
                } finally {
                    frame.recycle()
                }
            }.getOrNull()
            onQuad(result)
        }
    }

    private companion object {
        const val INTERVAL_MS = 300L
    }
}
