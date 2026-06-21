plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
}

android {
    namespace = "com.example.chessanalysis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chessanalysis"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags.add("-std=c++17")
                cppFlags.add("-O3")
                cppFlags.add("-DNNUE_EMBEDDING_OFF")
                arguments.add("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

}

// --- Stockfish 18 big NNUE network ---
// Not embedded (NNUE_EMBEDDING_OFF) and too large to commit, so it is downloaded
// into res/raw at build time. The small net (nnue_network.nnue) is already committed.
// Must match EvalFileDefaultNameBig in cpp/stockfish/evaluate.h.
val bigNetName = "nn-c288c895ea92.nnue"
val bigNetFile = layout.projectDirectory.file("src/main/res/raw/nnue_big.nnue").asFile

val downloadBigNet = tasks.register("downloadBigNet") {
    description = "Downloads the Stockfish 18 big NNUE network into res/raw."
    outputs.file(bigNetFile)
    doLast {
        if (bigNetFile.exists() && bigNetFile.length() > 1_000_000L) {
            logger.lifecycle("Big NNUE net already present (${bigNetFile.length()} bytes).")
            return@doLast
        }
        bigNetFile.parentFile.mkdirs()
        val url = uri("https://tests.stockfishchess.org/api/nn/$bigNetName").toURL()
        logger.lifecycle("Downloading Stockfish big NNUE net $bigNetName ...")
        url.openStream().use { input -> bigNetFile.outputStream().use { input.copyTo(it) } }
        logger.lifecycle("Saved ${bigNetFile.length()} bytes to ${bigNetFile.absolutePath}")
    }
}

tasks.named("preBuild") { dependsOn(downloadBigNet) }