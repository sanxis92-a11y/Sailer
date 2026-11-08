plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sailer.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nauvel.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.4.0"
    }

    // Dos versiones de la app:
    //  - full: la completa, para instalar desde GitHub (APK).
    //  - play: la de Google Play. Sin descargas de YouTube ni plataformas
    //    de pago y con aviso de derechos de autor.
    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            // Nombre distinto al de Play para que las dos versiones puedan convivir.
            applicationIdSuffix = ".full"
            buildConfigField("boolean", "STREAM_DOWNLOADS", "true")
            buildConfigField("boolean", "DISGUISE", "true")
            // Lista de imágenes, galerías de varias páginas y recolector de enlaces (solo uso personal).
            buildConfigField("boolean", "GRABBER", "true")
        }
        create("play") {
            dimension = "store"
            // Descargador incluido, pero con webs bloqueadas (ver media/StorePolicy).
            buildConfigField("boolean", "STREAM_DOWNLOADS", "true")
            // Disimulo con calculadora funcional. Si Google lo rechazara, poner "false"
            // y quitar el icono .LauncherCalculator en src/play/AndroidManifest.xml.
            buildConfigField("boolean", "DISGUISE", "true")
            buildConfigField("boolean", "GRABBER", "false")
        }
    }

    signingConfigs {
        // Clave de depuración fija (incluida en el repo) para que cada APK
        // compilado en GitHub se pueda instalar encima del anterior.
        // No es secreta: solo se usa si no hay clave privada configurada.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Clave privada: GitHub la pasa desde sus "secrets" al compilar
        // (nunca se guarda en el repositorio).
        val releaseStore = System.getenv("SAILER_KEYSTORE_FILE")
        if (!releaseStore.isNullOrBlank() && file(releaseStore).exists()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("SAILER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SAILER_KEY_ALIAS") ?: "sailer"
                keyPassword = System.getenv("SAILER_KEY_PASSWORD") ?: System.getenv("SAILER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Clave privada si está configurada; si no, la de depuración.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
