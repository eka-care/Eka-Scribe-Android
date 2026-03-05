import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("com.google.devtools.ksp")
    id("maven-publish")
    id("jacoco")
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
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
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
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

jacoco {
    toolVersion = "0.8.10"
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {

    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        // data models is not considered used in requests and responses
        "**/data/remote/models/**",
        // Android-dependent classes (require device/emulator, not JVM-testable)
        "**/recorder/**",
        "**/encoder/**",
        "**/analyser/**",
        "**/data/remote/upload/S3ChunkUploader*",
        "**/data/remote/S3CredentialProvider*",
        "**/data/remote/S3Credentials*",
        "**/data/remote/ScribeApiClient*",
        "**/data/local/db/ScribeDatabase*",
        "**/data/local/db/ScribeDao*",
        "**/data/local/file/**",
        "**/data/DefaultDataManager*",
        "**/common/logging/DefaultLogger*",
        "**/common/logging/LogLevel*",
        "**/common/logging/LogInterceptor*",
        "**/common/util/DefaultTimeProvider*",
        // Public facade depends on Android Context
        "**/api/EkaScribe*",
        "**/api/EkaScribeConfig*"
    )

    val debugTree = fileTree("${buildDir}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }

    val mainSrc = "${project.projectDir}/src/main/java"

    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(debugTree))

    executionData.setFrom(
        fileTree(buildDir) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
            )
        }
    )
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
        dependsOn(tasks.named("testReleaseUnitTest"))
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
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
