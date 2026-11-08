plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The default remains the free-software build used by GitHub and F-Droid.
// Google Play integrations are compiled only with -PplayStore=true.
val playStore = providers.gradleProperty("playStore").orNull == "true"

android {
    namespace = "io.github.nielstron.brushkin"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    defaultConfig {
        applicationId = "de.nielstron.brushkin"
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "0.9.0"
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
        debug {
            // Install the hardware experiment beside the user's normal app and documents.
            if (providers.gradleProperty("openClPrototype").orNull == "true") {
                applicationIdSuffix = ".opencl"
                versionNameSuffix = "-opencl"
            }
            resValue("string", "app_name", "Brushkin Preview")
        }
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
            keepDebugSymbols += listOf("**/libLiteRt*.so", "**/liblitert_jni.so", "**/libandroidx.graphics.path.so")
        }
    }

    // F-Droid and reproducible builds: no Google dependency-metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    sourceSets {
        getByName("main").java.srcDir(if (playStore) "src/play/java" else "src/foss/java")
        if (playStore) {
            getByName("main").res.srcDir("src/play/res")
            getByName("debug").res.srcDir("src/playDebug/res")
            getByName("debug").manifest.srcFile("src/play/AndroidManifest.xml")
            getByName("release").manifest.srcFile("src/play/AndroidManifest.xml")
            getByName("test").java.srcDir("src/testPlay/java")
        }
    }
    buildFeatures { compose = true; buildConfig = true }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

if (playStore) tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val settings = file("src/play/res/values/store.xml").readText()
        check(!settings.contains("ca-app-pub-3940256099942544")) { "Configure Brushkin's production AdMob IDs before building a Play release." }
        check(!settings.contains("translatable=\"false\"></string>")) { "Configure the Play billing public key before building a Play release." }
    }
}

dependencies {
    if (playStore) {
        implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.6.0")
        implementation("com.google.android.ump:user-messaging-platform:4.0.0")
        implementation("com.android.billingclient:billing:9.1.0")
    }
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

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
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose brings Espresso 3.5, which calls an InputManager method removed in newer Android.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
