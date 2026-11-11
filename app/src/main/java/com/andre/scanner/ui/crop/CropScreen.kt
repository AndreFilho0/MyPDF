package com.andre.scanner.ui.crop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andre.scanner.model.Page
import com.andre.scanner.model.PageStatus
import com.andre.scanner.model.Pt
import com.andre.scanner.processing.Geometry
import com.andre.scanner.processing.ImageLoader
import com.andre.scanner.viewmodel.DocumentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Confere os cantos das páginas da fila de revisão, uma por vez. Quando a fila esvazia,
 * chama [onFinished].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(vm: DocumentViewModel, onFinished: () -> Unit) {
    val queue by vm.reviewQueue.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    var reviewed by remember { mutableIntStateOf(0) }
    val currentId = queue.firstOrNull()
    val page = pages.firstOrNull { it.id == currentId }

    LaunchedEffect(currentId) { if (currentId == null) onFinished() }
    BackHandler { vm.skipReview() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ajustar cantos")
                        if (queue.size + reviewed > 1) {
                            Text(
                                "Página ${reviewed + 1} de ${reviewed + queue.size}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                actions = {
                    if (queue.size > 1) TextButton(onClick = { vm.skipReview() }) { Text("Aceitar todas") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (page == null || page.status == PageStatus.LOADING || page.originalPath == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                PageCropper(
                    page = page,
                    onConfirm = { corners ->
                        if (isValidQuad(corners)) {
                            reviewed++
                            vm.confirmCorners(page.id, corners)
                        } else {
                            vm.notify("Os cantos não formam uma folha válida")
                        }
                    },
                )
            }
        }
    }
}

private fun isValidQuad(c: List<Pt>): Boolean {
    if (c.size != 4) return false
    val ordered = Geometry.orderCorners(c)
    return Geometry.isConvex(ordered) && Geometry.area(ordered) > 400
}

@Composable
private fun PageCropper(page: Page, onConfirm: (List<Pt>) -> Unit) {
    val full = remember(page.id, page.width, page.height) { Geometry.fullImage(page.width, page.height) }
    var corners by remember(page.id, page.originalPath) { mutableStateOf(page.corners.ifEmpty { full }) }

    Column(Modifier.fillMaxSize()) {
        if (page.detected == null) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Não encontrei a folha automaticamente. Arraste os cantos até as bordas dela.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        CornerEditor(
            path = page.originalPath!!,
            imgW = page.width,
            imgH = page.height,
            corners = corners,
            onChange = { corners = it },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { corners = page.detected ?: full }, modifier = Modifier.weight(1f)) { Text("Resetar") }
            OutlinedButton(onClick = { corners = full }, modifier = Modifier.weight(1f)) {
                Text("Inteira", maxLines = 1)
            }
            Button(onClick = { onConfirm(corners) }, modifier = Modifier.weight(1f)) { Text("Confirmar") }
        }
    }
}

/**
 * Mostra a foto com os 4 cantos arrastáveis. Os cantos estão em pixels da original
 * ([imgW] × [imgH]); a foto é exibida reduzida e encaixada no espaço disponível.
 */
@Composable
private fun CornerEditor(
    path: String,
    imgW: Int,
    imgH: Int,
    corners: List<Pt>,
    onChange: (List<Pt>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { ImageLoader.decodeSampled(path, 2048)?.asImageBitmap() }
    }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableStateOf<Int?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val currentCorners by rememberUpdatedState(corners)
    val currentOnChange by rememberUpdatedState(onChange)

    val primary = MaterialTheme.colorScheme.primary
    val fit = remember(boxSize, imgW, imgH) { Fit.of(boxSize, imgW, imgH) }

    Box(modifier.onSizeChanged { boxSize = it }) {
        val img = image
        if (img == null || fit == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(fit) {
                    val grab = 56.dp.toPx()
                    detectDragGestures(
                        onDragStart = { pos ->
                            val screen = currentCorners.map(fit::toScreen)
                            val (idx, d) = screen.withIndex().map { it.index to (it.value - pos).getDistance() }.minBy { it.second }
                            active = if (d <= grab) idx else null
                            finger = pos
                        },
                        onDrag = { change, amount ->
                            val i = active ?: return@detectDragGestures
                            change.consume()
                            finger += amount
                            val c = currentCorners[i]
                            val moved = Pt(
                                (c.x + amount.x / fit.scale).coerceIn(0f, imgW.toFloat()),
                                (c.y + amount.y / fit.scale).coerceIn(0f, imgH.toFloat()),
                            )
                            currentOnChange(currentCorners.toMutableList().also { it[i] = moved })
                        },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    )
                }
        ) {
            drawPhoto(img, fit)
            val screen = corners.map(fit::toScreen)
            drawQuad(screen, primary, active)
            active?.let { i -> drawMagnifier(img, fit, screen, i, finger, primary) }
        }
    }
}

/** Encaixe da foto (em pixels da original) dentro da caixa, como ContentScale.Fit. */
private data class Fit(val scale: Float, val left: Float, val top: Float, val w: Float, val h: Float) {
    fun toScreen(p: Pt) = Offset(left + p.x * scale, top + p.y * scale)
    val rect get() = Rect(left, top, left + w, top + h)

    companion object {
        fun of(box: IntSize, imgW: Int, imgH: Int): Fit? {
            if (box.width <= 0 || box.height <= 0 || imgW <= 0 || imgH <= 0) return null
            val s = min(box.width.toFloat() / imgW, box.height.toFloat() / imgH)
            val w = imgW * s
            val h = imgH * s
            return Fit(s, (box.width - w) / 2, (box.height - h) / 2, w, h)
        }
    }
}

private fun DrawScope.drawPhoto(img: ImageBitmap, fit: Fit) {
    drawImage(
        image = img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
        dstSize = IntSize(fit.w.toInt(), fit.h.toInt()),
    )
}

private fun DrawScope.drawQuad(pts: List<Offset>, color: Color, active: Int?) {
    if (pts.size != 4) return
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1..3) lineTo(pts[i].x, pts[i].y)
        close()
    }
    drawPath(path, color.copy(alpha = 0.15f))
    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    pts.forEachIndexed { i, p ->
        val r = if (i == active) 16.dp.toPx() else 12.dp.toPx()
        drawCircle(Color.White, r + 2.dp.toPx(), p)
        drawCircle(color, r, p, alpha = 0.85f)
    }
}

/**
 * Lupa: mostra ampliada a região em volta do canto que está sendo arrastado, num círculo
 * no alto da tela, do lado oposto ao dedo para não ficar escondida por ele.
 */
private fun DrawScope.drawMagnifier(img: ImageBitmap, fit: Fit, screen: List<Offset>, i: Int, finger: Offset, color: Color) {
    val radius = 60.dp.toPx()
    val margin = 12.dp.toPx()
    val zoom = 3f
    val onLeft = finger.x > size.width / 2
    val center = Offset(
        if (onLeft) margin + radius else size.width - margin - radius,
        margin + radius,
    )
    val target = screen[i]
    val circle = Path().apply { addOval(Rect(center, radius)) }

    clipPath(circle) {
        drawRect(Color.Black, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
        withTransform({
            translate(center.x - target.x * zoom, center.y - target.y * zoom)
            scale(zoom, zoom, pivot = Offset.Zero)
        }) {
            drawPhoto(img, fit)
            val path = Path().apply {
                moveTo(screen[0].x, screen[0].y)
                for (k in 1..3) lineTo(screen[k].x, screen[k].y)
                close()
            }
            drawPath(path, color, style = Stroke(width = 1.dp.toPx()))
        }
    }
    // Mira no centro da lupa = posição exata do canto.
    val arm = 10.dp.toPx()
    drawLine(color, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = 1.5f.dp.toPx())
    drawLine(color, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = 1.5f.dp.toPx())
    drawCircle(Color.White, radius, center, style = Stroke(width = 3.dp.toPx()))
}
