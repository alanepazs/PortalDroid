plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alan.portaldroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alan.portaldroid"
        // minSdk 31 = Android 12, tal cual pediste ("de 12 para arriba").
        // De paso evita líos de compatibilidad con currentWindowMetrics,
        // que también pide API 30+.
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Escáner de QR. Trae la pantalla de escaneo lista, así no hay que armar
    // una cámara a mano con CameraX sólo para leer un código.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
