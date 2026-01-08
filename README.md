# EkaScribe SDK Documentation

EkaScribe SDK (Voice2Rx) is an Android SDK for voice-based medical transcription and documentation.
It provides real-time voice recording, transcription, and intelligent medical documentation
generation with support for multiple languages and output formats.

**Version:** 3.0.6

**Minimum Android SDK:** 23 (Android 6.0)

---

## 1. Installation

### Add Maven Repository

Add the JitPack repository to your project's `settings.gradle.kts` (or `build.gradle`):

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Add below line to fetch SDK from Jitpack
        maven { url = uri("<https://jitpack.io>") }
    }
}

```

### Add Dependencies

Add the EkaScribe SDK and required networking dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.eka-care:Eka-Scribe-Android:${LATEST_VERSION}")
}

```

### Required Permissions

Add the following permissions to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**Note:** You must request `RECORD_AUDIO` permission at runtime for Android 6.0 and above.

---

## 2. Initialization

Initialize the SDK in your `Application` class or main `Activity`.

### Step 1: Implement Lifecycle Callbacks

Create a class that implements `Voice2RxLifecycleCallbacks` to handle session events.

```kotlin
import com.eka.voice2rx_sdk.sdkinit.Voice2RxLifecycleCallbacks
import com.eka.voice2rx_sdk.common.models.EkaScribeError

class MyVoice2RxCallbacks : Voice2RxLifecycleCallbacks {
    override fun onStartSession(sessionId: String) {
        // Recording started
    }

    override fun onStopSession(sessionId: String, recordedFiles: Int) {
        // Get result when ready after stopping the session
        CoroutineScope(Dispatchers.IO).launch {
            Voice2Rx.pollEkaScribeResult(sessionId = sessionId).onSuccess {
                Log.d(TAG, it.templates.toString())
            }.onFailure {
                Log.d(TAG, "error : ${it.message.toString()}")
            }
        }
    }

    override fun onPauseSession(sessionId: String) {
        // Recording paused
    }

    override fun onResumeSession(sessionId: String) {
        // Recording resumed
    }

    override fun onError(error: EkaScribeError) {
        // Handle error
    }
}

```

### Step 2: Implement TokenStorage

The networking module requires a `TokenStorage` implementation to handle authentication tokens.

```kotlin
import com.eka.networking.token.TokenStorage

class MyTokenStorage : TokenStorage {
    override fun getAccessToken(): String {
        return "access_token"
    }

    override fun getRefreshToken(): String {
        return "refresh_token"
    }

    override fun saveTokens(accessToken: String, refreshToken: String) {
        // save tokens
    }

    override fun onSessionExpired() {
        // SessionExpired refresh token expired
    }
}
```

### Step 3: Configure and Initialize

Initialize `Voice2Rx` with `Voice2RxInitConfig` and `NetworkConfig`.

```kotlin
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx
import com.eka.voice2rx_sdk.sdkinit.Voice2RxInitConfig
import com.eka.networking.client.NetworkConfig
import com.eka.networking.client.AuthConfig

// 1. Configure Network
val networkConfig = NetworkConfig(
    appId = "your-app-id",
    baseUrl = "<https://api.eka.care/>",
    appVersionName = BuildConfig.VERSION_NAME,
    appVersionCode = BuildConfig.VERSION_CODE,
    isDebugApp = false // false in production environment,
    apiCallTimeOutInSec = 30L,
    headers = mapOf(), // Optional headers
    tokenStorage = MyTokenStorage()
)

// 2. Configure SDK
val config = Voice2RxInitConfig(
    voice2RxLifecycle = MyVoice2RxCallbacks(),
    networkConfig = networkConfig,
)

// 3. Initialize
Voice2Rx.init(config, context)

```

---

## 3. Core API Reference

All main functions are available via the `Voice2Rx` singleton object.

### Recording Control

### `startVoice2Rx`

Starts a new voice recording session.

```kotlin
fun startVoice2Rx(
    mode: String = Voice2RxType.CONSULTATION.value, // "consultation" or "dictation"
    patientDetails: PatientDetails? = null,
    outputFormats: List<Template>, // Max 2 formats
    languages: List<String>,       // Max 2 languages
    modelType: String,             // e.g., "pro"
    onError: (EkaScribeError) -> Unit,
    onStart: (String) -> Unit
)

```

**Example:**

```kotlin
// Define output templates using Template(templateId, templateName)
val outputFormats = listOf(
    Template(templateId = "19288d2f-81a9-46a6-b804-9651242a9b3e", templateName = "SOAP Note")
)

val languages = listOf("en-IN")

Voice2Rx.startVoice2Rx(
    mode = Voice2RxType.DICTATION.value,
    outputFormats = outputFormats,
    languages = languages,
    modelType = "pro" // or lite for faster results,
    onError = { error -> Log.e("SDK", "Error: ${error.errorDetails.message}") },
    onStart = { sessionId -> Log.d("SDK", "Started: $sessionId") }
)

```

### `pauseVoice2Rx`

Pauses the current recording.

```kotlin
Voice2Rx.pauseVoice2Rx()

```

### `resumeVoice2Rx`

Resumes a paused recording.

```kotlin
Voice2Rx.resumeVoice2Rx()

```

### `stopVoice2Rx`

Stops the recording and triggers processing.

```kotlin
Voice2Rx.stopVoice2Rx()

```

### `isCurrentlyRecording`

Checks if a recording is in progress.

```kotlin
val isRecording = Voice2Rx.isCurrentlyRecording()

```

### Data & Results

### `getSessionOutput`

Retrieves the transcription/generated output for a session.

```kotlin
suspend fun getSessionOutput(sessionId: String): Result<SessionResult>

```

### `pollEkaScribeResult`

Polls for results, useful for waiting until processing completes.

```kotlin
suspend fun pollEkaScribeResult(sessionId: String): Result<SessionResult>

```

### `getHistoryVoice2Rx`

Fetches session history.

```kotlin
suspend fun getHistoryVoice2Rx(count: Int? = null): Voice2RxHistoryResponse?

```

### `getFullRecordingFile`

Gets the audio file for a session.

```kotlin
fun getFullRecordingFile(sessionId: String): Result<File>

```

### Session Info & Monitoring

### `getSessionUploadInfoAsFlow`

Observe the upload stage of a session (Uploading, Processing, Completed).

```kotlin
suspend fun getSessionUploadInfoAsFlow(sessionId: String): Flow<VoiceTransactionStage>?

```

### `getVoiceActivityFlow`

Flow for real-time voice activity (speech detection, amplitude).

```kotlin
fun getVoiceActivityFlow(): Flow<VoiceActivityData>?

```

### `getAudioQualityFlow`

Flow for real-time audio quality metrics (STOI, PESQ, SI-SDR).

```kotlin
fun getAudioQualityFlow(): Flow<AudioQualityMetrics?>?

```

### Configuration & Templates

### `getTemplates`

Get available output templates.

```kotlin
suspend fun getTemplates(): Result<List<TemplateItem>>?

```

### `getUserConfigs`

Get user configuration including supported languages and modes.

```kotlin
suspend fun getUserConfigs(): Result<UserConfigs>

```

### `updateUserConfigs`

Update user preferences (selected language, mode, etc.).

```kotlin
suspend fun updateUserConfigs(selectedUserPreferences: SelectedUserPreferences): Result<Boolean>

```

---

## 4. Error Handling

Errors are returned via `EkaScribeError`.

**Common Error Codes:**

- `SUPPORTED_OUTPUT_FORMATS_COUNT_EXCEEDED`: Max 2 output formats allowed.
- `SUPPORTED_LANGUAGES_COUNT_EXCEEDED`: Max 2 languages allowed.
- `LANGUAGE_LIST_CAN_NOT_BE_EMPTY`
- `OUTPUT_FORMAT_LIST_CAN_NOT_BE_EMPTY`

**Data Class:**

```kotlin
data class EkaScribeError(
    val sessionId: String,
    val errorDetails: EkaScribeErrorDetails, // Contains 'message', 'displayMessage', 'code'
    val voiceError: VoiceError
)

```

## 5. Cleanup

Call `releaseResources()` when the SDK is no longer needed (e.g., in `onDestroy` of your host
Activity/Application).

```kotlin
Voice2Rx.releaseResources()

```