package com.andre.scanner.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andre.scanner.R
import com.andre.scanner.share.ShareHelper
import com.andre.scanner.storage.HistoryEntry
import com.andre.scanner.ui.common.formatSize
import com.andre.scanner.viewmodel.DocumentViewModel
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: DocumentViewModel, onCamera: () -> Unit, onGallery: () -> Unit, onOpen: (File) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var toDelete by remember { mutableStateOf<HistoryEntry?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("MyPDF", fontWeight = FontWeight.Bold) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BigAction("Câmera", R.drawable.ic_camera, Modifier.weight(1f), onCamera)
                    BigAction("Galeria", R.drawable.ic_gallery, Modifier.weight(1f), onGallery)
                }
            }
            item {
                Text(
                    "PDFs gerados",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (history.isEmpty()) {
                item {
                    Text(
                        "Nenhum PDF ainda. Tire fotos ou escolha imagens da galeria para começar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(history, key = { it.fileName }) { entry ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(vm.historyFile(entry)) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_pdf), null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(entry.name, maxLines = 1) },
                    supportingContent = { Text(describe(entry)) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { ShareHelper.sharePdf(ctx, vm.historyFile(entry)) }) {
                                Icon(Icons.Default.Share, "Compartilhar")
                            }
                            IconButton(onClick = { toDelete = entry }) {
                                Icon(Icons.Default.Delete, "Excluir")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    toDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Excluir PDF?") },
            text = { Text("\"${entry.name}\" será apagado do app. Cópias já salvas em Downloads ou enviadas não são afetadas.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteHistory(entry); toDelete = null }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun BigAction(label: String, @DrawableRes icon: Int, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier.height(140.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(icon), null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun describe(e: HistoryEntry): String {
    val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(e.createdAt))
    val pages = if (e.pages == 1) "1 página" else "${e.pages} páginas"
    return "$pages · ${formatSize(e.sizeBytes)} · $date"
}
