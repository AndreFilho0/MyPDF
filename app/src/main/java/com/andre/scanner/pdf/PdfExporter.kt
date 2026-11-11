package com.andre.scanner.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.andre.scanner.model.Page
import com.andre.scanner.processing.ImageLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

enum class Quality(val label: String, val shortSide: Int, val longSide: Int, val jpeg: Int) {
    /** 150 dpi: padrão, cabe bem no WhatsApp. */
    NORMAL("Normal", 1240, 1754, 80),
    /** 200 dpi. */
    HIGH("Alta", 1654, 2339, 90),
}

object PdfExporter {

    /** Gera o PDF com uma página A4 por item de [pages], na ordem da lista. */
    fun export(pages: List<Page>, title: String, quality: Quality, out: File, onProgress: (Int) -> Unit = {}) {
        require(pages.isNotEmpty()) { "Nenhuma página" }
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        try {
            tmp.outputStream().buffered().use { stream ->
                JpegPdfWriter(stream, pages.size, title).use { pdf ->
                    pages.forEachIndexed { i, p ->
                        val path = p.processedPath ?: throw IOException("Página ${i + 1} ainda não foi processada")
                        val (jpeg, w, h) = encodePage(path, p.rotation, quality)
                        pdf.addPage(jpeg, w, h)
                        onProgress(i + 1)
                    }
                }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IOException("Não foi possível salvar o PDF")
        } finally {
            tmp.delete()
        }
    }

    private fun encodePage(path: String, rotation: Int, q: Quality): Triple<ByteArray, Int, Int> {
        val src = BitmapFactory.decodeFile(path) ?: throw IOException("Não foi possível abrir a página")
        val landscape = (src.width > src.height) xor (rotation % 180 != 0)
        val (tw, th) = if (landscape) q.longSide to q.shortSide else q.shortSide to q.longSide
        val bmp = ImageLoader.rotateAndFit(src, rotation, tw, th)
        try {
            val buf = ByteArrayOutputStream(512 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, q.jpeg, buf)
            return Triple(buf.toByteArray(), bmp.width, bmp.height)
        } finally {
            bmp.recycle()
        }
    }
}
