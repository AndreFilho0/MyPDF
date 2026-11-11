package com.andre.scanner.storage

import android.content.Context
import java.io.File

/**
 * Onde ficam os arquivos. Tudo no armazenamento interno do app, que outros apps não leem.
 *
 *  - work/   imagens do documento em edição (originais, páginas processadas, miniaturas).
 *            Apagado ao descartar ou concluir o documento.
 *  - pdfs/   PDFs gerados (histórico). Compartilhados via FileProvider.
 */
class PageStorage(context: Context) {
    private val root = context.filesDir
    val workDir = File(root, "work")
    val pdfDir = File(root, "pdfs")

    fun captureFile(): File = File(workDir, "capture_${System.nanoTime()}.jpg").also { it.parentFile?.mkdirs() }
    fun originalFile(id: String) = File(workDir, "original_$id.jpg")
    /** Nome novo a cada processamento para a UI não mostrar uma miniatura velha em cache. */
    fun processedFile(id: String) = File(workDir, "page_${id}_${System.nanoTime()}.jpg")
    fun thumbFile(id: String) = File(workDir, "thumb_${id}_${System.nanoTime()}.jpg")

    fun delete(vararg paths: String?) {
        paths.filterNotNull().forEach { File(it).delete() }
    }

    fun clearWork() {
        workDir.deleteRecursively()
    }
}
