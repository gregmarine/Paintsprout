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

// Room writes every version of the generated schema here as JSON, one file per
// version bump. It is the trail a future schema-parity test would walk to catch
// the ORM and the hand-written DDL in the G1 contract disagreeing about a
// column — the same failure mode the Wacom app's SchemaSql.kt test guards
// against, and the reason it is worth paying for here even though Paper, which
// has no pinned-DDL contract to hold itself to, turns exportSchema off and
// keeps no history at all.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation("com.symmetricalpalmtree.gpaper:gpaper-core:0.1.12")
    implementation("com.symmetricalpalmtree.gpaper:gpaper-onyx:0.1.12")

    // Room — the ORM over both SQLCipher databases, paintsprout.db and every
    // .soil. ktx is here because index and soil access are coroutine-first from
    // G1 on; without it every DAO call needs a hand-wrapped suspend shim to get
    // off the UI thread, which is exactly the kind of boilerplate a dependency
    // exists to remove. The compiler runs through KSP, not kapt, because this
    // build is already KSP-only for serialization below, and a second
    // annotation-processing backend in one module would double the tax for no
    // second opinion worth having.
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // SQLCipher for every .soil and paintsprout.db open — always through
    // crypto/SoilCrypto, never straight through the platform's SQLite. The
    // artifact id is sqlcipher-android; net.zetetic's older
    // android-database-sqlcipher name stopped publishing before 4.6 and 4.6.1
    // does not exist under it, so sqlcipher-android is the one that actually
    // resolves — the same one Paper is already pinned to. androidx.sqlite:sqlite
    // supplies the SupportSQLiteOpenHelper contract that
    // NonDestructiveOpenHelperFactory wraps; sqlite-ktx was folded into that
    // same artifact upstream and was never split out as its own coordinate.
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // EncryptedSharedPreferences, for the two SecurePrefs files
    // (paintsprout_secure, paintsprout_dkeys) that hold the cached passphrase and
    // the derived raw keys — nowhere else is allowed to hold either. Pinned to
    // the same alpha Paper runs: the stable 1.x line of this artifact still has
    // not shipped.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // kotlinx.serialization is the only JSON path this codebase allows
    // (org.json is a standing no) — SketchbookMeta and the .soil format's other
    // JSON columns go through it. Same version Paper already has proven against
    // this Kotlin/KSP toolchain, so this is not the first mile it has run.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    // The mark ↔ row mapping is the one part of the page that can be checked with no tablet in the
    // room, and it speaks g-paper's Stroke on one side. Core only: gpaper-onyx would drag the BOOX
    // SDK onto the JVM test classpath, where none of it can run.
    testImplementation("com.symmetricalpalmtree.gpaper:gpaper-core:0.1.12")
}
