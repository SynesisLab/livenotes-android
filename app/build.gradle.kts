// App module: Compose UI + a foreground capture service + on-device whisper
// (NDK/CMake, sources under app/src/main/cpp/whisper — a whisper.cpp
// submodule added on first build, see README).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sinesislab.livenotes"
    // 36 = Android 16: unlocks Notification.ProgressStyle for live notifications
    compileSdk = 36
    // installed locally (Android Studio's SDK dir) — matches the default's range
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.sinesislab.livenotes"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.1.8"
        ndk {
            // arm64-v8a is the only real-device target; x86_64 keeps emulation usable
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
    packaging {
        jniLibs.pickFirsts += "lib/**/libc++_shared.so"
    }
}

dependencies {
    // 1.17 = the first stable with NotificationCompat.ProgressStyle +
    // setRequestPromotedOngoing (1.18/1.19 require AGP 9.x — out of reach here)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}