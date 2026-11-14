plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.debridmediamanager.rex"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.debridmediamanager.rex"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("REX_ANDROID_VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.environmentVariable("REX_ANDROID_VERSION").orElse("0.1.0-dev").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // github: the release on GitHub, which starts with rex-addons' own repository. play: the
    // store build, which adds no repository and ships only the bundled Internet Archive addon.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("String[]", "DEFAULT_REPOS", "{\"https://github.com/debridmediamanager/rex-addons\"}")
        }
        create("play") {
            dimension = "store"
            buildConfigField("String[]", "DEFAULT_REPOS", "{}")
        }
    }
    signingConfigs {
        // The upload key lives outside the repository; CI writes it from a secret.
        if (System.getenv("REX_ANDROID_KEYSTORE") != null) {
            create("release") {
                storeFile = file(System.getenv("REX_ANDROID_KEYSTORE"))
                storePassword = System.getenv("REX_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REX_ANDROID_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("REX_ANDROID_KEY_PASSWORD") ?: System.getenv("REX_ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // -Pamazon builds the play flavor as one APK for the Amazon Appstore, which takes no AAB:
    // the two ARM ABIs Fire TV and Fire tablets use (Fire TV Sticks are 32-bit).
    val amazon = providers.gradleProperty("amazon").isPresent
    if (amazon) defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    // One APK per ABI for GitHub; the AAB carries all three and Play splits it.
    splits {
        abi {
            isEnable = !amazon
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    // The native programs run from nativeLibraryDir (Android refuses to execute anything in an
    // app's writable storage), so the installer must extract them: legacy packaging keeps
    // extractNativeLibs on, in the APKs and in the splits Play builds from the AAB.
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += setOf("**/librex.so", "**/librexaddons.so", "**/libffprobe.so", "**/libffmpeg.so") } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
