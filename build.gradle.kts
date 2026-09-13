// Top-level build file. Versions live here so the two modules cannot drift.
//
// Pinned against the toolchain this project is built and tested with:
// Gradle 8.12 + JDK 21 (the JBR that ships with Android Studio). If your Studio
// brings newer ones, let the upgrade assistant bump them together -- AGP and the
// Kotlin plugin are the pair that must stay compatible.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
