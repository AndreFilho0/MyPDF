package com.andre.scanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.andre.scanner.model.Mode
import com.andre.scanner.model.Pt
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.IOException

/** Junta as etapas: original → perspectiva → iluminação → JPEG. Sem nada de UI. */
object DocumentProcessor {
    const val PAGE_JPEG_QUALITY = 85
    const val THUMB_SIDE = 300

    fun process(originalPath: String, corners: List<Pt>, mode: Mode, out: File) {
        val src = BitmapFactory.decodeFile(originalPath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("Não foi possível abrir a foto original")

        val result: Bitmap = withMats {
            val rgba = mat()
            Utils.bitmapToMat(src, rgba)
            src.recycle()
            val rgb = mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            rgba.release()

            val warped = PerspectiveCorrector.warp(rgb, corners).track()
            rgb.release()

            val enhanced: Mat = when (mode) {
                Mode.NATURAL -> Enhancer.natural(warped)
                Mode.SCANNER -> Enhancer.scanner(warped)
            }.track()
            warped.release()

            Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(enhanced, it)
            }
        }
        try {
            out.parentFile?.mkdirs()
            out.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, PAGE_JPEG_QUALITY, it) }
        } finally {
            result.recycle()
        }
    }

    /** Miniatura de [THUMB_SIDE] px já girada, para a grade de páginas. */
    fun makeThumb(processedPath: String, rotation: Int, out: File) {
        val bmp = ImageLoader.decodeSampled(processedPath, THUMB_SIDE * 2)
            ?: throw IOException("Não foi possível abrir a página processada")
        val thumb = ImageLoader.rotateAndFit(bmp, rotation, THUMB_SIDE, THUMB_SIDE)
        try {
            out.parentFile?.mkdirs()
            out.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } finally {
            thumb.recycle()
        }
    }
}
