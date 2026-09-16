plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.proxypool.sdk"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        // API 24 covers ~97% of devices and every Android TV box worth having.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Partners consume this as an AAR; a release variant is what they get.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // OkHttp only. org.json is part of the Android platform, and every extra
    // dependency in an SDK is a version conflict in somebody else's app.
    api("com.squareup.okhttp3:okhttp:4.12.0")
}

// Published as an AAR so apps consume it with Gradle instead of copying source.
// Via JitPack (builds straight from GitHub — no server needed):
//   settings.gradle: maven { url = uri("https://jitpack.io") }
//   app build.gradle: implementation("com.github.ekinsipahi:proxy-pool-sdk-android:<tag>")
// Bump the tag (or use main-SNAPSHOT) to pull a newer SDK on the next build.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.ekinsipahi"
            artifactId = "proxy-pool-sdk-android"
            version = System.getenv("VERSION") ?: "1.0.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
