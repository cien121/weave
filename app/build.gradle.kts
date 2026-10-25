plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.chan.weave"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chan.weave"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "1.0.31"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Only arm64-v8a is shipped (matches the device fleet)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("ciancoreDebug") {
            // All CI builds share one fixed keystore (decoded from the
            // CIANCORE_DEBUG_KEYSTORE_B64 secret by the workflow), so every
            // build carries the same signature and installs over the previous
            // one. Local dev machines fall back to ~/.android/debug.keystore.
            val ciKeystore = System.getenv("CIANCORE_KEYSTORE_PATH")
            if (ciKeystore != null) {
                storeFile = file(ciKeystore)
                storeType = "PKCS12"
            } else {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            }
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("ciancoreDebug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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
    // (ndk abiFilters live inside defaultConfig)
}

dependencies {
    // Independent Mist framework AAR (gomobile bind of the mobile package
    // in cien121/mist-framework; committed under app/libs/ —
    // the connector cannot push binary files, so this AAR is uploaded via
    // the GitHub web UI, not via the file-push flow).
    // Weave is Mist-only since 1.0.30: Xray/libv2ray is fully removed
    // (use SwiftNG for Xray). No duplicate gomobile classes remain
    // because mistframework.aar is the only gomobile AAR in the app.
    implementation(files("libs/mistframework.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.7.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Room (node storage)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
