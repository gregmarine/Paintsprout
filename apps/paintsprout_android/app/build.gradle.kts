plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.paintsprout"
    compileSdk = 35

    defaultConfig {
        // Same applicationId as the Flutter build — the native app replaces it
        // completely on the target device.
        applicationId = "com.symmetricalpalmtree.paintsprout"

        // AGSL RuntimeShader (the pigment-mixing pipeline) requires API 33+.
        // It is foundational here, not an enhancement, so there is no sub-33
        // fallback path — minSdk reflects that hard requirement. Deviates from
        // Notesprout's minSdk 29 for this reason. The Movink 11 target runs 33+.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Movink 11 (and every other sprout target device) is 64-bit ARM.
            abiFilters += "arm64-v8a"
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

    buildTypes {
        // No applicationIdSuffix on debug: the native app is meant to replace the
        // previous Flutter install outright, so debug and release share one id.
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Off-thread stroke bakes: Dispatchers.Default composites, Main swaps + invalidates.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Stylus latency (Apache-2.0): kalman-predicted pen positions so the live
    // ink tail is drawn where the pen will be when the frame reaches glass.
    implementation("androidx.input:input-motionprediction:1.0.0-beta05")

    testImplementation("junit:junit:4.13.2")
}
