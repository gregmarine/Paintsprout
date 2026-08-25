plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.symmetricalpalmtree.paintsproutonyx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.paintsproutonyx"

        // 29 rather than the Wacom app's 33: nothing here needs AGSL. The floor
        // that mattered there was a shader pipeline, and arc 1 draws graphite
        // through g-paper instead.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-onyx"

        ndk {
            // BOOX devices are 64-bit ARM. This also drops the Onyx SDK's x86_64
            // native libs, which are not 16 KB-aligned and would fail packaging
            // checks for an ABI no target device has.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        // A development build is a different app from a shipped one and says so in
        // its id, so both can sit on the NA5C at once and a build under test can
        // never replace the install holding real drawings. The cost is that every
        // per-id thing starts empty in the .dev build — its own library, and from
        // G1 its own recovery key, which it will ask for if a sketchbook is copied
        // across.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // The Onyx SDK ships its own copy of libc++_shared.so in several of its
            // AARs. They are the same library, and the merger has no way to know
            // that — without this it stops at the collision rather than picking one.
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // The drawing engine. Core carries the strokes and the renderers; onyx carries
    // the BOOX SDK and the raw-drawing view. gpaper-ratta is deliberately absent —
    // this app runs on one panel.
    //
    // Pinned at 0.1.6, the newest published build, so the Onyx build baggage above
    // is exercised by a real dependency rather than sitting here as untested
    // boilerplate. It re-pins to 0.1.7 when g-paper's Phase 10 publishes the
    // textured PENCIL renderer, which is what G3 waits on.
    implementation("com.symmetricalpalmtree.gpaper:gpaper-core:0.1.6")
    implementation("com.symmetricalpalmtree.gpaper:gpaper-onyx:0.1.6")

    testImplementation("junit:junit:4.13.2")
}
