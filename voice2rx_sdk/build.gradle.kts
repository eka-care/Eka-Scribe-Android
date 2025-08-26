import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("kotlin-kapt")
}

val config =
    Properties().apply { load(project.rootProject.file("config.properties").inputStream()) }

val sdk = Properties().apply { load(project.rootProject.file("sdk.properties").inputStream()) }

android {
    namespace = "com.eka.voice2rx_sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34

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
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.ext.junit)
    api(libs.silero)
    implementation(libs.aws.android.sdk.s3)
    implementation(libs.aws.android.sdk.core)
    kapt(libs.room.compiler)
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
}