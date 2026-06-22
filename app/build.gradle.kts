plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
}

// AI Coach: toggle the on-device llama.cpp GGUF runner. OFF = fast builds, no native llama compile,
// app still runs (Gemma cards show "disabled in this build"). Flip via -PaiCoachLlama=true or gradle.properties.
val llamaEnabled = (project.findProperty("aiCoachLlama") as String?)?.toBoolean() ?: false

// Pinned llama.cpp tag, passed to CMake (-DLLAMA_TAG) which clones the source at configure time.
// Constraints:
//  - >= Jan-2025 vocab refactor (b4404 too old → "unknown type name 'llama_vocab'" at compile).
//  - >= Gemma 3 support (PR #12343, merged 2025-03-12 ~b4875): otherwise nativeLoad() fails on the
//    gemma-3-1B GGUF ("unknown model architecture") → model never loads → coach falls back, no "thinking".
// NOTE: must be a REAL tag — llama.cpp only tags actual CI builds, not every bNNNN integer (b5000 doesn't exist).
val llamaTag = "b5050"

android {
    namespace = "com.example.chessanalysis"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.chessanalysis"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("boolean", "LLAMA_ENABLED", llamaEnabled.toString())

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags.add("-std=c++17")
                cppFlags.add("-O3")
                cppFlags.add("-DNNUE_EMBEDDING_OFF")
                arguments.add("-DANDROID_STL=c++_shared")
                arguments.add("-DLLAMA_ENABLED=${if (llamaEnabled) "ON" else "OFF"}")
                arguments.add("-DLLAMA_TAG=$llamaTag")
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

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // NOTE: on-device LLM runs via the bundled llama.cpp native runner (GGUF), not MediaPipe.
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

// NOTE: llama.cpp source is cloned by CMake at configure time (see cpp/CMakeLists.txt, -DLLAMA_TAG=$llamaTag),
// not by a Gradle task — Android Studio's CMake sync runs before preBuild, so the dir must exist by then.