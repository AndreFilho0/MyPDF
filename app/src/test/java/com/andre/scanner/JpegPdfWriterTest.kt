package com.andre.scanner

import com.andre.scanner.pdf.JpegPdfWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegPdfWriterTest {
    private fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        JpegPdfWriter(out, 2, "Lista ção").use { pdf ->
            pdf.addPage(ByteArray(1000) { it.toByte() }, 1240, 1754)
            pdf.addPage(ByteArray(500) { 7 }, 1754, 1240)
        }
        return out.toByteArray()
    }

    @Test fun xrefOffsetsPointToObjects() {
        val bytes = build()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))

        val startXref = Regex("startxref\\n(\\d+)").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref", startXref))

        val entries = Regex("(\\d{10}) 00000 n ").findAll(text.substring(startXref)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(9, entries.size)  // catálogo, páginas, info + 3 por página
        entries.forEachIndexed { i, off -> assertTrue("obj ${i + 1}", text.startsWith("${i + 1} 0 obj", off)) }
    }

    @Test fun pageSizeFollowsImageOrientation() {
        val text = String(build(), Charsets.ISO_8859_1)
        assertTrue(text.contains("/MediaBox [0 0 595.28 841.89]"))
        assertTrue(text.contains("/MediaBox [0 0 841.89 595.28]"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/Length 1000"))
    }

    @Test(expected = IllegalStateException::class)
    fun failsWhenPagesAreMissing() {
        JpegPdfWriter(ByteArrayOutputStream(), 2, "x").use { it.addPage(ByteArray(10), 10, 20) }
    }
}
