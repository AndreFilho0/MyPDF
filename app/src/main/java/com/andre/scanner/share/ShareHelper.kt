package com.andre.scanner.share

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

object ShareHelper {
    private const val PDF = "application/pdf"

    private fun uriFor(ctx: Context, file: File) =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)

    /** Abre o menu do Android (WhatsApp, Drive, e-mail...). O envio usa a internet do outro app. */
    fun sharePdf(ctx: Context, file: File) {
        val uri = uriFor(ctx, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = PDF
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Compartilhar PDF"))
    }

    fun openPdf(ctx: Context, file: File) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(ctx, file), PDF)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ctx.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "Nenhum app para abrir PDF instalado", Toast.LENGTH_SHORT).show()
        }
    }

    /** No Android 8 e 9 precisa de WRITE_EXTERNAL_STORAGE antes de chamar [saveToDownloads]. */
    val needsStoragePermission get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Copia o PDF para a pasta Downloads. Devolve o nome com que ficou salvo. */
    fun saveToDownloads(ctx: Context, file: File): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, PDF)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Não foi possível criar o arquivo em Downloads")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Não foi possível gravar em Downloads")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            // O sistema pode ter renomeado para "nome (1).pdf" se já existia.
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: file.name
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = uniqueFile(dir, file.nameWithoutExtension, "pdf")
            file.copyTo(dest)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(PDF), null)
            dest.name
        }
    }

    fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) f = File(dir, "$base (${n++}).$ext")
        return f
    }
}
