plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aliedu.kern.agent"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.aliedu.kern.agent"
        minSdk = 28
        targetSdk = 37
        // versionCode is the release date (YY MM DD): 29 Oct 2026 -> 261029.
        versionCode = 261029
        versionName = "1.0.2"
        testInstrumentationRunner = "com.aliedu.kern.agent.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    /**
     * One APK per ABI, plus a universal one.
     *
     * The proot runtime is only vendored for arm64-v8a and x86_64, so the armeabi-v7a and x86
     * builds ship the terminal PTY and the built-in device shell but not the Ubuntu environment.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    /**
     * The release signing key.
     *
     * Kern is published as a GitHub release rather than through the Play Store, so the same key has
     * to be reused for every future version: a different key would turn each release into an
     * uninstall-and-reinstall instead of an in-place update. This points at the standard Android
     * debug keystore, which is a stable, long-lived key that already exists on every machine.
     *
     * v2 and v3 signing are both enabled. v3 is what makes a future key rotation possible, so it
     * is worth having signed in from the start rather than discovering its absence later.
     *
     * Keep `~/.android/debug.keystore` safe. Losing it means losing the ability to update an
     * already-installed copy of Kern.
     */
    signingConfigs {
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        // proot ships as `lib*.so` and must exist as a real file in nativeLibraryDir to be executable,
        // so native libraries are extracted at install time rather than loaded straight from the APK.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:workspace"))
    implementation(project(":core:providers:api"))
    implementation(project(":core:providers:openai"))
    implementation(project(":core:agent"))
    implementation(project(":core:tools"))
    implementation(project(":core:auth"))
    implementation(project(":core:terminal:api"))
    implementation(project(":core:terminal:implementation"))
    // Bundles the Termux proot runtime into the APK's native library dir (executable on targetSdk 36).
    implementation(project(":core:terminal:binaries"))

    implementation(project(":feature:home"))
    implementation(project(":feature:files"))
    implementation(project(":feature:agent"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:preview"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.google.material)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
