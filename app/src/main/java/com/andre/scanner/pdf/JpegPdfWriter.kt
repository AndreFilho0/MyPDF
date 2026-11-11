package com.andre.scanner.pdf

import java.io.Closeable
import java.io.OutputStream

/**
 * Gravador de PDF mínimo: uma imagem JPEG por página, embutida como está (filtro DCTDecode).
 *
 * O PdfDocument do Android regrava a imagem sem compressão JPEG, o que deixa cada página com
 * vários MB. Aqui o JPEG vai direto para o arquivo, então o tamanho do PDF é praticamente a
 * soma dos JPEGs.
 *
 * Uso: abrir com o número de páginas, chamar [addPage] na ordem e depois [close].
 */
class JpegPdfWriter(
    output: OutputStream,
    private val pageCount: Int,
    title: String,
) : Closeable {

    private val out = CountingStream(output)
    private val offsets = LongArray(3 + pageCount * 3)
    private var written = 0
    private var closed = false

    init {
        require(pageCount > 0) { "PDF precisa de pelo menos uma página" }
        out.write("%PDF-1.4\n".ascii())
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        obj(CATALOG, "<< /Type /Catalog /Pages $PAGES 0 R >>")
        val kids = (0 until pageCount).joinToString(" ") { "${pageObj(it)} 0 R" }
        obj(PAGES, "<< /Type /Pages /Kids [$kids] /Count $pageCount >>")
        obj(INFO, "<< /Title ${pdfText(title)} /Producer (MyPDF) >>")
    }

    /**
     * Adiciona uma página com [jpeg] (RGB de 3 canais, [width] × [height] px, como o
     * Bitmap.compress gera) ocupando a página inteira. A página é A4 em pé ou deitada conforme
     * a proporção da imagem.
     */
    fun addPage(jpeg: ByteArray, width: Int, height: Int) {
        check(written < pageCount) { "mais páginas do que o declarado" }
        val i = written
        val (pw, ph) = if (width > height) A4_H_PT to A4_W_PT else A4_W_PT to A4_H_PT

        begin(imageObj(i))
        out.write(
            ("<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n").ascii()
        )
        out.write(jpeg)
        out.write("\nendstream\nendobj\n".ascii())

        val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q".ascii()
        begin(contentObj(i))
        out.write("<< /Length ${content.size} >>\nstream\n".ascii())
        out.write(content)
        out.write("\nendstream\nendobj\n".ascii())

        obj(
            pageObj(i),
            "<< /Type /Page /Parent $PAGES 0 R /MediaBox [0 0 $pw $ph] " +
                "/Resources << /XObject << /Im0 ${imageObj(i)} 0 R >> >> /Contents ${contentObj(i)} 0 R >>",
        )
        written++
    }

    override fun close() {
        if (closed) return
        closed = true
        check(written == pageCount) { "declarou $pageCount páginas mas gravou $written" }
        val xref = out.count
        val size = offsets.size + 1
        val sb = StringBuilder()
        sb.append("xref\n0 $size\n0000000000 65535 f \n")
        for (off in offsets) sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", off))
        sb.append("trailer\n<< /Size $size /Root $CATALOG 0 R /Info $INFO 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().ascii())
        out.flush()
    }

    private fun begin(num: Int) {
        offsets[num - 1] = out.count
        out.write("$num 0 obj\n".ascii())
    }

    private fun obj(num: Int, body: String) {
        begin(num)
        out.write("$body\nendobj\n".ascii())
    }

    private class CountingStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
    }

    companion object {
        /** A4 em pontos (1/72 pol). */
        const val A4_W_PT = "595.28"
        const val A4_H_PT = "841.89"

        private const val CATALOG = 1
        private const val PAGES = 2
        private const val INFO = 3
        private fun imageObj(i: Int) = 4 + i * 3
        private fun contentObj(i: Int) = 5 + i * 3
        private fun pageObj(i: Int) = 6 + i * 3

        private fun String.ascii() = toByteArray(Charsets.ISO_8859_1)

        /** Texto em UTF-16BE com BOM, para acentos no título aparecerem certo. */
        internal fun pdfText(s: String): String {
            val bytes = s.toByteArray(Charsets.UTF_16BE)
            return "<FEFF" + bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) } + ">"
        }
    }
}
