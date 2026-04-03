# EkaScribe Android SDK

Voice-powered medical transcription and documentation for Android apps.

EkaScribe records audio, transcribes it in real-time, and generates structured clinical documents
(SOAP notes, discharge summaries, etc.) with support for multiple languages and output templates.

**Min SDK:** 24 (Android 7.0) | **Compile SDK:** 36 | **Java:** 17

### Features

- Real-time voice recording with chunked upload
- Multi-language transcription (up to 2 languages per session)
- Multiple output templates (SOAP, custom formats)
- Real-time voice activity detection and audio quality analysis
- Session retry and idempotent error recovery
- Full Kotlin and Java support

---

## Table of Contents

- [Installation](#installation)
- [Quick Start](#quick-start)
    - [1. Implement TokenStorage](#1-implement-tokenstorage)
    - [2. Implement EkaScribeCallback](#2-implement-ekascribecallback)
    - [3. Initialize the SDK](#3-initialize-the-sdk)
    - [4. Start a Session](#4-start-a-session)
- [Recording Controls](#recording-controls)
- [Observing Session State](#observing-session-state)
- [Getting Results](#getting-results)
- [Session Configuration](#session-configuration)
- [Error Handling](#error-handling)
- [Java Interop Guide](#java-interop-guide)
- [Sample Apps](#sample-apps)
- [Cleanup](#cleanup)

---

## Installation

### 1. Add Maven Repository

In your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add Dependency

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.eka-care:Eka-Scribe-Android:${LATEST_VERSION}")
}
```

### 3. Add Permissions

In your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

> **Note:** `RECORD_AUDIO` is a dangerous permission — you must request it
> at runtime before starting a session.

---

## Quick Start

### 1. Implement TokenStorage

Provide your authentication tokens to the SDK.

**Kotlin**

```kotlin
import com.eka.networking.token.TokenStorage

class MyTokenStorage : TokenStorage {
    override fun getAccessToken(): String = "your_access_token"
    override fun getRefreshToken(): String = "your_refresh_token"
    override fun saveTokens(
        accessToken: String,
        refreshToken: String
    ) { /* persist tokens */ }
    override fun onSessionExpired() { /* redirect to login */ }
}
```

**Java**

```java
import com.eka.networking.token.TokenStorage;

public class MyTokenStorage implements TokenStorage {
    @Override
    public String getAccessToken() {
        return "your_access_token";
    }

    @Override
    public String getRefreshToken() {
        return "your_refresh_token";
    }

    @Override
    public void saveTokens(
        String accessToken, String refreshToken
    ) { /* persist */ }

    @Override
    public void onSessionExpired() { /* handle expiry */ }
}
```

---

### 2. Implement EkaScribeCallback

Receive session lifecycle events.

**Kotlin**

```kotlin
import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionResult

class MyScribeCallback : EkaScribeCallback {
    override fun onSessionStarted(sessionId: String) {}
    override fun onSessionPaused(sessionId: String) {}
    override fun onSessionResumed(sessionId: String) {}
    override fun onSessionStopped(
        sessionId: String, chunkCount: Int
    ) { /* upload complete */ }
    override fun onError(error: ScribeError) {}

    // Optional overrides
    override fun onSessionCompleted(
        sessionId: String, result: SessionResult
    ) {
        result.templates.forEach { template ->
            println("${template.title}: ${template.sections}")
        }
    }

    override fun onSessionFailed(
        sessionId: String, error: ScribeError
    ) {}
    override fun onSessionCancelled(sessionId: String) {}
}
```

**Java**

```java
import com.eka.scribesdk.api.EkaScribeCallback;
import com.eka.scribesdk.api.models.ScribeError;
import com.eka.scribesdk.api.models.SessionResult;

public class MyScribeCallback implements EkaScribeCallback {
    @Override
    public void onSessionStarted(String sid) {}

    @Override
    public void onSessionPaused(String sid) {}

    @Override
    public void onSessionResumed(String sid) {}

    @Override
    public void onSessionStopped(String sid, int chunks) {}

    @Override
    public void onError(ScribeError error) {}

    @Override
    public void onSessionCompleted(
        String sid, SessionResult result
    ) {
        for (TemplateOutput t : result.getTemplates()) {
            Log.d("Scribe", t.getTitle());
        }
    }

    @Override
    public void onSessionFailed(
        String sid, ScribeError error
    ) {}

    @Override
    public void onSessionCancelled(String sid) {}
}
```

---

### 3. Initialize the SDK

Initialize once (e.g., in `Application.onCreate()` or your main `Activity`).

**Kotlin**

```kotlin
import com.eka.networking.client.NetworkConfig
import com.eka.scribesdk.api.EkaScribe
import com.eka.scribesdk.api.EkaScribeConfig

val networkConfig = NetworkConfig(
    appId = "your-app-id",
    baseUrl = "https://api.eka.care/",
    appVersionName = BuildConfig.VERSION_NAME,
    appVersionCode = BuildConfig.VERSION_CODE,
    isDebugApp = BuildConfig.DEBUG,
    apiCallTimeOutInSec = 30L,
    headers = emptyMap(),
    tokenStorage = MyTokenStorage()
)

val config = EkaScribeConfig(
    clientId = "your-client-id",
    networkConfig = networkConfig,
    debugMode = BuildConfig.DEBUG
)

EkaScribe.init(config, applicationContext, MyScribeCallback())
```

**Java**

```java
import com.eka.networking.client.NetworkConfig;
import com.eka.scribesdk.api.EkaScribe;
import com.eka.scribesdk.api.EkaScribeConfig;

NetworkConfig netConfig = new NetworkConfig(
    "your-app-id",
    "https://api.eka.care/",
    "1.0.0",
    1,
    true,
    30L,
    new HashMap<>(),
    new MyTokenStorage()
);

EkaScribeConfig config = new EkaScribeConfig(
    "your-client-id",
    "android",
    true,
    true,
    netConfig
);

EkaScribe scribe = EkaScribe.INSTANCE;
scribe.init(config, this, new MyScribeCallback());
```

> **Java note:** `EkaScribe` is a Kotlin `object` — access it via
> `EkaScribe.INSTANCE` from Java. The SDK automatically injects
> `clientId` and `flavour` as `client-id` and `flavour` headers
> into all API requests.

---

### 4. Start a Session

**Kotlin**

```kotlin
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.OutputTemplate

val sessionConfig = SessionConfig(
    languages = listOf("en-IN"),
    mode = "dictation",
    modelType = "pro",
    outputTemplates = listOf(
        OutputTemplate(
            templateId = "your-template-id",
            templateName = "SOAP Notes"
        )
    )
)

lifecycleScope.launch {
    EkaScribe.startSession(
        context = this@MainActivity,
        sessionConfig = sessionConfig,
        onStart = { sid ->
            Log.d("Scribe", "Started: $sid")
        },
        onError = { error ->
            Log.e("Scribe", "Error: ${error.message}")
        }
    )
}
```

**Java**

```java
import com.eka.scribesdk.api.models.SessionConfig;
import com.eka.scribesdk.api.models.OutputTemplate;

SessionConfig sessionConfig = new SessionConfig(
    Arrays.asList("en-IN"),
    "dictation",
    "pro",
    Arrays.asList(new OutputTemplate(
        "your-template-id", "custom", "SOAP Notes"
    )),
    null,
    null,
    null
);

CoroutineScope scope =
    LifecycleOwnerKt.getLifecycleScope(this);

CoroutineHelper.startSession(
    scope,
    this,
    sessionConfig,
    sid -> {
        Log.d("Scribe", "Started: " + sid);
        return Unit.INSTANCE;
    },
    error -> {
        Log.e("Scribe", error.getMessage());
        return Unit.INSTANCE;
    }
);
```

---

## Recording Controls

Once a session is started, control recording with these methods.

**Kotlin**

```kotlin
EkaScribe.pauseSession()
EkaScribe.resumeSession()
EkaScribe.stopSession()
EkaScribe.cancelSession()

val isActive = EkaScribe.isRecording()
```

**Java**

```java
EkaScribe scribe = EkaScribe.INSTANCE;
scribe.pauseSession();
scribe.resumeSession();
scribe.stopSession();
scribe.cancelSession();

boolean isActive = scribe.isRecording();
```

---

## Observing Session State

### Session State Flow

Monitor session state transitions:
`IDLE -> STARTING -> RECORDING -> PAUSED -> STOPPING -> PROCESSING -> COMPLETED`.

**Kotlin**

```kotlin
lifecycleScope.launch {
    EkaScribe.getSessionState().collect { state ->
        // IDLE, STARTING, RECORDING, PAUSED,
        // STOPPING, PROCESSING, COMPLETED, ERROR
    }
}
```

**Java** (via CoroutineHelper)

```java
CoroutineHelper.collectSessionState(
    scope,
    state -> {
        runOnUiThread(() -> {
            // Update UI based on state
        });
        return Unit.INSTANCE;
    }
);
```

### Voice Activity Detection

Get real-time speech detection and amplitude data during recording.

**Kotlin**

```kotlin
lifecycleScope.launch {
    EkaScribe.getVoiceActivity().collect { data ->
        val status = if (data.isSpeech) "Speaking"
                     else "Silent"
        Log.d("Scribe", "$status: ${data.amplitude}")
    }
}
```

**Java** (via CoroutineHelper)

```java
CoroutineHelper.collectVoiceActivity(
    scope,
    data -> {
        String status = data.isSpeech()
            ? "Speaking" : "Silent";
        Log.d("Scribe", status);
        return Unit.INSTANCE;
    }
);
```

---

## Getting Results

### Via Callback (recommended)

Override `onSessionCompleted` in your `EkaScribeCallback`:

```kotlin
override fun onSessionCompleted(
    sessionId: String, result: SessionResult
) {
    for (template in result.templates) {
        println("Template: ${template.title}")
        for (section in template.sections) {
            println("  ${section.title}: ${section.value}")
        }
    }
}
```

### Via Polling (manual)

If you need to fetch results on demand:

**Kotlin**

```kotlin
lifecycleScope.launch {
    EkaScribe.pollSessionResult(sessionId)
        .onSuccess { result ->
            // Process SessionResult
        }
        .onFailure { error ->
            Log.e("Scribe", error.message)
        }
}
```

**Java**

```java
// Use CoroutineHelper bridge for suspend functions
```

### SessionResult Structure

```
SessionResult
+-- templates: List<TemplateOutput>
|   +-- title: String?
|   +-- name: String?
|   +-- type: TemplateType (MARKDOWN, JSON, EKA_EMR)
|   +-- rawOutput: String?
|   +-- sections: List<SectionData>
|   |   +-- title: String?
|   |   +-- value: String?
|   +-- isEditable: Boolean
+-- audioQuality: Double?
```

---

## Session Configuration

### SessionConfig

| Parameter | Type | Default | Description |
|---|---|---|---|
| `languages` | `List<String>` | `["en-IN"]` | Input languages (max 2) |
| `mode` | `String` | `"dictation"` | `"dictation"` or `"consultation"` |
| `modelType` | `String` | `"pro"` | `"pro"` (accurate) or `"lite"` (faster) |
| `outputTemplates` | `List<OutputTemplate>?` | `null` | Output format templates |
| `patientDetails` | `PatientDetail?` | `null` | Optional patient context |
| `section` | `String?` | `null` | Medical section filter |
| `speciality` | `String?` | `null` | Medical speciality filter |

### OutputTemplate

| Parameter | Type | Default | Description |
|---|---|---|---|
| `templateId` | `String` | -- | Template identifier |
| `templateType` | `String` | `"custom"` | Template type |
| `templateName` | `String?` | -- | Display name |

### PatientDetail

| Parameter | Type | Description |
|---|---|---|
| `age` | `Int?` | Patient age |
| `biologicalSex` | `String?` | `"M"` or `"F"` |
| `name` | `String?` | Patient name |
| `patientId` | `String?` | External patient ID |
| `visitId` | `String?` | Visit/encounter ID |

### EkaScribeConfig

| Parameter | Type | Default | Description |
|---|---|---|---|
| `clientId` | `String` | -- | **Required.** Client identifier |
| `flavour` | `String` | `"android"` | SDK flavour (sent as header) |
| `enableAnalyser` | `Boolean` | `true` | Enable audio quality analysis |
| `debugMode` | `Boolean` | `false` | Enable detailed logging |
| `networkConfig` | `NetworkConfig` | -- | **Required.** Network config |

> Audio recording parameters (sample rate, chunk durations, retries,
> etc.) are managed internally by the SDK with optimized defaults.

---

## Error Handling

Errors are delivered via `EkaScribeCallback.onError()` and
`onSessionFailed()` as `ScribeError`:

```kotlin
data class ScribeError(
    val code: ErrorCode,
    val message: String,
    val isRecoverable: Boolean = false
)
```

### Error Codes

| Code | Description |
|---|---|
| `MIC_PERMISSION_DENIED` | Microphone permission not granted |
| `SESSION_ALREADY_ACTIVE` | A session is already running |
| `INVALID_CONFIG` | SDK not initialized or bad config |
| `ENCODER_FAILED` | Audio encoding failure |
| `UPLOAD_FAILED` | Chunk upload to server failed |
| `MODEL_LOAD_FAILED` | Audio analysis model failed to load |
| `NETWORK_UNAVAILABLE` | No network connectivity |
| `DB_ERROR` | Local database error |
| `INVALID_STATE_TRANSITION` | Invalid state change attempted |
| `INIT_TRANSACTION_FAILED` | Server session init failed |
| `STOP_TRANSACTION_FAILED` | Server session stop failed |
| `COMMIT_TRANSACTION_FAILED` | Server commit failed |
| `POLL_TIMEOUT` | Result polling timed out |
| `TRANSCRIPTION_FAILED` | Server-side transcription failed |
| `RETRY_EXHAUSTED` | All retry attempts exhausted |
| `UNKNOWN` | Unexpected error |

### Retrying Failed Sessions

```kotlin
val result = EkaScribe.retrySession(
    sessionId, forceCommit = false
)
```

---

## Java Interop Guide

EkaScribe is written in Kotlin. Java apps need to handle
three differences:

### 1. Singleton Access

`EkaScribe` is a Kotlin `object`. From Java, access it via
`EkaScribe.INSTANCE`:

```java
EkaScribe scribe = EkaScribe.INSTANCE;
scribe.init(config, context, callback);
scribe.pauseSession();
scribe.stopSession();
```

### 2. No Default Parameters

Kotlin data classes with default parameter values require
**all parameters** when called from Java. `EkaScribeConfig`
has 5 parameters (2 mandatory + 3 with defaults). See the
[Initialize the SDK](#3-initialize-the-sdk) section for the
full constructor call.

### 3. Suspend Functions & Flows (CoroutineHelper)

Kotlin `suspend` functions (`startSession`, `pollSessionResult`,
etc.) and `Flow` collection cannot be called directly from Java.
You need a small Kotlin bridge file.

Add this single Kotlin file to your Java project:

```kotlin
// bridge/CoroutineHelper.kt
object CoroutineHelper {

    @JvmStatic
    fun startSession(
        scope: CoroutineScope,
        context: Context,
        config: SessionConfig,
        onStart: (String) -> Unit,
        onError: (ScribeError) -> Unit
    ): Job = scope.launch(Dispatchers.Main) {
        EkaScribe.startSession(
            context, config, onStart, onError
        )
    }

    @JvmStatic
    fun collectSessionState(
        scope: CoroutineScope,
        callback: (SessionState) -> Unit
    ): Job = scope.launch(Dispatchers.Main) {
        EkaScribe.getSessionState()
            .collect { callback(it) }
    }

    @JvmStatic
    fun collectVoiceActivity(
        scope: CoroutineScope,
        callback: (VoiceActivityData) -> Unit
    ): Job = scope.launch(Dispatchers.Main) {
        EkaScribe.getVoiceActivity()
            .collect { callback(it) }
    }
}
```

> For a Java project, add `apply plugin: 'kotlin-android'`
> to your `build.gradle` to compile this single Kotlin file.
> See the [sample-java](sample-java/) module for a complete
> working example.

---

## Sample Apps

| Module | Language | Description |
|---|---|---|
| [`sample-java/`](sample-java/) | Java + Kotlin bridge | Full integration example |

---

## Cleanup

Call `destroy()` when the SDK is no longer needed
(typically in `onDestroy`):

**Kotlin**

```kotlin
override fun onDestroy() {
    super.onDestroy()
    EkaScribe.destroy()
}
```

**Java**

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    EkaScribe.INSTANCE.destroy();
}
```
