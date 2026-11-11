package com.andre.scanner.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andre.scanner.R
import com.andre.scanner.model.Mode
import com.andre.scanner.model.Page
import com.andre.scanner.model.PageStatus
import com.andre.scanner.processing.DocumentProcessor
import com.andre.scanner.ui.common.FileImage
import com.andre.scanner.viewmodel.DocumentViewModel
import com.andre.scanner.viewmodel.ExportState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesScreen(
    vm: DocumentViewModel,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onAdjust: (String) -> Unit,
    onExport: () -> Unit,
    onExit: () -> Unit,
) {
    val pages by vm.pages.collectAsStateWithLifecycle()
    val export by vm.exportState.collectAsStateWithLifecycle()
    var confirmExit by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    val tryExit = {
        // Já exportado (e nada mudou depois) ou vazio: sai sem perguntar.
        if (pages.isEmpty() || export is ExportState.Done) onExit() else confirmExit = true
    }
    BackHandler { tryExit() }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        vm.move(from.index - HEADER_ITEMS, to.index - HEADER_ITEMS)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pages.size == 1) "1 página" else "${pages.size} páginas") },
                navigationIcon = {
                    IconButton(onClick = tryExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Sair") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Box {
                    OutlinedButton(onClick = { addMenu = true }, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Adicionar")
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Câmera") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_camera), null) },
                            onClick = { addMenu = false; onCamera() },
                        )
                        DropdownMenuItem(
                            text = { Text("Galeria") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_gallery), null) },
                            onClick = { addMenu = false; onGallery() },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onExport,
                    enabled = pages.isNotEmpty(),
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text("Gerar PDF") }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
                Text(
                    if (pages.isEmpty()) "Nenhuma página. Use Adicionar para tirar fotos ou escolher da galeria."
                    else "Toque longo e arraste para mudar a ordem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(pages, key = { it.id }) { page ->
                ReorderableItem(reorderState, key = page.id) { dragging ->
                    val elevation by animateDpAsState(if (dragging) 8.dp else 1.dp, label = "elev")
                    PageCard(
                        number = pages.indexOf(page) + 1,
                        page = page,
                        elevation = elevation.value,
                        modifier = Modifier.longPressDraggableHandle(),
                        onRotate = { vm.rotate(page.id) },
                        onToggleMode = {
                            vm.setMode(page.id, if (page.mode == Mode.NATURAL) Mode.SCANNER else Mode.NATURAL)
                        },
                        onAdjust = { onAdjust(page.id) },
                        onDelete = { vm.delete(page.id) },
                    )
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Descartar documento?") },
            text = { Text("As páginas ainda não viraram PDF e serão apagadas.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; onExit() }) { Text("Descartar") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Continuar editando") } },
        )
    }
}

/** O texto de dica ocupa o primeiro item da grade. */
private const val HEADER_ITEMS = 1

@Composable
private fun PageCard(
    number: Int,
    page: Page,
    elevation: Float,
    modifier: Modifier,
    onRotate: () -> Unit,
    onToggleMode: () -> Unit,
    onAdjust: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(Color(0xFFE0E0E0))) {
            FileImage(
                path = page.thumbPath,
                maxSide = DocumentProcessor.THUMB_SIDE,
                modifier = Modifier.fillMaxSize().padding(6.dp),
                contentScale = ContentScale.Fit,
            )
            when (page.status) {
                PageStatus.LOADING, PageStatus.PROCESSING, PageStatus.READY ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp))
                PageStatus.ERROR -> Icon(
                    Icons.Default.Warning, "Erro",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
                PageStatus.DONE -> Unit
            }
            Text(
                "$number",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Default.MoreVert, "Opções da página",
                        modifier = Modifier.background(Color(0x99FFFFFF), CircleShape).padding(2.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Girar 90°") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_rotate), null) },
                        enabled = page.processedPath != null,
                        onClick = { menu = false; onRotate() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (page.mode == Mode.NATURAL) "Modo Scanner" else "Modo Natural") },
                        enabled = page.originalPath != null,
                        onClick = { menu = false; onToggleMode() },
                    )
                    DropdownMenuItem(
                        text = { Text("Reajustar cantos") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        enabled = page.originalPath != null && page.status != PageStatus.LOADING,
                        onClick = { menu = false; onAdjust() },
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
            Text(
                if (page.mode == Mode.NATURAL) "Natural" else "Scanner",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
