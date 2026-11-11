package com.andre.scanner.ui.common

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.andre.scanner.processing.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cache pequeno de miniaturas. Os caminhos mudam a cada reprocessamento, então não fica velho. */
private object ThumbCache : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** Mostra uma imagem nossa do disco, decodificada fora da thread principal. */
@Composable
fun FileImage(
    path: String?,
    maxSide: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val image by produceState<ImageBitmap?>(null, path, maxSide) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) {
                val key = "$p@$maxSide"
                (ThumbCache.get(key) ?: ImageLoader.decodeSampled(p, maxSide)?.also { ThumbCache.put(key, it) })
                    ?.asImageBitmap()
            }
        }
    }
    val img = image
    if (img != null) {
        Image(bitmap = img, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> "${(bytes + 1023) / 1024} KB"
}
