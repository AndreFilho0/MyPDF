package com.andre.scanner

import android.app.Application
import org.opencv.android.OpenCVLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Carrega a biblioteca nativa que vem dentro do APK. Não baixa nada.
        check(OpenCVLoader.initLocal()) { "Falha ao carregar OpenCV" }
    }
}
