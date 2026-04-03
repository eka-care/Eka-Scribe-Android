plugins {
    alias(libs.plugins.android.application)
    // Required: Kotlin plugin needed to compile the CoroutineHelper bridge file.
    // The SDK exposes suspend functions which cannot be called from pure Java —
    // a thin Kotlin bridge is the only way to properly create coroutine state machines.
    // This does NOT affect existing Java code; it only enables compiling .kt files.
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.eka.voice2rx.javasample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eka.voice2rx.javasample"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines)

    // Eka Scribe SDK
    implementation(project(":scribesdk"))
}
