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
    namespace = "com.eka.scribe_sdk"
    compileSdk = 35

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
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // Voice Activity analysis
    api(libs.silero)

    // Serialization library
    implementation(libs.google.gson)

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}