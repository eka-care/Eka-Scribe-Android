import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("com.google.devtools.ksp")
}

val config =
    Properties().apply { load(project.rootProject.file("config.properties").inputStream()) }

android {
    namespace = "com.eka.scribesdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField(
                "String",
                "COG_URL",
                "\"${config["COG_URL"]}\""
            )
            buildConfigField(
                "String",
                "DEVELOPER_URL",
                "\"${config["DEVELOPER_URL"]}\""
            )
        }
        debug {
            buildConfigField(
                "String",
                "COG_URL",
                "\"${config["COG_URL"]}\""
            )
            buildConfigField(
                "String",
                "DEVELOPER_URL",
                "\"${config["DEVELOPER_URL"]}\""
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
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Coroutines
    implementation(libs.kotlinx.coroutines)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.google.gson)

    // Network response adapter
    implementation(libs.haroldadmin.networkresponseadapter)

    // Eka Network (auth + networking)
    api(libs.eka.network.android)

    // AWS S3
    implementation(libs.aws.android.sdk.core)
    implementation(libs.aws.android.sdk.s3)

    // Silero VAD
    implementation(libs.silero)

    // ONNX Runtime (SQUIM)
    implementation(libs.onnxruntime.android)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
