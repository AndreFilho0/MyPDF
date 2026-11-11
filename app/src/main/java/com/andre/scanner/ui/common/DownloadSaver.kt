package com.andre.scanner.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.andre.scanner.share.ShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Stable
class DownloadSaver internal constructor(private val start: (File) -> Unit) {
    var saving by mutableStateOf(false)
        internal set

    fun save(file: File) = start(file)
}

/** "Salvar em Downloads", pedindo a permissão de armazenamento só no Android 8 e 9. */
@Composable
fun rememberDownloadSaver(): DownloadSaver {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<File?>(null) }
    lateinit var saver: DownloadSaver

    fun doSave(file: File) {
        saver.saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ShareHelper.saveToDownloads(ctx, file) } }
            saver.saving = false
            result
                .onSuccess { Toast.makeText(ctx, "Salvo em Downloads como $it", Toast.LENGTH_LONG).show() }
                .onFailure {
                    Log.e("DownloadSaver", "falha ao salvar", it)
                    Toast.makeText(ctx, "Não foi possível salvar em Downloads", Toast.LENGTH_LONG).show()
                }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val f = pending
        pending = null
        if (ok && f != null) doSave(f)
        else if (!ok) Toast.makeText(ctx, "Sem permissão para salvar em Downloads", Toast.LENGTH_LONG).show()
    }

    saver = remember {
        DownloadSaver { file ->
            val needs = ShareHelper.needsStoragePermission &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            if (needs) {
                pending = file
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else doSave(file)
        }
    }
    return saver
}
