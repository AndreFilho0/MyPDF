package com.andre.scanner.processing

import com.andre.scanner.model.Pt
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object PerspectiveCorrector {
    /** A4 a 200 dpi. */
    const val A4_W = 1654
    const val A4_H = 2339

    /**
     * Recorta o quadrilátero [corners] de [src] e devolve um retângulo A4. Se a folha estava
     * deitada na foto, a saída também fica deitada (2339 × 1654), sem esticar; o usuário
     * gira depois. Quem chama deve dar release() no resultado.
     */
    fun warp(src: Mat, corners: List<Pt>): Mat = withMats {
        val landscape = Geometry.isLandscape(corners)
        val w = (if (landscape) A4_H else A4_W).toDouble()
        val h = (if (landscape) A4_W else A4_H).toDouble()
        val from = MatOfPoint2f(*corners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()).track()
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h)).track()
        val m = Imgproc.getPerspectiveTransform(from, to).track()
        val out = Mat()
        Imgproc.warpPerspective(src, out, m, Size(w, h), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        out
    }
}
