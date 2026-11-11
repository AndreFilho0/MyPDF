package com.andre.scanner.processing

import android.graphics.Bitmap
import com.andre.scanner.model.Pt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

object CornerDetector {
    /** Lado maior da cópia reduzida usada na detecção da foto. */
    const val DETECT_SIDE = 1000
    /** Na câmera ao vivo usamos uma cópia ainda menor. */
    const val LIVE_SIDE = 500

    private const val MIN_AREA = 0.20  // a folha precisa ocupar pelo menos 20% da imagem
    // Um "contorno" do tamanho da foto quase sempre é o fundo (textura nas bordas, ou mesa tão
    // clara quanto o papel), não a folha. Nesse caso é melhor avisar e deixar o usuário ajustar.
    private const val MAX_AREA = 0.95

    /**
     * Detecta a folha em [bitmap] (não é modificado). Devolve os 4 cantos em coordenadas do
     * próprio bitmap, ordenados sup. esq., sup. dir., inf. dir., inf. esq.; ou null se não achou.
     */
    fun detect(bitmap: Bitmap, maxSide: Int = DETECT_SIDE): List<Pt>? {
        val longSide = max(bitmap.width, bitmap.height)
        val f = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
        val small = if (f < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true)
        } else bitmap
        try {
            val quad = withMats {
                val rgba = mat()
                Utils.bitmapToMat(small, rgba)
                detectInMat(rgba)
            }
            return quad?.let { Geometry.scale(it, 1f / f) }
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    /** [rgba] em CV_8UC4. Tenta a máscara de bordas e, se falhar, a de papel claro. */
    fun detectInMat(rgba: Mat): List<Pt>? = withMats {
        val imgArea = rgba.total().toDouble()

        val gray = mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // 1) Bordas: funciona bem com folha sobre fundo mais escuro.
        val edges = mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        val k5 = Mat.ones(5, 5, CvType.CV_8U).track()
        Imgproc.dilate(edges, edges, k5)
        quadFromMask(edges, imgArea)?.let { return@withMats it }

        // 2) Papel claro: pouca saturação e muito brilho. Ajuda com folha branca em mesa clara.
        val rgb = mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.GaussianBlur(rgb, rgb, Size(5.0, 5.0), 0.0)
        val hsv = mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val paper = mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 150.0), Scalar(180.0, 45.0, 255.0), paper)
        val ksz = max(5.0, max(rgba.cols(), rgba.rows()) / 66.0)  // ~15 px a 1000 px
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(ksz, ksz)).track()
        Imgproc.morphologyEx(paper, paper, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(paper, paper, Imgproc.MORPH_OPEN, kernel)
        quadFromMask(paper, imgArea)
    }

    private fun MatScope.quadFromMask(mask: Mat, imgArea: Double): List<Pt>? {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        contours.forEach { it.track() }
        val biggest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        val area = Imgproc.contourArea(biggest)
        if (area < MIN_AREA * imgArea) return null

        val hullIdx = MatOfInt().track()
        Imgproc.convexHull(biggest, hullIdx)
        val pts = biggest.toArray()
        val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray()).track()
        val perimeter = Imgproc.arcLength(hull, true)

        // Aumenta a tolerância até sobrarem exatamente 4 pontos.
        for (k in 1..40) {
            val approx = MatOfPoint2f().track()
            Imgproc.approxPolyDP(hull, approx, 0.005 * k * perimeter, true)
            val n = approx.total()
            if (n < 4) return null
            if (n == 4L) {
                val quad = Geometry.orderCorners(approx.toList().map { Pt(it.x.toFloat(), it.y.toFloat()) })
                val qArea = Geometry.area(quad)
                return quad.takeIf {
                    Geometry.isConvex(it) && qArea >= MIN_AREA * imgArea && qArea <= MAX_AREA * imgArea
                }
            }
        }
        return null
    }
}
