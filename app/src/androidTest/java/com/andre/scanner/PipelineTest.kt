package com.andre.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * Roda o pipeline inteiro com OpenCV de verdade, numa "foto" sintética de 12 MP: mesa escura,
 * folha clara inclinada em perspectiva, linhas de texto e uma sombra.
 */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "pipeline-test").apply { deleteRecursively(); mkdirs() }

    // Cantos da folha na foto 3000 × 4000.
    private val sheet = listOf(Pt(520f, 610f), Pt(2480f, 420f), Pt(2650f, 3500f), Pt(380f, 3620f))

    @Before fun init() { check(OpenCVLoader.initLocal()) }

    private fun synthPhoto(w: Int = 3000, h: Int = 4000, quad: List<Pt> = sheet, table: Int = Color.rgb(70, 50, 40)): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(table)
        val path = Path().apply {
            moveTo(quad[0].x, quad[0].y); quad.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        // Papel com sombra: mais escuro embaixo à esquerda.
        val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, h.toFloat(), w.toFloat(), 0f, Color.rgb(170, 168, 160), Color.rgb(245, 243, 238), Shader.TileMode.CLAMP)
        }
        c.drawPath(path, paper)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 40, 120); strokeWidth = 12f }
        for (i in 0 until 25) {
            val t = 0.1f + i * 0.032f
            val a = lerp(quad[0], quad[3], t); val b = lerp(quad[1], quad[2], t)
            val s = lerp(a, b, 0.1f); val e = lerp(a, b, 0.85f)
            c.drawLine(s.x, s.y, e.x, e.y, ink)
        }
        return bmp
    }

    private fun lerp(a: Pt, b: Pt, t: Float) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun assertNear(expected: List<Pt>, actual: List<Pt>, tol: Float) {
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            val d = Geometry.dist(e, a)
            assertTrue("canto $i: esperado $e, veio $a (distância $d)", d < tol)
        }
    }

    @Test fun detectsSheetCorners() {
        val photo = synthPhoto()
        val found = CornerDetector.detect(photo)
        assertNotNull("não detectou a folha", found)
        assertNear(sheet, found!!, tol = 40f)  // ~1% da foto
        photo.recycle()
    }

    @Test fun detectsWhiteSheetOnLightWoodTable() {
        // Madeira clara: brilho parecido com o do papel na sombra, mas com cor (saturação).
        val photo = synthPhoto(table = Color.rgb(205, 170, 125))
        val found = CornerDetector.detect(photo)
        assertNotNull("não detectou a folha em mesa clara", found)
        assertNear(sheet, found!!, tol = 60f)
        photo.recycle()
    }

    @Test fun grayTableAsBrightAsPaperIsNotMistakenForTheSheet() {
        // Sem cor nem contraste, não dá para separar; o certo é desistir (null) e não devolver a mesa.
        val photo = synthPhoto(table = Color.rgb(200, 195, 185))
        val found = CornerDetector.detect(photo)
        if (found != null) assertNear(sheet, found, tol = 60f)
        photo.recycle()
    }

    @Test fun returnsNullWithoutSheet() {
        val bmp = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60, 60, 60)) }
        assertNull(CornerDetector.detect(bmp))
    }

    @Test fun fullPipelineToPdf() {
        val src = File(dir, "photo.jpg")
        synthPhoto().let { b -> src.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }; b.recycle() }

        val original = File(dir, "original.jpg")
        val norm = ImageLoader.normalize({ src.inputStream() }, original)
        assertEquals(2250, norm.width)  // lado maior limitado a 3000
        assertEquals(3000, norm.height)
        val t0 = System.nanoTime()
        val corners = CornerDetector.detect(norm)!!
        val tDetect = (System.nanoTime() - t0) / 1e6
        norm.recycle()

        val pages = Mode.entries.mapIndexed { i, mode ->
            val out = File(dir, "page_$mode.jpg")
            val t1 = System.nanoTime()
            DocumentProcessor.process(original.path, corners, mode, out)
            val ms = (System.nanoTime() - t1) / 1e6
            Log.i("PipelineTest", "modo $mode: detecção ${tDetect}ms, processamento ${ms}ms, ${out.length() / 1024} KB")

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(out.path, opts)
            assertEquals(1654, opts.outWidth)
            assertEquals(2339, opts.outHeight)

            // O fundo do papel deve sair claro e a sombra bem mais fraca que na foto.
            val bmp = BitmapFactory.decodeFile(out.path)
            val bright = luma(bmp.getPixel(1500, 100))
            val shadow = luma(bmp.getPixel(150, 2250))
            Log.i("PipelineTest", "modo $mode: papel claro=$bright, papel na sombra=$shadow")
            if (mode == Mode.SCANNER) {
                assertTrue("fundo do Scanner não ficou branco: $shadow", shadow > 235)
            } else {
                assertTrue("Natural não clareou a sombra: $shadow", shadow > 175)
            }
            bmp.recycle()
            Page(id = "p$i", originalPath = original.path, corners = corners, mode = mode,
                rotation = if (i == 1) 90 else 0, processedPath = out.path, status = PageStatus.DONE)
        }

        for (q in Quality.entries) {
            val pdf = File(dir, "doc_$q.pdf")
            PdfExporter.export(pages, "Teste ção", q, pdf)
            val head = pdf.readBytes().copyOfRange(0, 8).toString(Charsets.ISO_8859_1)
            assertEquals("%PDF-1.4", head)
            Log.i("PipelineTest", "PDF $q: ${pdf.length() / 1024} KB para ${pages.size} páginas")
            assertTrue("PDF grande demais: ${pdf.length()}", pdf.length() < pages.size * 600 * 1024)

            // Abre com o leitor de PDF do próprio Android para garantir que o arquivo é válido.
            android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                assertEquals(2, r.pageCount)
                r.openPage(0).use { p -> assertTrue(p.height > p.width) }
                r.openPage(1).use { p -> assertTrue("página girada deveria estar deitada", p.width > p.height) }
            }
        }
    }

    private fun luma(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()
}
