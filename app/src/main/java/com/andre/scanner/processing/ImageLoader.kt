package com.andre.scanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object ImageLoader {
    /** Lado maior das originais guardadas. Limita a memória com fotos de 12 MP ou mais. */
    const val MAX_SIDE = 3000

    /**
     * Lê uma imagem (galeria ou câmera), aplica a rotação EXIF, limita o lado maior a [MAX_SIDE]
     * e grava em [dest] como JPEG. Daí em diante a original está sempre "em pé" e todos os
     * cantos são medidos nela.
     *
     * [open] precisa abrir um stream novo a cada chamada (lemos o arquivo mais de uma vez).
     * Devolve o bitmap já normalizado; quem chama deve dar recycle().
     */
    fun normalize(open: () -> InputStream, dest: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Imagem inválida ou formato não suportado")

        val orientation = runCatching {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Não foi possível ler a imagem")

        val matrix = exifMatrix(orientation)
        val longSide = max(decoded.width, decoded.height)
        if (longSide > MAX_SIDE) {
            val f = MAX_SIDE.toFloat() / longSide
            matrix.preScale(f, f)
        }
        val upright = if (matrix.isIdentity) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
        dest.parentFile?.mkdirs()
        dest.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return upright
    }

    private fun exifMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
        }
    }

    /** Decodifica um arquivo nosso com o lado maior perto de [maxSide] (só reduz, nunca amplia). */
    fun decodeSampled(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return scaleToMax(bmp, maxSide)
    }

    /** Reduz para o lado maior caber em [maxSide]; recicla o original se criar um novo. */
    fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(src.width, src.height)
        if (longSide <= maxSide) return src
        val f = maxSide.toFloat() / longSide
        val out = Bitmap.createScaledBitmap(src, max(1, (src.width * f).toInt()), max(1, (src.height * f).toInt()), true)
        if (out !== src) src.recycle()
        return out
    }

    /**
     * Gira e reduz de uma vez, para o resultado (já girado) caber em [targetW] × [targetH].
     * Recicla [src] se criar um novo bitmap.
     */
    fun rotateAndFit(src: Bitmap, rotation: Int, targetW: Int, targetH: Int): Bitmap {
        val m = Matrix()
        val sideways = rotation % 180 != 0
        val rw = if (sideways) src.height else src.width
        val rh = if (sideways) src.width else src.height
        val f = min(targetW.toFloat() / rw, targetH.toFloat() / rh)
        if (f < 1f) m.postScale(f, f)
        if (rotation % 360 != 0) m.postRotate(rotation.toFloat())
        if (m.isIdentity) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }
}
