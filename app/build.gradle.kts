plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Por padrão o APK leva só arm64-v8a (celulares atuais), o que corta o peso do OpenCV.
// Para o emulador: ./gradlew installDebug -Pabis=x86_64
val abiList = (findProperty("abis") as String?)?.split(",")?.map { it.trim() } ?: listOf("arm64-v8a")

// Versão: o pipeline de release passa -PversionName=1.2.0 -PversionCode=10200 a partir da tag.
val appVersionName = (findProperty("versionName") as String?) ?: "1.0.0"
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1

// Chave de assinatura da release. No CI vem dos secrets do GitHub; localmente dá para
// exportar as mesmas variáveis. Sem elas, a release é assinada com a chave de debug.
val releaseKeystore: String? = System.getenv("MYPDF_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.andre.scanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andre.scanner"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += abiList }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MYPDF_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MYPDF_KEY_ALIAS")
                keyPassword = System.getenv("MYPDF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.opencv)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
