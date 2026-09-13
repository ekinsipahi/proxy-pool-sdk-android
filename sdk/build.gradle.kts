plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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
