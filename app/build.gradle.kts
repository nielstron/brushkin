plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.nielstron.brushkin"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.nielstron.brushkin"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // LiteRT (object selection) ships native code: ARM only (phones), which keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Release signing comes from the environment (GitHub Actions secrets). Without it the
    // release stays unsigned, e.g. on F-Droid's build server, which signs with its own key.
    val keystore = System.getenv("BRUSHKIN_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("BRUSHKIN_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("BRUSHKIN_KEY_ALIAS")
            keyPassword = System.getenv("BRUSHKIN_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Store native libraries (LiteRT) compressed so the APK stays small.
    // Not stripping them keeps the (already stripped) libraries byte-identical on every machine,
    // NDK installed or not, which reproducible builds need.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    // F-Droid and reproducible builds: no Google dependency-metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures { compose = true }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Full system emoji picker (categories, skin tones, recents).
    implementation("androidx.emoji2:emoji2-emojipicker:1.5.0")
    // Object selection: MobileSAM (downloaded on first use) on LiteRT 2 (Apache-2.0) with its GPU
    // accelerator (ML Drift, Apache-2.0). Google Play's ai-delivery library is only used for
    // downloading models/NPU packs through Play, which we don't do: excluded so no proprietary
    // code ships (R8 drops the unused delivery class).
    implementation("com.google.ai.edge.litert:litert:2.3.0") { exclude(group = "com.google.android.play") }
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.0") { exclude(group = "com.google.android.play") }

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
