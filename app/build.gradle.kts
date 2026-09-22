plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mrashidcit.project4one_to_onevideocall"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mrashidcit.project4one_to_onevideocall"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default signaling server URL used by the app. 10.0.2.2 is the Android EMULATOR's
        // alias for "localhost" on your development machine (see README > Signaling Server URL).
        // On a PHYSICAL device this will NOT work - change it in-app to your machine's LAN IP,
        // e.g. ws://192.168.1.100:8080, or edit the default below.
        buildConfigField("String", "DEFAULT_SIGNALING_SERVER_URL", "\"ws://10.28.86.115:8080\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Dependency injection (Hilt) - see di/AppModule.kt and di/WebRtcModule.kt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Signaling: OkHttp WebSocket client + kotlinx.serialization for the JSON protocol
    // used by the existing Project #3 Node.js signaling server.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // WebRTC: native Android WebRTC APIs (org.webrtc package). See README > WebRTC dependency.
    implementation(libs.stream.webrtc.android)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
