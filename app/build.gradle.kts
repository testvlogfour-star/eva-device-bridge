plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eva.devicebridge"
    // compileSdk 34 (Android 14) gives access to the latest AccessibilityService
    // APIs used below; the app still runs fine on devices up to the current release.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eva.devicebridge"
        // minSdk 30 (Android 11) is required because AccessibilityService.takeScreenshot()
        // -- the API that lets us grab screenshots WITHOUT the MediaProjection
        // permission/consent dialog -- was introduced in API 30. This is the
        // single most important version constraint in this project: it is what
        // makes "one-time Accessibility toggle, nothing else" possible.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // NanoHTTPD: a ~50KB, dependency-free embeddable HTTP server. Chosen over
    // hand-rolling raw ServerSocket parsing (fragile, more code to get right)
    // and over com.sun.net.httpserver (not reliably present in Android's
    // libcore across OS versions). It runs happily inside an
    // AccessibilityService's process with no extra permissions beyond
    // INTERNET (needed even for 127.0.0.1-only sockets on Android).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
