package com.andre.scanner.ui.preview

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andre.scanner.share.ShareHelper
import com.andre.scanner.ui.common.formatSize
import com.andre.scanner.ui.common.rememberDownloadSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Lê o PDF gerado com o leitor nativo do Android (PdfRenderer), então a prévia mostra
 * exatamente o arquivo que vai ser enviado. O PdfRenderer só renderiza uma página por vez,
 * por isso tudo passa pelo mesmo lock.
 */
private class PdfPages(file: File) : Closeable {
    private val lock = Any()
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private var closed = false

    /** Proporção (largura / altura) de cada página. */
    val aspects: List<Float> = synchronized(lock) {
        (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width.toFloat() / it.height } }
    }

    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        if (closed) return null
        renderer.openPage(index).use { page ->
            val w = widthPx.coerceAtLeast(1)
            val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(AColor.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        renderer.close()
        fd.close()
    }
}

/**
 * Prévia do PDF. Vindo da exportação ([fromExport]), mostra o botão Concluir; vindo do
 * histórico, só as ações de compartilhar/salvar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewScreen(file: File, fromExport: Boolean, onBack: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val saver = rememberDownloadSaver()
    var menu by remember { mutableStateOf(false) }
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()

    val pages by produceState<Result<PdfPages>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { PdfPages(file) } }
            .onFailure { Log.e("PdfPreview", "falha ao abrir", it) }
    }
    DisposableEffect(pages) {
        val opened = pages?.getOrNull()
        // Fecha fora da thread principal: pode ter uma página sendo renderizada.
        onDispose { if (opened != null) thread { opened.close() } }
    }
    val count = pages?.getOrNull()?.aspects?.size ?: 0
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.nameWithoutExtension, maxLines = 1)
                        if (count > 0) {
                            Text(
                                "Página ${current.coerceAtMost(count)} de $count · ${formatSize(file.length())}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                actions = {
                    if (fromExport) TextButton(onClick = onFinish) { Text("Concluir") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mais opções") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Abrir em outro app") },
                                onClick = { menu = false; ShareHelper.openPdf(ctx, file) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { saver.save(file) },
                        enabled = !saver.saving,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (saver.saving) "Salvando…" else "Salvar em Downloads", maxLines = 1) }
                    Button(onClick = { ShareHelper.sharePdf(ctx, file) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Compartilhar")
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            val result = pages
            when {
                result == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                result.isFailure -> Text(
                    "Não foi possível abrir o PDF.",
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    val pdf = result.getOrThrow()
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val widthPx = with(LocalDensity.current) { (maxWidth - 32.dp).roundToPx() }
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(pdf.aspects.size) { i ->
                                PageImage(
                                    pdf, i, min(widthPx, MAX_LIST_WIDTH),
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(pdf.aspects[i])
                                        .clickable(onClickLabel = "Ampliar página") { zoomPage = i },
                                )
                            }
                        }
                        zoomPage?.let { i -> ZoomDialog(pdf, i, widthPx) { zoomPage = null } }
                    }
                }
            }
        }
    }
}

private const val MAX_LIST_WIDTH = 1240

@Composable
private fun PageImage(pdf: PdfPages, index: Int, widthPx: Int, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, pdf, index, widthPx) {
        value = withContext(Dispatchers.IO) { pdf.render(index, widthPx)?.asImageBitmap() }
    }
    Surface(modifier = modifier, shadowElevation = 2.dp, color = Color.White) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = "Página ${index + 1}", modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
            }
        }
    }
}

/** Página em tela cheia, renderizada com mais resolução, com pinça para ampliar e toque duplo para voltar. */
@Composable
private fun ZoomDialog(pdf: PdfPages, index: Int, screenWidthPx: Int, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PageImage(
                pdf, index, min(screenWidthPx * 2, 2480),
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .aspectRatio(pdf.aspects[index])
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp),
            ) { Icon(Icons.Default.Close, "Fechar") }
        }
    }
}
