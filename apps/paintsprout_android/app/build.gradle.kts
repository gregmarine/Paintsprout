plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
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

        // Backup's Google Drive credentials, read from the environment at build
        // time and never from a file in the repo. Set DRIVE_CLIENT_ID and
        // DRIVE_CLIENT_SECRET in your shell profile — see docs/backup.md. A build
        // without them compiles fine; the Drive slot says it isn't configured.
        buildConfigField(
            "String", "DRIVE_CLIENT_ID", "\"${System.getenv("DRIVE_CLIENT_ID") ?: ""}\"",
        )
        buildConfigField(
            "String", "DRIVE_CLIENT_SECRET", "\"${System.getenv("DRIVE_CLIENT_SECRET") ?: ""}\"",
        )
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

    // Room writes its generated schema here at build time. It is committed, and a
    // unit test compares it against the hand-written DDL in SchemaSql — the ORM
    // and the constants must describe the same tables or an open fails validation
    // on a device with a perfectly good file.
    sourceSets["main"].assets.srcDir("$projectDir/schemas")

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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // The LOCAL backup destination: a folder tree the user picks, reached through
    // the Storage Access Framework, so no storage permission is ever requested.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Off-thread stroke bakes: Dispatchers.Default composites, Main swaps + invalidates.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Stylus latency (Apache-2.0): kalman-predicted pen positions so the live
    // ink tail is drawn where the pen will be when the frame reaches glass.
    implementation("androidx.input:input-motionprediction:1.0.0-beta05")

    // Room — one instance per open .soil sketchbook, plus one for the global
    // index. The object tables are wide and sparse and read through hand-written
    // SQL; Room is here for entity/DAO plumbing, migrations, and the
    // schema-validation-on-open that catches a DDL drift before a user does.
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // SQLCipher — whole-file encryption for the index and every sketchbook, with
    // stock defaults (PBKDF2-HMAC-SHA512 x256,000, AES-256) so a stock sqlcipher
    // CLI opens our files with the same passphrase. Never customise kdf_iter or
    // the page size: that portability IS the format.
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.5.0")

    // Keystore-backed storage for the GLOBAL passphrase and the derived raw keys.
    // A per-sketchbook passphrase never lands here — that is the whole difference
    // between the two key scopes.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // The container's JSON payloads: notebook_meta, surface parameter bags, and
    // pigment recipes. Code-generated, no reflection.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")

    // A real SQLite engine on the JVM, so the schema constants are executed and
    // read back in unit tests rather than merely string-matched. A typo in a
    // CREATE TABLE is otherwise invisible until a device opens a file with it.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
}
