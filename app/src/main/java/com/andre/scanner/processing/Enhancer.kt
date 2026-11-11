package com.andre.scanner.processing

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Correção de iluminação. As duas versões estimam o "fundo" do papel (a iluminação sem a
 * tinta) e dividem a imagem por ele.
 *
 * O fundo é calculado em 1/4 da resolução: dá o mesmo resultado (ele é suave de qualquer
 * jeito) e fica muitas vezes mais rápido que um blur de sigma 25 na imagem cheia.
 */
object Enhancer {
    private const val BG_SCALE = 4
    private const val CLOSE_KERNEL = 31   // em pixels da imagem cheia
    private const val BLUR_SIGMA = 25.0   // idem
    private const val MAX_GAIN = 1.6
    /** "Meio ganho": aplica metade da correção para não lavar as cores da tinta. */
    private const val NATURAL_STRENGTH = 0.5
    private const val BLACK = 150
    private const val WHITE = 245
    private const val GAMMA = 1.6

    /** Natural: [rgb] CV_8UC3 → CV_8UC3. Clareia sombras sem mudar a cor da tinta. */
    fun natural(rgb: Mat): Mat = withMats {
        val gray = mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val bgSmall = background(gray)
        val paper = percentile(bgSmall, 0.90).coerceAtLeast(1.0)

        // ganho = papel / fundo, limitado em [1, 1.6], e então só metade dele.
        val gain = mat()
        bgSmall.convertTo(gain, CvType.CV_32F)
        Core.max(gain, Scalar(1.0), gain)
        Core.divide(paper, gain, gain)
        Core.min(gain, Scalar(MAX_GAIN), gain)
        Core.max(gain, Scalar(1.0), gain)
        gain.convertTo(gain, -1, NATURAL_STRENGTH, 1.0 - NATURAL_STRENGTH)
        val gainFull = mat()
        Imgproc.resize(gain, gainFull, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val channels = mutableListOf<Mat>()
        Core.split(rgb, channels)
        channels.forEach { it.track() }
        val f = mat()
        for (ch in channels) {
            ch.convertTo(f, CvType.CV_32F)
            Core.multiply(f, gainFull, f)
            f.convertTo(ch, CvType.CV_8U)  // satura em 255
        }
        val out = Mat()
        Core.merge(channels, out)
        out
    }

    /** Scanner: [rgb] CV_8UC3 → CV_8UC1. Fundo branco e tinta escura. */
    fun scanner(rgb: Mat): Mat = withMats {
        val gray = mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val bgSmall = background(gray)
        val bg = mat()
        Imgproc.resize(bgSmall, bg, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.max(bg, Scalar(1.0), bg)

        val norm = mat()
        Core.divide(gray, bg, norm, 255.0)   // cinza / fundo, em 0..255

        val out = Mat()
        Core.LUT(norm, levelsLut().track(), out)
        out
    }

    /** Fundo estimado em 1/4 da resolução, CV_8UC1. Fica no escopo de quem chama. */
    private fun MatScope.background(gray: Mat): Mat {
        val small = mat()
        val size = Size((gray.cols() / BG_SCALE).toDouble().coerceAtLeast(1.0), (gray.rows() / BG_SCALE).toDouble().coerceAtLeast(1.0))
        Imgproc.resize(gray, small, size, 0.0, 0.0, Imgproc.INTER_AREA)
        val k = odd(CLOSE_KERNEL / BG_SCALE)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble())).track()
        // CLOSE (dilata e erode) apaga traços escuros finos: sobra só o papel.
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), BLUR_SIGMA / BG_SCALE)
        return small
    }

    /** Níveis 150 → preto, 245 → branco e gama 1,6. */
    private fun levelsLut(): Mat {
        val lut = Mat(1, 256, CvType.CV_8U)
        val bytes = ByteArray(256) { v ->
            val t = ((v - BLACK).toDouble() / (WHITE - BLACK)).coerceIn(0.0, 1.0)
            (t.pow(GAMMA) * 255).roundToInt().toByte()
        }
        lut.put(0, 0, bytes)
        return lut
    }

    private fun percentile(gray8: Mat, p: Double): Double {
        val n = gray8.total().toInt()
        val data = ByteArray(n)
        val cont = if (gray8.isContinuous) gray8 else gray8.clone()
        cont.get(0, 0, data)
        if (cont !== gray8) cont.release()
        val hist = IntArray(256)
        for (b in data) hist[b.toInt() and 0xFF]++
        val target = (n * p).toInt()
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return v.toDouble()
        }
        return 255.0
    }

    private fun odd(v: Int) = if (v % 2 == 0) v + 1 else v
}
