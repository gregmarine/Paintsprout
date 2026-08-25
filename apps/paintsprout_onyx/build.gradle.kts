plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // Room's annotation processor and the serializer generator for the JSON the
    // container carries. Both are pinned to the Kotlin version above — KSP
    // releases track the compiler exactly, so the three move together or not at
    // all. Neither is used yet; the data core arrives in G1.
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}
