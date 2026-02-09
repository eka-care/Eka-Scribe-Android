import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("com.google.devtools.ksp")
    kotlin("kapt")
}

val config =
    Properties().apply { load(project.rootProject.file("config.properties").inputStream()) }

val sdk = Properties().apply { load(project.rootProject.file("sdk.properties").inputStream()) }

android {
    namespace = "com.eka.voice2rx_sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        targetSdk = 35

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
                "SDK_VERSION_NAME",
                "\"${sdk["SDK_VERSION_NAME"]}\""
            )
            buildConfigField(
                "String",
                "SDK_BUILD_NUMBER",
                "\"${sdk["SDK_BUILD_NUMBER"]}\""
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
            isJniDebuggable = true
            buildConfigField(
                "String",
                "SDK_VERSION_NAME",
                "\"${sdk["SDK_VERSION_NAME"]}\""
            )
            buildConfigField(
                "String",
                "SDK_BUILD_NUMBER",
                "\"${sdk["SDK_BUILD_NUMBER"]}\""
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    packaging {
        jniLibs {
            // Use sherpa-onnx's bundled libonnxruntime.so (compatible with sherpa-onnx-jni)
            pickFirsts += listOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/x86/libonnxruntime.so",
                "lib/x86_64/libonnxruntime.so"
            )
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.eka.voice2rx"
                artifactId = "voice2rx"
                version = "0.0.1"
            }
        }
    }
    tasks.named("publishReleasePublicationToMavenLocal") {
        dependsOn(tasks.named("bundleReleaseAar"))
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    api(libs.silero)
    implementation(libs.aws.android.sdk.s3)
    implementation(libs.aws.android.sdk.core)
    ksp(libs.room.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.google.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.urlconnection)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit) {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    api(libs.kotlinx.coroutines)
    api(libs.brotili)
    implementation(libs.ok2curl)
    implementation(libs.retrofit.gson)
    implementation(libs.haroldadmin.networkresponseadapter)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.onnxruntime.android)
    api(libs.eka.network.android)
    // TensorFlow Lite for Whisper ASR
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // MediaPipe LLM Inference for Gemma clinical notes generation
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}