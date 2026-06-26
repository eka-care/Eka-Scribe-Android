# EkaScribe Android SDK — Project Setup

Everything you need to clone the repository, build all modules, and run both sample applications.

For SDK concepts see [getting-started.md](getting-started.md).
For integration code examples see [usage-guide.md](usage-guide.md).

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Cloning the Repository](#2-cloning-the-repository)
3. [Project Structure](#3-project-structure)
4. [Build Configuration Files](#4-build-configuration-files)
5. [Running the Kotlin Test App](#5-running-the-kotlin-test-app)
6. [Running the Java Sample App](#6-running-the-java-sample-app)
7. [Build Commands Reference](#7-build-commands-reference)
8. [SDK Publishing via JitPack](#8-sdk-publishing-via-jitpack)
9. [Notes on Config Files](#9-notes-on-config-files)

---

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **Android Studio** | Ladybug (2024.2.1) or newer | Meerkat (2024.3.1) recommended for full Kotlin DSL and KSP support |
| **JDK** | 17 | OpenJDK 17 or equivalent; matches `jitpack.yml` requirement |
| **Android SDK** | minSdk 24, compileSdk 36 | Install SDK 36 via SDK Manager → SDK Platforms |
| **Gradle** | 8.x | Wrapper included in repo — no separate installation needed |
| **Git** | Any | For cloning |
| **Eka Care API token** | — | Required only for live end-to-end testing; not needed for a build-only check |

**Check your JDK version:**

```bash
java -version
# Expected: openjdk version "17.x.x"
```

**Configure Android Studio to use JDK 17:**
File → Project Structure → SDK Location → Gradle JDK → select JDK 17.

---

## 2. Cloning the Repository

```bash
git clone https://github.com/eka-care/Eka-Scribe-Android.git
cd Eka-Scribe-Android
```

Open in Android Studio:
1. File → Open
2. Select the `Eka-Scribe-Android` directory
3. Android Studio will sync Gradle automatically on first open

> If Gradle sync fails with "SDK location not found", ensure `ANDROID_HOME` is set or create `local.properties` with `sdk.dir=/path/to/android/sdk`.

---

## 3. Project Structure

```
Eka-Scribe-Android/
│
├── scribesdk/                          # SDK library module (published to JitPack)
│   ├── build.gradle.kts
│   └── src/main/java/com/eka/scribesdk/
│       ├── api/          Public facade, config, callback, all public models
│       ├── session/      Session lifecycle and server API orchestration
│       ├── recorder/     PCM audio capture (AndroidAudioRecorder)
│       ├── pipeline/     Coroutine-based bounded-channel audio pipeline
│       ├── chunker/      VAD-driven audio chunking (Silero ONNX)
│       ├── analyser/     SQUIM audio quality analysis (ONNX)
│       ├── encoder/      PCM → MP3 encoding (Mp3AudioEncoder)
│       ├── data/         Room DB, S3 upload, REST API (Retrofit)
│       └── common/       Error types, logging, utilities
│
├── app/                                # Kotlin test app (Compose UI)
│   ├── build.gradle.kts
│   └── src/main/java/com/eka/voice2rx/
│       ├── TestActivity.kt             Main test screen — full SDK integration example
│       ├── BaseApplication.kt          Application class
│       └── CallRecordingScanner.kt     Finds call recordings on device
│
├── sample-java/                        # Java sample app (XML/View Binding)
│   ├── build.gradle.kts
│   └── src/main/java/com/eka/voice2rx/javasample/
│       ├── TestActivity.java           Main test screen — full Java SDK integration
│       ├── MyScribeCallback.java       EkaScribeCallback implementation
│       ├── MyTokenStorage.java         TokenStorage implementation
│       ├── SampleApplication.java      Application class
│       └── bridge/
│           └── CoroutineHelper.kt      Kotlin bridge for suspend functions and Flow
│
├── docs/                               # Developer documentation
│   ├── getting-started.md              Concepts and architecture
│   ├── usage-guide.md                  API reference with Kotlin + Java examples
│   ├── setup.md                        This file
│   └── LLD.md                          Low-level design: class diagrams, DB schema
│
├── build.gradle.kts                    Root build file (plugin declarations)
├── settings.gradle.kts                 Module declarations + repository configuration
├── gradle.properties                   JVM args, AndroidX flag, Kotlin code style
├── config.properties                   Build-time secrets (NOT in VCS — see § 9)
├── sdk.properties                      SDK version and build number
├── jitpack.yml                         JitPack CI configuration
└── gradle/
    └── libs.versions.toml              Centralized dependency version catalog
```

**Three Gradle modules:**

| Module | Role |
|---|---|
| `:scribesdk` | The SDK library. Published as an AAR to JitPack. |
| `:app` | Kotlin test application. Depends on `:scribesdk` directly. |
| `:sample-java` | Java sample application. Depends on `:scribesdk` directly. |

---

## 4. Build Configuration Files

### `config.properties` — Build-time secrets

Contains API endpoint URLs and the S3 bucket name injected as `BuildConfig` fields at compile time.

```properties
COG_URL=https://cog.eka.care/
DEVELOPER_URL=https://api.eka.care/
BUCKET_NAME=m-prod-voice-record
OUTPUT_DIR=eka_scribe_audio
```

> This file is **not committed to VCS**. You must create it locally. Obtain the values from the Eka Care developer team.
> For a build-only check (no live network), use placeholder values — see [§ 9](#9-notes-on-config-files).

`scribesdk/build.gradle.kts` reads this file and injects fields into `BuildConfig`:

```kotlin
// scribesdk/build.gradle.kts (excerpt)
val configProps = Properties().apply {
    load(rootProject.file("config.properties").inputStream())
}
buildConfigField("String", "COG_URL", "\"${configProps["COG_URL"]}\"")
```

### `sdk.properties` — SDK version

```properties
SDK_VERSION_NAME=4.2.4
SDK_BUILD_NUMBER=1
```

Update `SDK_VERSION_NAME` before tagging a release on GitHub.

### `settings.gradle.kts` — Module and repository declarations

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        mavenLocal()   // used when testing against a locally published SDK
    }
}
include(":app", ":scribesdk", ":sample-java")
```

### `build.gradle.kts` (root) — Plugin declarations

Declares `android.application`, `kotlin.android`, `android.library`, and `ksp` as `apply false` — each module applies only what it needs.

### `scribesdk/build.gradle.kts` — SDK module build

Key build details:
- **KSP** for Room annotation processing
- **`maven-publish`** plugin — publishes the release AAR to Maven Local (consumed by JitPack)
- **Jacoco** — `jacocoTestReport` task generates coverage reports under `build/reports/jacoco/`
- **ProGuard rules** — in `scribesdk/proguard-rules.pro`

### `gradle/libs.versions.toml` — Centralized dependency versions

All dependency versions are declared here. Key entries:

```toml
[versions]
kotlin             = "2.1.0"
room-version       = "2.6.1"
onnxruntime-android = "1.23.2"
silero             = "2.0.10"
awsAndroidSdkS3    = "2.22.0"
ekaNetworkAndroid  = "2.0.6"
```

---

## 5. Running the Kotlin Test App

The `app` module is a fully functional Kotlin + Compose test app that demonstrates the complete SDK integration.

### Step 1 — Create `config.properties`

If not already present (see [§ 4](#4-build-configuration-files)).

### Step 2 — Set your auth token

Open [app/src/main/java/com/eka/voice2rx/TestActivity.kt](https://github.com/eka-care/Eka-Scribe-Android/blob/main/app/src/main/java/com/eka/voice2rx/TestActivity.kt) and update the token:

```kotlin
// Near the top of TestActivity.kt
var TEST_ACCESS_TOKEN = "your-access-token-here"
```

### Step 3 — Select the run configuration

In Android Studio, select **`app`** from the run configuration dropdown (top toolbar).

### Step 4 — Run

Click **Run** or press `Shift+F10`. The app will install and launch `TestActivity`.

**Via command line:**

```bash
./gradlew :app:installDebug
adb shell am start -n com.eka.voice2rx/.TestActivity
```

### Step 5 — Grant permissions

On first launch, tap **START RECORDING** and grant the microphone permission when prompted.

### LogCat filters

```bash
# App-level events
adb logcat -s TestActivity

# SDK internal logs (when debugMode = true)
adb logcat | grep EkaScribe
```

See [TEST_APP_GUIDE.md](../TEST_APP_GUIDE.md) for the full testing workflow.

---

## 6. Running the Java Sample App

The `sample-java` module is a pure Java application (with a single `CoroutineHelper.kt` bridge file) that demonstrates Java integration patterns.

### Step 1 — Set your auth token

Open `sample-java/src/main/java/com/eka/voice2rx/javasample/MyTokenStorage.java`:

```java
private String accessToken = "your-access-token-here";
private String refreshToken = "your-refresh-token-here";
```

### Step 2 — Set your client ID

Open `TestActivity.java`, find `initializeSdk()`, and set your `clientId`:

```java
EkaScribeConfig config = new EkaScribeConfig(
    "your-client-id",   // <- update this
    "android",
    true,
    true,
    networkConfig
);
```

### Step 3 — Select the run configuration

In Android Studio, select **`sample-java`** from the run configuration dropdown.

### Step 4 — Run

```bash
./gradlew :sample-java:installDebug
```

> **Note about `CoroutineHelper.kt`:** The `sample-java` module contains one Kotlin file (`bridge/CoroutineHelper.kt`). This is intentional — it is the minimum Kotlin required to bridge suspend functions and Flow for Java callers. The `kotlin-android` plugin in `sample-java/build.gradle.kts` compiles it. All other app code is pure Java.

---

## 7. Build Commands Reference

Run from the project root directory.

| Command | Description |
|---|---|
| `./gradlew build` | Build all three modules |
| `./gradlew :scribesdk:assembleRelease` | Build release AAR for the SDK |
| `./gradlew :scribesdk:assembleDebug` | Build debug AAR for the SDK |
| `./gradlew :app:installDebug` | Build and install Kotlin test app |
| `./gradlew :sample-java:installDebug` | Build and install Java sample app |
| `./gradlew :scribesdk:testDebugUnitTest` | Run SDK unit tests |
| `./gradlew :scribesdk:testReleaseUnitTest` | Run SDK release unit tests |
| `./gradlew :scribesdk:jacocoTestReport` | Generate Jacoco coverage report (in `scribesdk/build/reports/jacoco/`) |
| `./gradlew :scribesdk:publishReleasePublicationToMavenLocal` | Publish SDK AAR to local Maven (`~/.m2`) |
| `./gradlew :scribesdk:testReleaseUnitTest :scribesdk:publishReleasePublicationToMavenLocal` | Full JitPack build (mirrors `jitpack.yml`) |

**Testing a local SDK build in the test app:**

1. Publish to Maven Local: `./gradlew :scribesdk:publishReleasePublicationToMavenLocal`
2. In `settings.gradle.kts`, ensure `mavenLocal()` is in the repository list (it already is).
3. In `app/build.gradle.kts`, replace the JitPack dependency with the local one:
   ```kotlin
   implementation("com.eka.voice2rx:voice2rx:0.0.1")
   ```

---

## 8. SDK Publishing via JitPack

### How it works

JitPack builds the SDK on demand when a consumer requests a version for the first time. It is triggered by:
1. A consumer adding `implementation("com.github.eka-care:Eka-Scribe-Android:TAG")` to their `build.gradle`.
2. JitPack cloning the repo at `TAG`, running the `install` command from `jitpack.yml`, and caching the artifact.

### `jitpack.yml`

```yaml
jdk:
  - openjdk17
before_install:
  - sdk install java 17.0.2-open
  - sdk use java 17.0.2-open
install:
  - ./gradlew :scribesdk:testReleaseUnitTest :scribesdk:publishReleasePublicationToMavenLocal
```

### Releasing a new version

1. Update `SDK_VERSION_NAME` in `sdk.properties`.
2. Commit and push to `main`.
3. Create a GitHub tag matching the new version:
   ```bash
   git tag v4.2.5
   git push origin v4.2.5
   ```
4. JitPack automatically detects the tag. The artifact becomes available at:
   ```
   com.github.eka-care:Eka-Scribe-Android:4.2.5
   ```

### Dependency coordinates

| Context | groupId:artifactId |
|---|---|
| **JitPack (consumer)** | `com.github.eka-care:Eka-Scribe-Android` |
| **Local Maven (development)** | `com.eka.voice2rx:voice2rx` |

JitPack overrides the local Maven coordinates with the GitHub-derived `com.github.eka-care:Eka-Scribe-Android` groupId/artifactId.

---

## 9. Notes on Config Files

### `config.properties` — required to build

`scribesdk/build.gradle.kts` reads `config.properties` at configuration time. If the file is missing, Gradle sync will fail.

**For contributors who only want to explore the code** without running live tests, create a placeholder:

```bash
cat > config.properties << 'EOF'
COG_URL=https://placeholder.example.com/
DEVELOPER_URL=https://placeholder.example.com/
BUCKET_NAME=placeholder-bucket
OUTPUT_DIR=scribe_output
EOF
```

The project will compile and the test apps will install. All network calls will fail (connection refused), but the SDK itself can be explored and modified.

### No `.env` file required

The SDK has no runtime `.env` file. The only runtime secret is the **auth token**, which is provided by the host app through `TokenStorage` (set in `MyTokenStorage.java` / `TestActivity.kt`). This token never touches the build system.

### `config.properties` vs `sdk.properties`

| File | Purpose | Who edits it |
|---|---|---|
| `config.properties` | API endpoint URLs + S3 bucket (build-time, injected into `BuildConfig`) | Infrastructure / platform team |
| `sdk.properties` | SDK version and build number | Developer cutting a release |
