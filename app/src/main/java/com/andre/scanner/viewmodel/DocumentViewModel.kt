package com.andre.scanner.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andre.scanner.model.Mode
import com.andre.scanner.model.Page
import com.andre.scanner.model.PageStatus
import com.andre.scanner.model.Pt
import com.andre.scanner.pdf.PdfExporter
import com.andre.scanner.pdf.Quality
import com.andre.scanner.processing.CornerDetector
import com.andre.scanner.processing.DocumentProcessor
import com.andre.scanner.processing.Geometry
import com.andre.scanner.processing.ImageLoader
import com.andre.scanner.share.ShareHelper
import com.andre.scanner.storage.HistoryEntry
import com.andre.scanner.storage.HistoryStore
import com.andre.scanner.storage.PageStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ExportState {
    data object Idle : ExportState
    data class Working(val done: Int, val total: Int) : ExportState
    data class Done(val file: File, val sizeBytes: Long, val pages: Int) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Estado do documento em edição. A ordem das páginas é a ordem da lista [pages].
 *
 * Todo o trabalho pesado roda em [heavy], que executa uma tarefa por vez: com fotos de 12 MP,
 * processar duas ao mesmo tempo pode estourar a memória. As alterações de estado feitas pelas
 * tarefas passam pela thread principal (onde também acontecem os cancelamentos), então uma
 * tarefa cancelada nunca sobrescreve o resultado de uma mais nova.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentViewModel(private val app: Application) : AndroidViewModel(app) {

    val storage = PageStorage(app)
    private val historyStore = HistoryStore(app, storage.pdfDir)
    private val heavy = Dispatchers.Default.limitedParallelism(1)

    private val _pages = MutableStateFlow<List<Page>>(emptyList())
    val pages: StateFlow<List<Page>> = _pages.asStateFlow()

    /** Páginas novas esperando o usuário conferir os cantos, na ordem em que entraram. */
    private val _review = MutableStateFlow<List<String>>(emptyList())
    val reviewQueue: StateFlow<List<String>> = _review.asStateFlow()

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _export.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Última tarefa de cada página. Só mexido na thread principal. */
    private val jobs = mutableMapOf<String, Job>()

    init {
        // O documento não sobrevive ao app ser fechado; limpa sobras de uma sessão anterior.
        // Roda em [heavy], então qualquer importação feita depois espera a limpeza terminar.
        viewModelScope.launch(heavy) { storage.clearWork() }
        viewModelScope.launch(Dispatchers.IO) { _history.value = historyStore.load() }
    }

    fun page(id: String): Page? = _pages.value.firstOrNull { it.id == id }

    // ---------- Entrada de imagens ----------

    fun addFromUris(uris: List<Uri>) {
        val resolver = app.contentResolver
        uris.forEach { uri ->
            addSource { resolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir a imagem") }
        }
    }

    fun addFromCapture(file: File) = addSource(cleanup = file) { file.inputStream() }

    /** Cria a página na hora (aparece como "carregando") e normaliza + detecta em segundo plano. */
    private fun addSource(cleanup: File? = null, open: () -> InputStream) {
        val page = Page()
        _pages.update { it + page }
        _review.update { it + page.id }
        invalidateExport()
        enqueue(page.id) {
            val dest = storage.originalFile(page.id)
            val bmp = try {
                ImageLoader.normalize(open, dest)
            } finally {
                cleanup?.delete()
            }
            val w = bmp.width
            val h = bmp.height
            val detected = try {
                CornerDetector.detect(bmp)
            } finally {
                bmp.recycle()
            }
            onMain {
                update(page.id) {
                    it.copy(
                        originalPath = dest.path, width = w, height = h,
                        detected = detected, corners = detected ?: Geometry.fullImage(w, h),
                        status = PageStatus.READY,
                    )
                }
                // O usuário pulou a revisão enquanto esta página carregava: processa direto.
                if (page.id !in _review.value) reprocess(page.id)
            }
        }
    }

    // ---------- Revisão dos cantos ----------

    fun confirmCorners(id: String, corners: List<Pt>) {
        val ordered = if (corners.size == 4) Geometry.orderCorners(corners) else corners
        update(id) { it.copy(corners = ordered) }
        _review.update { it - id }
        reprocess(id)
    }

    /** Aceita os cantos detectados de todas as páginas que faltam conferir. */
    fun skipReview() {
        val pending = _review.value
        _review.value = emptyList()
        pending.mapNotNull(::page).filter { it.status == PageStatus.READY }.forEach { reprocess(it.id) }
    }

    /** Reabre o ajuste de cantos de uma página já processada. */
    fun startReview(id: String) {
        _review.value = listOf(id)
    }

    // ---------- Edição das páginas ----------

    fun move(from: Int, to: Int) {
        _pages.update { list ->
            if (from !in list.indices || to !in list.indices) list
            else list.toMutableList().apply { add(to, removeAt(from)) }
        }
        invalidateExport()
    }

    fun setMode(id: String, mode: Mode) {
        if (page(id)?.mode == mode) return
        update(id) { it.copy(mode = mode) }
        reprocess(id)
    }

    fun rotate(id: String) {
        update(id) { it.copy(rotation = (it.rotation + 90) % 360) }
        invalidateExport()
        // Só refaz a miniatura; a rotação é aplicada de novo na hora de gerar o PDF.
        // Espera a tarefa atual da página (não cancela) e usa a rotação mais recente.
        enqueue(id, cancelPrevious = false) {
            val p = page(id) ?: return@enqueue
            val processed = p.processedPath ?: return@enqueue
            val thumb = storage.thumbFile(id)
            DocumentProcessor.makeThumb(processed, p.rotation, thumb)
            onMain {
                val old = page(id)?.thumbPath
                update(id) { it.copy(thumbPath = thumb.path) }
                storage.delete(old)
            }
        }
    }

    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        val p = page(id) ?: return
        _pages.update { list -> list.filter { it.id != id } }
        _review.update { it - id }
        storage.delete(p.originalPath, p.processedPath, p.thumbPath)
        invalidateExport()
    }

    /** Apaga o documento em edição e suas imagens temporárias. O histórico de PDFs fica. */
    fun discard() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _pages.value = emptyList()
        _review.value = emptyList()
        _export.value = ExportState.Idle
        viewModelScope.launch(heavy) { storage.clearWork() }
    }

    private fun reprocess(id: String) {
        update(id) { it.copy(status = PageStatus.PROCESSING) }
        invalidateExport()
        enqueue(id, cancelPrevious = true) {
            val p = page(id) ?: return@enqueue
            val original = p.originalPath ?: return@enqueue
            val out = storage.processedFile(id)
            DocumentProcessor.process(original, p.corners, p.mode, out)
            ensureActive()
            val thumb = storage.thumbFile(id)
            DocumentProcessor.makeThumb(out.path, page(id)?.rotation ?: p.rotation, thumb)
            onMain {
                val old = page(id)
                if (old == null) {
                    storage.delete(out.path, thumb.path)
                    return@onMain
                }
                update(id) { it.copy(processedPath = out.path, thumbPath = thumb.path, status = PageStatus.DONE) }
                storage.delete(old.processedPath, old.thumbPath)
            }
        }
    }

    // ---------- Exportação ----------

    fun defaultName(): String =
        "Documento_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date())

    fun export(name: String, quality: Quality) {
        if (_export.value is ExportState.Working) return
        viewModelScope.launch {
            _export.value = ExportState.Working(0, _pages.value.size)
            try {
                // Espera as páginas que ainda estão sendo processadas.
                jobs.values.toList().joinAll()
                val pages = _pages.value
                if (pages.isEmpty()) throw IOException("Nenhuma página no documento")
                val failed = pages.indexOfFirst { it.status != PageStatus.DONE }
                if (failed >= 0) throw IOException("A página ${failed + 1} não foi processada. Exclua ou reajuste os cantos.")

                val title = sanitize(name).ifBlank { defaultName() }
                val file = withContext(Dispatchers.IO) {
                    val f = ShareHelper.uniqueFile(storage.pdfDir.apply { mkdirs() }, title, "pdf")
                    PdfExporter.export(pages, title, quality, f) { n ->
                        _export.value = ExportState.Working(n, pages.size)
                    }
                    f
                }
                val entry = HistoryEntry(
                    name = file.nameWithoutExtension, fileName = file.name, pages = pages.size,
                    sizeBytes = file.length(), createdAt = System.currentTimeMillis(),
                )
                _history.value = withContext(Dispatchers.IO) { historyStore.add(entry) }
                _export.value = ExportState.Done(file, file.length(), pages.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "falha ao exportar", e)
                _export.value = ExportState.Failed(e.message ?: "Erro ao gerar o PDF")
            }
        }
    }

    fun resetExport() {
        if (_export.value !is ExportState.Working) _export.value = ExportState.Idle
    }

    fun deleteHistory(entry: HistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) { _history.value = historyStore.remove(entry) }
    }

    fun historyFile(entry: HistoryEntry) = historyStore.fileOf(entry)

    fun notify(msg: String) {
        _messages.tryEmit(msg)
    }

    // ---------- Infra ----------

    private fun update(id: String, f: (Page) -> Page) {
        _pages.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    /** O PDF gerado não reflete mais as páginas; volta a pedir confirmação ao sair. */
    private fun invalidateExport() {
        if (_export.value is ExportState.Done || _export.value is ExportState.Failed) _export.value = ExportState.Idle
    }

    /**
     * Agenda uma tarefa para a página [id] em [heavy]. Com [cancelPrevious], a tarefa anterior
     * da página é cancelada (ex.: trocou o modo no meio do processamento); senão, espera ela.
     */
    private fun enqueue(id: String, cancelPrevious: Boolean = true, block: suspend CoroutineScope.() -> Unit) {
        val prev = jobs[id]
        if (cancelPrevious) prev?.cancel()
        jobs[id] = viewModelScope.launch(heavy) {
            prev?.join()
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "falha ao processar página $id", e)
                onMain { onPageError(id, e) }
            }
        }
    }

    private fun onPageError(id: String, e: Throwable) {
        val p = page(id) ?: return
        if (p.originalPath == null) {
            // Nem chegou a carregar: some com a página.
            _pages.update { list -> list.filter { it.id != id } }
            _review.update { it - id }
            notify("Não foi possível abrir uma das imagens")
        } else {
            update(id) { it.copy(status = PageStatus.ERROR) }
            notify(if (e is OutOfMemoryError) "Memória insuficiente para processar a página" else "Falha ao processar a página")
        }
    }

    /** Roda na thread principal e só se a tarefa ainda valer (não foi cancelada). */
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) {
        ensureActive()
        block()
    }

    private fun sanitize(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(100)

    private companion object {
        const val TAG = "DocumentViewModel"
    }
}
