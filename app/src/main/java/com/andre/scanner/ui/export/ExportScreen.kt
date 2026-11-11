package com.andre.scanner.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andre.scanner.pdf.Quality
import com.andre.scanner.ui.common.formatSize
import com.andre.scanner.viewmodel.DocumentViewModel
import com.andre.scanner.viewmodel.ExportState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(vm: DocumentViewModel, onBack: () -> Unit, onPreview: (File) -> Unit, onFinish: () -> Unit) {
    val state by vm.exportState.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(vm.defaultName()) }
    var quality by rememberSaveable { mutableStateOf(Quality.NORMAL) }
    // Só abre a prévia sozinho logo depois de tocar em Gerar, não ao voltar da prévia.
    var waitingResult by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    LaunchedEffect(Unit) { if (state is ExportState.Failed) vm.resetExport() }
    LaunchedEffect(state) {
        val s = state
        if (s is ExportState.Done && waitingResult) {
            waitingResult = false
            onPreview(s.file)
        } else if (s is ExportState.Failed) {
            waitingResult = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gerar PDF") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val working = state is ExportState.Working
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; if (state is ExportState.Done) vm.resetExport() },
                label = { Text("Nome do arquivo") },
                suffix = { Text(".pdf") },
                singleLine = true,
                enabled = !working,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                Text("Qualidade", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Quality.entries.forEachIndexed { i, q ->
                        SegmentedButton(
                            selected = quality == q,
                            onClick = { quality = q; if (state is ExportState.Done) vm.resetExport() },
                            shape = SegmentedButtonDefaults.itemShape(i, Quality.entries.size),
                            enabled = !working,
                        ) { Text(if (q == Quality.NORMAL) "Normal (150 dpi)" else "Alta (200 dpi)") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (quality == Quality.NORMAL) "Arquivo menor, bom para WhatsApp." else "Mais nitidez, arquivo maior.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val s = state) {
                is ExportState.Working -> Column {
                    Text(
                        if (s.done == 0) "Preparando as páginas…" else "Gravando página ${s.done} de ${s.total}…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (s.done == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                }

                is ExportState.Done -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("PDF pronto", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${s.file.name}\n${if (s.pages == 1) "1 página" else "${s.pages} páginas"} · ${formatSize(s.sizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { onPreview(s.file) }, modifier = Modifier.fillMaxWidth()) { Text("Ver PDF") }
                        TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Concluir e começar outro") }
                    }
                }

                is ExportState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                ExportState.Idle -> Unit
            }

            if (state !is ExportState.Done) {
                Button(
                    onClick = {
                        focus.clearFocus()
                        waitingResult = true
                        vm.export(name, quality)
                    },
                    enabled = !working && pages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Gerar PDF") }
            }
        }
    }
}
