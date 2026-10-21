plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aliedu.kern.core.terminal.pty"
    compileSdk = 37
    compileSdkMinor = 0
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28
        ndk {
            // Every ABI the app ships, so the built-in device shell works everywhere. The proot
            // runtime that provides the full Ubuntu userspace is only vendored for arm64-v8a and
            // x86_64; the 32-bit builds fall back to the device shell.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cFlags += listOf("-O2", "-Wall", "-Wextra", "-Wno-unused-parameter")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
