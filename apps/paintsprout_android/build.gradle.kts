plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // Room's annotation processor, and the serializer generator for the JSON
    // payloads the container carries (notebook_meta, surface params, recipes).
    // Both versions are pinned to the Kotlin version above — KSP releases track
    // the compiler exactly, so these three move together or not at all.
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}
