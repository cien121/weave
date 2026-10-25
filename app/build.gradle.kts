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
        versionCode = 35
        versionName = "1.0.29"

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

// ---------------------------------------------------------------------------
// Two gomobile AARs, one name collision — handled here, not with pickFirst.
//
// libv2ray.aar and mistframework.aar BOTH embed:
//  - the gomobile runtime classes go.* (go.Seq, go.Universe, go.error, ...)
//  - jni/arm64-v8a/libgojni.so — but with DIFFERENT native code inside
//    (36MB Xray vs 3.8MB Mist).
//
// Why the naive fixes are wrong:
//  - packaging.resources.pickFirsts does NOT apply to .class files, so it
//    cannot fix checkDebugDuplicateClasses.
//  - packaging.jniLibs.pickFirsts WOULD fix the .so collision by dropping
//    one file — silently deleting one backend's entire native library.
//    That is a runtime break, not a fix.
//
// What this does instead (build-time only, no source changes):
//  1. Strip go/* from mistframework.aar's classes.jar — go.* then comes
//     only from libv2ray.aar (the gomobile bridge API is stable, and
//     go.Seq is what loads "gojni" for Xray).
//  2. Rename mist's libgojni.so to libmistgojni.so inside the repacked
//     AAR, so BOTH native libraries land in the APK.
//  3. MistVpnService loads "mistgojni" explicitly before creating the
//     engine (see mist/MistVpnService.kt), so mist's native methods
//     resolve even though go.Seq only loads "gojni".
// ---------------------------------------------------------------------------
val strippedAarDir = layout.buildDirectory.dir("strippedAars")

val unpackMistAar by tasks.registering(Copy::class) {
    from(zipTree(file("libs/mistframework.aar")))
    into(strippedAarDir.map { it.dir("mist-unpacked") })
}

val stripMistGoClasses by tasks.registering(Copy::class) {
    dependsOn(unpackMistAar)
    from(zipTree(strippedAarDir.map { it.file("mist-unpacked/classes.jar") })) {
        exclude("go/**")
    }
    into(strippedAarDir.map { it.dir("mist-classes") })
}

val rejarMistClasses by tasks.registering(Zip::class) {
    dependsOn(stripMistGoClasses)
    archiveFileName.set("classes.jar")
    destinationDirectory.set(strippedAarDir.map { it.dir("mist-repacked") })
    from(strippedAarDir.map { it.dir("mist-classes") })
}

val repackMistAar by tasks.registering(Zip::class) {
    dependsOn(rejarMistClasses)
    archiveFileName.set("mistframework.aar")
    destinationDirectory.set(strippedAarDir)
    from(strippedAarDir.map { it.dir("mist-unpacked") }) {
        exclude("classes.jar")
        exclude("jni/arm64-v8a/libgojni.so")
    }
    from(rejarMistClasses.map { it.archiveFile })
    from(strippedAarDir.map { it.file("mist-unpacked/jni/arm64-v8a/libgojni.so") }) {
        into("jni/arm64-v8a")
        rename { "libmistgojni.so" }
    }
}

// Make sure the repacked AAR exists before any compilation starts.
tasks.named("preBuild") {
    dependsOn(repackMistAar)
}

dependencies {
    // Xray-core AAR (official build from 2dust/AndroidLibXrayLite).
    // CI downloads it into app/libs/ before building; see .github/workflows/build.yml
    implementation(files("libs/libv2ray.aar"))

    // Independent Mist framework AAR (gomobile bind of the mobile package
    // in cien121/mist-framework; committed under app/libs/ — the
    // connector cannot push binary files, so this AAR is uploaded via
    // the GitHub web UI, not via the file-push flow).
    // NOTE: the dependency is the REPACKED AAR produced by the
    // repackMistAar task above (go/* classes stripped, libgojni.so
    // renamed to libmistgojni.so), NOT libs/mistframework.aar directly.
    // Using the raw AAR breaks the build with duplicate go.* classes
    // and would collide with libv2ray's libgojni.so in the APK.
    implementation(files(repackMistAar.map { it.archiveFile }))

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
