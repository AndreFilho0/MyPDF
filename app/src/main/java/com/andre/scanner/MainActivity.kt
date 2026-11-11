package com.andre.scanner

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import java.io.File
import com.andre.scanner.ui.camera.CameraScreen
import com.andre.scanner.ui.crop.CropScreen
import com.andre.scanner.ui.export.ExportScreen
import com.andre.scanner.ui.home.HomeScreen
import com.andre.scanner.ui.pages.PagesScreen
import com.andre.scanner.ui.preview.PdfPreviewScreen
import com.andre.scanner.ui.theme.ScannerTheme
import com.andre.scanner.viewmodel.DocumentViewModel

object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val CROP = "crop"
    const val PAGES = "pages"
    const val EXPORT = "export"
    const val PREVIEW = "preview?path={path}&fromExport={fromExport}"
    fun preview(file: File, fromExport: Boolean) = "preview?path=${Uri.encode(file.path)}&fromExport=$fromExport"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScannerTheme { ScannerApp() }
        }
    }
}

@Composable
private fun ScannerApp() {
    // Criado fora do NavHost para ser o mesmo ViewModel em todas as telas.
    val vm: DocumentViewModel = viewModel()
    val nav = rememberNavController()
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    // Photo Picker: seleção múltipla sem permissão de armazenamento.
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) {
            vm.addFromUris(uris)
            nav.navigate(Routes.CROP)
        }
    }
    val openGallery = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val openCamera = { nav.navigate(Routes.CAMERA) }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                vm, onCamera = openCamera, onGallery = openGallery,
                onOpen = { file -> nav.navigate(Routes.preview(file, fromExport = false)) },
            )
        }
        composable(Routes.CAMERA) {
            CameraScreen(
                vm,
                onDone = { captured ->
                    if (captured > 0) nav.navigate(Routes.CROP) { popUpTo(Routes.CAMERA) { inclusive = true } }
                    else nav.popBackStack()
                },
            )
        }
        composable(Routes.CROP) {
            CropScreen(vm, onFinished = { nav.goToPages() })
        }
        composable(Routes.PAGES) {
            PagesScreen(
                vm,
                onCamera = openCamera,
                onGallery = openGallery,
                onAdjust = { id -> vm.startReview(id); nav.navigate(Routes.CROP) },
                onExport = { nav.navigate(Routes.EXPORT) },
                onExit = { vm.discard(); nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.EXPORT) {
            ExportScreen(
                vm,
                onBack = { nav.popBackStack() },
                onPreview = { file -> nav.navigate(Routes.preview(file, fromExport = true)) },
                onFinish = { vm.discard(); nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(
            Routes.PREVIEW,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("fromExport") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val args = entry.arguments
            PdfPreviewScreen(
                file = File(args?.getString("path").orEmpty()),
                fromExport = args?.getBoolean("fromExport") ?: false,
                onBack = { nav.popBackStack() },
                onFinish = { vm.discard(); nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
    }
}

/** Pilha sempre fica Início → Páginas, venha de onde vier. */
private fun NavHostController.goToPages() = navigate(Routes.PAGES) {
    popUpTo(Routes.HOME)
    launchSingleTop = true
}

private const val MAX_PICK = 50
