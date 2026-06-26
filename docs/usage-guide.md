# EkaScribe Android SDK — Usage Guide

Complete integration reference with Kotlin and Java code examples for every public API.

For architecture and concepts see [getting-started.md](getting-started.md).
For environment setup and running sample apps see [setup.md](setup.md).

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Installation](#2-installation)
3. [Manifest Permissions](#3-manifest-permissions)
4. [Step-by-Step Integration](#4-step-by-step-integration)
   - [4.1 Implement TokenStorage](#41-implement-tokenstorage)
   - [4.2 Implement EkaScribeCallback](#42-implement-ekascribecallback)
   - [4.3 Initialize the SDK](#43-initialize-the-sdk)
   - [4.4 Start a Session](#44-start-a-session)
5. [Session Configuration Reference](#5-session-configuration-reference)
6. [Recording Controls](#6-recording-controls)
7. [Observing Session State](#7-observing-session-state)
8. [Voice Activity Detection](#8-voice-activity-detection)
9. [Audio Quality Metrics](#9-audio-quality-metrics)
10. [Getting Results](#10-getting-results)
11. [Template Management](#11-template-management)
12. [User Configuration Management](#12-user-configuration-management)
13. [Session History](#13-session-history)
14. [Pre-Recorded Audio File Processing](#14-pre-recorded-audio-file-processing)
15. [Session Persistence and Upload Progress](#15-session-persistence-and-upload-progress)
16. [Retrying Failed Sessions](#16-retrying-failed-sessions)
17. [SDK Lifecycle](#17-sdk-lifecycle)
18. [Error Handling](#18-error-handling)
19. [Java Interop Guide](#19-java-interop-guide)
20. [Test App References](#20-test-app-references)

---

## 1. Prerequisites

- **Android minSdk:** 24 (Android 7.0)
- **Android compileSdk:** 36
- **JDK:** 17
- **Kotlin coroutines** (`kotlinx-coroutines-android`) in the host project — required for `startSession` and Flow collection in Kotlin
- **Eka Care credentials:** a `clientId` and auth tokens obtained from the Eka Care developer portal
- **Java projects only:** add `apply plugin: 'kotlin-android'` (or `id("org.jetbrains.kotlin.android")`) to your module's `build.gradle` — required to compile the `CoroutineHelper.kt` bridge file (see [§ 19](#19-java-interop-guide))

---

## 2. Installation

### Step 1 — Add JitPack to `settings.gradle.kts`

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

### Step 2 — Add the dependency in `app/build.gradle.kts`

```kotlin
dependencies {
    implementation("com.github.eka-care:Eka-Scribe-Android:4.2.4")
}
```

Replace `4.2.4` with the latest release tag from [GitHub releases](https://github.com/eka-care/Eka-Scribe-Android/releases).

---

## 3. Manifest Permissions

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

> `RECORD_AUDIO` is a **dangerous permission** — it must be requested at runtime before calling `startSession()`.
> The SDK checks internally and delivers `ScribeError(code = MIC_PERMISSION_DENIED)` via `onError()` if the permission is missing.

**Kotlin — request permission at runtime:**

```kotlin
private val requestPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else showPermissionDeniedMessage()
    }

private fun checkAndRequestMicPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
        startRecording()
    } else {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
```

**Java:**

```java
private final ActivityResultLauncher<String> requestPermissionLauncher =
    registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
        if (granted) startRecording();
        else showPermissionDeniedMessage();
    });

private void checkAndRequestMicPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
        startRecording();
    } else {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }
}
```

---

## 4. Step-by-Step Integration

### 4.1 Implement TokenStorage

`TokenStorage` (from the `ekaNetworkAndroid` dependency) provides auth tokens to every SDK network request.

**Import:** `com.eka.networking.token.TokenStorage`

| Method | When called |
|---|---|
| `getAccessToken()` | Before every API request |
| `getRefreshToken()` | When the access token expires and needs refreshing |
| `saveTokens(accessToken, refreshToken)` | After a successful token refresh |
| `onSessionExpired()` | When refresh also fails — redirect user to login |

**Kotlin:**

```kotlin
import com.eka.networking.token.TokenStorage

class MyTokenStorage : TokenStorage {
    private var accessToken = "your_access_token"
    private var refreshToken = "your_refresh_token"

    override fun getAccessToken(): String = accessToken
    override fun getRefreshToken(): String = refreshToken

    override fun saveTokens(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        // persist to SharedPreferences / encrypted storage
    }

    override fun onSessionExpired() {
        // clear local tokens and redirect user to login screen
    }
}
```

**Java:**

```java
import com.eka.networking.token.TokenStorage;

public class MyTokenStorage implements TokenStorage {
    private String accessToken = "your_access_token";
    private String refreshToken = "your_refresh_token";

    @Override
    public String getAccessToken() { return accessToken; }

    @Override
    public String getRefreshToken() { return refreshToken; }

    @Override
    public void saveTokens(String accessToken, String refreshToken) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        // persist to SharedPreferences
    }

    @Override
    public void onSessionExpired() {
        // redirect to login
    }
}
```

---

### 4.2 Implement EkaScribeCallback

`EkaScribeCallback` (at `com.eka.scribesdk.api.EkaScribeCallback`) delivers session lifecycle events to your app.

**Required methods** (no default implementation — must override):

| Method | When fired |
|---|---|
| `onSessionStarted(sessionId)` | Recording has begun; microphone is open |
| `onSessionPaused(sessionId)` | Recording paused |
| `onSessionResumed(sessionId)` | Recording resumed |
| `onSessionStopped(sessionId, chunkCount)` | Recording stopped; all chunks are in flight |
| `onError(error)` | A recoverable error occurred during recording |

**Optional methods** (Kotlin provides default no-op; Java must implement all):

| Method | When fired |
|---|---|
| `onTranscriptReady(sessionId, result)` | Transcript-only result is available |
| `onOutputReady(sessionId, result)` | Full template output is available |
| `onSessionCompleted(sessionId, result)` | Both transcript and output ready — primary result callback |
| `onSessionFailed(sessionId, error)` | Unrecoverable error after session stopped |
| `onSessionCancelled(sessionId)` | Session was cancelled via `cancelSession()` |
| `onAudioFocusChanged(hasFocus)` | Device audio focus changed (e.g. incoming call) |
| `onSessionEvent(event)` | Fine-grained event for logging/analytics (see below) |

**Kotlin:**

```kotlin
import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionEvent
import com.eka.scribesdk.api.models.SessionResult

class MyScribeCallback : EkaScribeCallback {

    override fun onSessionStarted(sessionId: String) {
        Log.d("Scribe", "Session started: $sessionId")
    }

    override fun onSessionPaused(sessionId: String) {
        Log.d("Scribe", "Paused: $sessionId")
    }

    override fun onSessionResumed(sessionId: String) {
        Log.d("Scribe", "Resumed: $sessionId")
    }

    override fun onSessionStopped(sessionId: String, chunkCount: Int) {
        Log.d("Scribe", "Stopped: $sessionId, chunks=$chunkCount")
    }

    override fun onError(error: ScribeError) {
        Log.e("Scribe", "Error [${error.code}]: ${error.message}")
    }

    // --- Optional ---

    override fun onSessionCompleted(sessionId: String, result: SessionResult) {
        result.templates.forEach { template ->
            Log.d("Scribe", "=== ${template.title} ===")
            template.sections.forEach { section ->
                Log.d("Scribe", "${section.title}: ${section.value}")
            }
        }
    }

    override fun onSessionFailed(sessionId: String, error: ScribeError) {
        Log.e("Scribe", "Failed [$sessionId]: ${error.code}")
    }

    override fun onSessionCancelled(sessionId: String) {
        Log.d("Scribe", "Cancelled: $sessionId")
    }

    override fun onAudioFocusChanged(hasFocus: Boolean) {
        Log.d("Scribe", "Audio focus: $hasFocus")
    }

    override fun onSessionEvent(event: SessionEvent) {
        Log.d("Scribe", "[${event.eventType}] ${event.eventName}: ${event.message}")
    }
}
```

**Java:**

```java
import com.eka.scribesdk.api.EkaScribeCallback;
import com.eka.scribesdk.api.models.ScribeError;
import com.eka.scribesdk.api.models.SessionEvent;
import com.eka.scribesdk.api.models.SessionResult;
import com.eka.scribesdk.api.models.TemplateOutput;

public class MyScribeCallback implements EkaScribeCallback {

    @Override
    public void onSessionStarted(String sessionId) {
        Log.d("Scribe", "Session started: " + sessionId);
    }

    @Override
    public void onSessionPaused(String sessionId) {
        Log.d("Scribe", "Paused: " + sessionId);
    }

    @Override
    public void onSessionResumed(String sessionId) {
        Log.d("Scribe", "Resumed: " + sessionId);
    }

    @Override
    public void onSessionStopped(String sessionId, int chunkCount) {
        Log.d("Scribe", "Stopped: " + sessionId + ", chunks=" + chunkCount);
    }

    @Override
    public void onError(ScribeError error) {
        Log.e("Scribe", "Error [" + error.getCode() + "]: " + error.getMessage());
    }

    @Override
    public void onSessionCompleted(String sessionId, SessionResult result) {
        for (TemplateOutput template : result.getTemplates()) {
            Log.d("Scribe", "=== " + template.getTitle() + " ===");
        }
    }

    @Override
    public void onSessionFailed(String sessionId, ScribeError error) {
        Log.e("Scribe", "Failed [" + sessionId + "]: " + error.getCode());
    }

    @Override
    public void onSessionCancelled(String sessionId) {
        Log.d("Scribe", "Cancelled: " + sessionId);
    }

    @Override
    public void onAudioFocusChanged(boolean hasFocus) {
        Log.d("Scribe", "Audio focus: " + hasFocus);
    }

    @Override
    public void onSessionEvent(SessionEvent event) {
        Log.d("Scribe", "[" + event.getEventType() + "] " + event.getEventName());
    }
}
```

> **`onSessionEvent` for analytics:** `SessionEvent` carries an `eventName` (e.g. `RECORDING_STARTED`, `CHUNK_UPLOADED`, `COMMIT_SUCCEEDED`, `POLL_RESULT_RECEIVED`) and `eventType` (`SUCCESS`, `ERROR`, `INFO`). Use this for detailed telemetry without adding any logging code inside the SDK.

---

### 4.3 Initialize the SDK

Call `EkaScribe.init()` **once**, before any other SDK call. Best placed in `Application.onCreate()`.

**Kotlin:**

```kotlin
import com.eka.networking.client.NetworkConfig
import com.eka.scribesdk.api.EkaScribe
import com.eka.scribesdk.api.EkaScribeConfig

val networkConfig = NetworkConfig(
    appId          = "your-app-id",
    baseUrl        = "https://api.eka.care/",
    appVersionName = BuildConfig.VERSION_NAME,
    appVersionCode = BuildConfig.VERSION_CODE,
    isDebugApp     = BuildConfig.DEBUG,
    apiCallTimeOutInSec = 30L,
    headers        = emptyMap(),
    tokenStorage   = MyTokenStorage()
)

val config = EkaScribeConfig(
    clientId      = "your-client-id",   // Required
    flavour       = "android",          // Optional, default "android"
    enableAnalyser = true,              // Optional, default true
    debugMode     = BuildConfig.DEBUG,  // Optional, default false
    networkConfig = networkConfig       // Required
)

EkaScribe.init(config, applicationContext, MyScribeCallback())
```

**Java:**

> `EkaScribe` is a Kotlin `object` — access it via `EkaScribe.INSTANCE` from Java.
> All constructor parameters must be provided explicitly — Java cannot use Kotlin default values.

```java
import com.eka.networking.client.NetworkConfig;
import com.eka.scribesdk.api.EkaScribe;
import com.eka.scribesdk.api.EkaScribeConfig;

NetworkConfig networkConfig = new NetworkConfig(
    "your-app-id",           // appId
    "https://api.eka.care/", // baseUrl
    "1.0.0",                 // appVersionName
    1,                       // appVersionCode
    true,                    // isDebugApp
    30L,                     // apiCallTimeOutInSec
    new HashMap<>(),         // headers
    new MyTokenStorage()     // tokenStorage
);

EkaScribeConfig config = new EkaScribeConfig(
    "your-client-id", // clientId
    "android",        // flavour
    true,             // enableAnalyser
    true,             // debugMode
    networkConfig     // networkConfig
);

EkaScribe.INSTANCE.init(config, this, new MyScribeCallback());
```

> The SDK automatically injects `clientId` and `flavour` as `client-id` and `flavour` HTTP headers into all API requests.

---

### 4.4 Start a Session

`startSession()` is a `suspend` function — call it from a coroutine.

**Kotlin:**

```kotlin
import com.eka.scribesdk.api.EkaScribe
import com.eka.scribesdk.api.models.OutputTemplate
import com.eka.scribesdk.api.models.PatientDetail
import com.eka.scribesdk.api.models.SessionConfig

lifecycleScope.launch {
    EkaScribe.startSession(
        context = this@MainActivity,
        sessionConfig = SessionConfig(
            languages       = listOf("en-IN"),
            mode            = "consultation",
            modelType       = "pro",
            outputTemplates = listOf(
                OutputTemplate(
                    templateId   = "your-template-id",
                    templateName = "SOAP Notes"
                )
            ),
            patientDetails  = PatientDetail(
                patientId      = "P001",
                visitId        = "V001",
                name           = "Jane Doe",
                age            = 35,
                biologicalSex  = "F"
            )
        ),
        onStart = { sessionId ->
            Log.d("Scribe", "Session ID: $sessionId")
        },
        onError = { error ->
            Log.e("Scribe", "Start failed: ${error.message}")
        }
    )
}
```

**Java** (via `CoroutineHelper` bridge — see [§ 19](#19-java-interop-guide)):

```java
import androidx.lifecycle.LifecycleOwnerKt;
import com.eka.scribesdk.api.models.OutputTemplate;
import com.eka.scribesdk.api.models.PatientDetail;
import com.eka.scribesdk.api.models.SessionConfig;
import com.eka.voice2rx.javasample.bridge.CoroutineHelper;
import kotlin.Unit;
import java.util.Arrays;

SessionConfig sessionConfig = new SessionConfig(
    Arrays.asList("en-IN"),                             // languages
    "consultation",                                      // mode
    "pro",                                               // modelType
    Arrays.asList(new OutputTemplate(
        "your-template-id", "custom", "SOAP Notes"
    )),                                                  // outputTemplates
    new PatientDetail(35, "F", "Jane Doe", "P001", "V001"), // patientDetails
    null,                                                // section
    null                                                 // speciality
);

CoroutineHelper.startSession(
    LifecycleOwnerKt.getLifecycleScope(this),
    this,
    sessionConfig,
    sessionId -> {
        Log.d("Scribe", "Session ID: " + sessionId);
        return Unit.INSTANCE;   // Java lambdas returning (T) -> Unit must return Unit.INSTANCE
    },
    error -> {
        Log.e("Scribe", "Start failed: " + error.getMessage());
        return Unit.INSTANCE;
    }
);
```

---

## 5. Session Configuration Reference

### SessionConfig

| Field | Type | Default | Description |
|---|---|---|---|
| `languages` | `List<String>` | `["en-IN"]` | BCP-47 language codes, max 2 (e.g. `"en-IN"`, `"hi"`, `"ta"`) |
| `mode` | `String` | `"dictation"` | `"dictation"` (single speaker) or `"consultation"` (multi-speaker) |
| `modelType` | `String` | `"pro"` | `"pro"` (high accuracy) or `"lite"` (faster) |
| `outputTemplates` | `List<OutputTemplate>?` | `null` | Templates to generate; `null` produces transcript only |
| `patientDetails` | `PatientDetail?` | `null` | Optional patient context for the AI model |
| `section` | `String?` | `null` | Medical section filter |
| `speciality` | `String?` | `null` | Clinical speciality filter |

### OutputTemplate

| Field | Type | Default | Description |
|---|---|---|---|
| `templateId` | `String` | — | Template identifier (from `getTemplates()`) |
| `templateType` | `String` | `"custom"` | Template type |
| `templateName` | `String?` | — | Display name |

### PatientDetail

| Field | Type | Description |
|---|---|---|
| `age` | `Int?` | Patient age |
| `biologicalSex` | `String?` | `"M"` or `"F"` |
| `name` | `String?` | Patient name |
| `patientId` | `String?` | External patient ID |
| `visitId` | `String?` | Visit / encounter ID |

### EkaScribeConfig

| Field | Type | Default | Description |
|---|---|---|---|
| `clientId` | `String` | — | **Required.** Client identifier (injected as `client-id` header) |
| `flavour` | `String` | `"android"` | SDK flavour (injected as `flavour` header) |
| `enableAnalyser` | `Boolean` | `true` | Enable SQUIM audio quality analysis |
| `debugMode` | `Boolean` | `false` | Enable detailed SDK logging |
| `networkConfig` | `NetworkConfig` | — | **Required.** Network and auth configuration |

### NetworkConfig

| Field | Type | Description |
|---|---|---|
| `appId` | `String` | Application identifier |
| `baseUrl` | `String` | API base URL (e.g. `"https://api.eka.care/"`) |
| `appVersionName` | `String` | App version name |
| `appVersionCode` | `Int` | App version code |
| `isDebugApp` | `Boolean` | Whether this is a debug build |
| `apiCallTimeOutInSec` | `Long` | HTTP timeout in seconds (default `30`) |
| `headers` | `Map<String, String>` | Additional headers for every request |
| `tokenStorage` | `TokenStorage` | Auth token provider |

---

## 6. Recording Controls

All controls are synchronous (non-suspend). They are valid only after `init()` has been called.

| Method | Valid in state | Description |
|---|---|---|
| `pauseSession()` | `RECORDING` | Pause microphone; in-progress chunk flushed |
| `resumeSession()` | `PAUSED` | Resume microphone |
| `stopSession()` | `RECORDING`, `PAUSED` | Stop and finalise; upload continues in background |
| `cancelSession()` | `RECORDING`, `PAUSED` | Discard session; no result delivered |
| `isRecording()` | any | `true` only when state is `RECORDING` |

**Kotlin:**

```kotlin
EkaScribe.pauseSession()
EkaScribe.resumeSession()
EkaScribe.stopSession()
EkaScribe.cancelSession()

val active: Boolean = EkaScribe.isRecording()
```

**Java:**

```java
EkaScribe scribe = EkaScribe.INSTANCE;
scribe.pauseSession();
scribe.resumeSession();
scribe.stopSession();
scribe.cancelSession();

boolean active = scribe.isRecording();
```

---

## 7. Observing Session State

### Via Flow (recommended for UI state)

**Kotlin:**

```kotlin
lifecycleScope.launch {
    EkaScribe.getSessionState().collect { state ->
        when (state) {
            SessionState.IDLE       -> showStartButton()
            SessionState.STARTING   -> showLoadingIndicator()
            SessionState.RECORDING  -> showRecordingControls()
            SessionState.PAUSED     -> showResumeButton()
            SessionState.STOPPING   -> showStoppingIndicator()
            SessionState.PROCESSING -> showProcessingSpinner()
            SessionState.COMPLETED  -> showResult()
            SessionState.ERROR      -> showRetryButton()
        }
    }
}
```

**Java** (via `CoroutineHelper`):

```java
CoroutineHelper.collectSessionState(
    LifecycleOwnerKt.getLifecycleScope(this),
    state -> {
        runOnUiThread(() -> updateUI(state));
        return Unit.INSTANCE;
    }
);
```

### Via EkaScribeCallback events

| Callback | Corresponding state |
|---|---|
| `onSessionStarted` | `RECORDING` |
| `onSessionPaused` | `PAUSED` |
| `onSessionResumed` | `RECORDING` |
| `onSessionStopped` | `STOPPING` → `PROCESSING` |
| `onSessionCompleted` | `COMPLETED` |
| `onSessionFailed` | `ERROR` |

### Fine-grained events via `onSessionEvent`

`onSessionEvent(event: SessionEvent)` fires for every internal lifecycle step. Useful for analytics or diagnostics without adding SDK-internal logging.

`SessionEvent` fields:
- `sessionId: String`
- `eventName: SessionEventName` — e.g. `RECORDING_STARTED`, `CHUNK_UPLOADED`, `COMMIT_SUCCEEDED`, `POLL_RESULT_RECEIVED`
- `eventType: EventType` — `SUCCESS`, `ERROR`, or `INFO`
- `message: String`
- `metadata: Map<String, String>`
- `timestampMs: Long`

---

## 8. Voice Activity Detection

`EkaScribe.getVoiceActivity(): Flow<VoiceActivityData>` emits on every 512-sample audio frame (every 32 ms) while the session is active.

**`VoiceActivityData` fields:**

| Field | Type | Description |
|---|---|---|
| `isSpeech` | `Boolean` | `true` when speech is detected in this frame |
| `amplitude` | `Float` | RMS amplitude of the frame |
| `timestampMs` | `Long` | Frame timestamp in milliseconds |

**Kotlin:**

```kotlin
lifecycleScope.launch {
    EkaScribe.getVoiceActivity().collect { data ->
        val label = if (data.isSpeech) "Speaking" else "Silent"
        updateWaveformUI(label, data.amplitude)
    }
}
```

**Java** (via `CoroutineHelper`):

```java
CoroutineHelper.collectVoiceActivity(
    LifecycleOwnerKt.getLifecycleScope(this),
    data -> {
        String label = data.isSpeech() ? "Speaking" : "Silent";
        runOnUiThread(() -> updateWaveformUI(label, data.getAmplitude()));
        return Unit.INSTANCE;
    }
);
```

---

## 9. Audio Quality Metrics

`EkaScribe.getAudioQuality(): Flow<AudioQualityMetrics>` emits approximately every 3 seconds of captured audio (driven by SQUIM model inference cadence).

**`AudioQualityMetrics` fields:**

| Field | Type | Range | Meaning |
|---|---|---|---|
| `stoi` | `Float` | 0.0 – 1.0 | Short-Time Objective Intelligibility (higher = clearer speech) |
| `pesq` | `Float` | -0.5 – 4.5 | Perceptual Evaluation of Speech Quality |
| `siSDR` | `Float` | dB | Scale-Invariant Signal-to-Distortion Ratio |
| `overallScore` | `Float` | — | Composite score |

**Monitor model readiness** via `EkaScribe.analyserStateFlow: StateFlow<AnalyserState>`:

| State | Meaning |
|---|---|
| `AnalyserState.Idle` | Model not yet downloading |
| `AnalyserState.Downloading` | CDN download in progress |
| `AnalyserState.Ready(modelPath)` | Model loaded; quality flow is active |
| `AnalyserState.Failed` | Model failed to load; quality flow will not emit |
| `AnalyserState.Disabled` | `enableAnalyser = false` in config |

**Kotlin:**

```kotlin
// Observe readiness
lifecycleScope.launch {
    EkaScribe.analyserStateFlow.collect { analyserState ->
        Log.d("Scribe", "Analyser: $analyserState")
    }
}

// Observe quality scores
lifecycleScope.launch {
    EkaScribe.getAudioQuality().collect { metrics ->
        Log.d("Scribe", "STOI: ${metrics.stoi}, PESQ: ${metrics.pesq}")
    }
}
```

> Requires `enableAnalyser = true` in `EkaScribeConfig` (the default). If disabled, `analyserStateFlow` emits `AnalyserState.Disabled` and `getAudioQuality()` never emits.

---

## 10. Getting Results

### 10.1 Via Callback (recommended for live sessions)

Override `onSessionCompleted` in your `EkaScribeCallback`. The SDK delivers the result automatically when transcription finishes.

```kotlin
override fun onSessionCompleted(sessionId: String, result: SessionResult) {
    result.templates.forEach { template ->
        println("=== ${template.title} (${template.type}) ===")
        template.sections.forEach { section ->
            println("${section.title}: ${section.value}")
        }
        // or access raw output for MARKDOWN type:
        template.rawOutput?.let { println(it) }
    }
    println("Audio quality: ${result.audioQuality}")
}
```

### 10.2 Via Single Fetch

Fetches whatever the server currently has — no polling, no retry.

**Kotlin:**

```kotlin
lifecycleScope.launch {
    EkaScribe.getSessionOutput(sessionId)
        .onSuccess { result -> processResult(result) }
        .onFailure { error -> Log.e("Scribe", error.message ?: "fetch failed") }

    // Transcript only:
    EkaScribe.getTranscriptOutput(sessionId)
        .onSuccess { result -> showTranscript(result) }
}
```

### 10.3 Via Polling

Polls with up to 3 retries (2 s delay between attempts). Use this when the app restarted and the SDK no longer has an active session for the given `sessionId`.

**Kotlin:**

```kotlin
lifecycleScope.launch {
    EkaScribe.pollSessionResult(sessionId)
        .onSuccess { result -> processResult(result) }
        .onFailure { Log.e("Scribe", "Poll failed") }

    // Transcript only:
    EkaScribe.pollTranscriptResult(sessionId)
        .onSuccess { result -> showTranscript(result) }
}
```

> The SDK already polls automatically after `stopSession()`. Use manual polling only for app-restart recovery scenarios.

### 10.4 SessionResult Structure

```
SessionResult
  audioQuality: Double?           -- overall audio quality (0.0 – 1.0), null if analyser disabled
  templates: List<TemplateOutput>
    TemplateOutput
      sessionId:  String
      documentId: String
      templateId: String?
      name:       String?
      title:      String?         -- human-readable name (e.g. "SOAP Note")
      type:       TemplateType    -- MARKDOWN | JSON | EKA_EMR
      rawOutput:  String?         -- full raw output (populated for MARKDOWN type)
      isEditable: Boolean
      sections:   List<SectionData>
        SectionData
          title:  String?         -- section heading (e.g. "Subjective")
          value:  String?         -- section content
```

**Java accessors:**

```java
for (TemplateOutput template : result.getTemplates()) {
    String title = template.getTitle();
    for (SectionData section : template.getSections()) {
        String heading = section.getTitle();
        String content = section.getValue();
    }
}
```

---

## 11. Template Management

Fetch available templates and manage user favorites.

**Kotlin:**

```kotlin
// Fetch all available templates
lifecycleScope.launch {
    EkaScribe.getTemplates()
        .onSuccess { templates ->
            templates.forEach { item ->
                Log.d("Scribe", "${item.id}: ${item.title}")
            }
        }
}

// Save favorite template IDs
lifecycleScope.launch {
    EkaScribe.updateTemplates(listOf("soap_note", "discharge_summary"))
}

// Re-process a session with a different template
lifecycleScope.launch {
    EkaScribe.convertTransactionResult(sessionId, newTemplateId)
        .onSuccess { Log.d("Scribe", "Converted successfully") }
}
```

**`TemplateItem` fields:** `id`, `title`, `desc`, `default`, `isFavorite`, `sectionIds`

---

## 12. User Configuration Management

Fetch and save the user's preferred recording defaults. Useful for a settings screen.

**Kotlin:**

```kotlin
lifecycleScope.launch {
    EkaScribe.getUserConfigs()
        .onSuccess { configs ->
            // configs.selectedUserPreferences holds current selections
            val currentMode = configs.selectedUserPreferences.consultationMode
            val currentLangs = configs.selectedUserPreferences.languages
        }
}

// Save updated preferences
lifecycleScope.launch {
    val prefs = SelectedUserPreferences(
        consultationMode = "consultation",
        languages        = listOf("en-IN"),
        outputTemplates  = listOf("soap_note"),
        modelType        = "pro"
    )
    EkaScribe.updateUserConfigs(prefs)
        .onSuccess { Log.d("Scribe", "Preferences saved") }
}
```

**`UserConfigs` structure:**

| Field | Type | Description |
|---|---|---|
| `consultationModes` | `ConsultationModeConfig` | Available modes and max selection |
| `supportedLanguages` | `SupportedLanguagesConfig` | Available language codes and max selection |
| `outputTemplates` | `OutputTemplatesConfig` | Available templates and max selection |
| `selectedUserPreferences` | `SelectedUserPreferences` | Current user selections |
| `modelConfigs` | `ModelConfigs` | Available model types |

---

## 13. Session History

**Server-side history** (richer metadata):

```kotlin
lifecycleScope.launch {
    val history: List<ScribeHistoryItem> = EkaScribe.getHistory(count = 20)
    history.forEach { item ->
        Log.d("Scribe", "${item.txnId} — ${item.processingStatus}")
    }
}
```

`ScribeHistoryItem` key fields: `txnId`, `uuid`, `createdAt`, `mode`, `flavour`, `processingStatus`, `userStatus`, `patientDetails`

**Local database** (faster, offline-capable):

```kotlin
lifecycleScope.launch {
    // All sessions
    val sessions: List<ScribeSession> = EkaScribe.getSessions()

    // Single session
    val session: ScribeSession? = EkaScribe.getSession(sessionId)
    Log.d("Scribe", "State: ${session?.state}, Chunks: ${session?.chunkCount}")
}
```

---

## 14. Pre-Recorded Audio File Processing

Process an existing audio file (e.g. a call recording) through the same transcription pipeline as a live session.

**Kotlin:**

```kotlin
lifecycleScope.launch {
    EkaScribe.processAudioFile(
        filePath      = "/storage/emulated/0/Recordings/call_2024.m4a",
        sessionConfig = SessionConfig(
            languages       = listOf("en-IN"),
            mode            = "consultation",
            outputTemplates = listOf(OutputTemplate("soap_note", "custom", "SOAP Notes"))
        ),
        onStart    = { sessionId -> Log.d("Scribe", "Processing: $sessionId") },
        onError    = { error -> Log.e("Scribe", error.message) },
        onComplete = { sessionId -> Log.d("Scribe", "Upload done: $sessionId") }
    )
}
```

> `onComplete` fires when all audio chunks have been uploaded. The transcription result is delivered later via `EkaScribeCallback.onSessionCompleted()`.

**Java** (add a helper to `CoroutineHelper.kt`):

```kotlin
// Add to CoroutineHelper.kt
@JvmStatic
fun processAudioFile(
    scope: CoroutineScope,
    filePath: String,
    sessionConfig: SessionConfig,
    onStart: (String) -> Unit,
    onError: (ScribeError) -> Unit,
    onComplete: (String) -> Unit
): Job = scope.launch(Dispatchers.Main) {
    EkaScribe.processAudioFile(filePath, sessionConfig, onStart, onError, onComplete)
}
```

```java
CoroutineHelper.processAudioFile(
    LifecycleOwnerKt.getLifecycleScope(this),
    "/storage/emulated/0/Recordings/call_2024.m4a",
    sessionConfig,
    sessionId -> { Log.d("Scribe", "Processing: " + sessionId); return Unit.INSTANCE; },
    error     -> { Log.e("Scribe", error.getMessage()); return Unit.INSTANCE; },
    sessionId -> { Log.d("Scribe", "Upload done: " + sessionId); return Unit.INSTANCE; }
);
```

---

## 15. Session Persistence and Upload Progress

Track the server-side upload progress of any session — including sessions that are retrying in the background.

**Kotlin:**

```kotlin
lifecycleScope.launch {
    EkaScribe.getUploadProgress(sessionId).collect { stage ->
        Log.d("Scribe", "Upload stage: $stage")
    }
}
```

**`UploadStage` values:**

| Stage | Meaning |
|---|---|
| `INIT` | `initTransaction` API call succeeded |
| `STOP` | `stopTransaction` API call succeeded |
| `COMMIT` | All chunks uploaded and committed |
| `ANALYZING` | Server is processing the audio |
| `COMPLETED` | Transcription result is ready |
| `FAILURE` | All retry attempts failed |
| `ERROR` | Unexpected error |

---

## 16. Retrying Failed Sessions

If a session enters the `ERROR` state, retry it without re-recording.

**Kotlin:**

```kotlin
lifecycleScope.launch {
    val result = EkaScribe.retrySession(
        sessionId   = "session-uuid-here",
        forceCommit = false   // set true to commit even if some chunks failed
    )
    Log.d("Scribe", "Retry result: $result")
}
```

**Java** (add to `CoroutineHelper.kt`):

```kotlin
// Add to CoroutineHelper.kt
@JvmStatic
fun retrySession(
    scope: CoroutineScope,
    sessionId: String,
    forceCommit: Boolean,
    onResult: (TransactionResult) -> Unit
): Job = scope.launch(Dispatchers.Main) {
    val result = EkaScribe.retrySession(sessionId, forceCommit)
    onResult(result)
}
```

```java
CoroutineHelper.retrySession(
    LifecycleOwnerKt.getLifecycleScope(this),
    sessionId,
    false,
    result -> { Log.d("Scribe", "Retry: " + result); return Unit.INSTANCE; }
);
```

> Use `forceCommit = true` when the network was flaky and some chunks failed to upload, but you want to proceed with whatever audio was successfully captured.

---

## 17. SDK Lifecycle

Call `destroy()` when the SDK is no longer needed to release the microphone, cancel all coroutines, and free memory.

**Kotlin:**

```kotlin
override fun onDestroy() {
    super.onDestroy()
    EkaScribe.destroy()
}
```

**Java:**

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    EkaScribe.INSTANCE.destroy();
}
```

After `destroy()`, calling any SDK method will throw `ScribeException(INVALID_CONFIG)`. You can call `init()` again to reinitialize.

---

## 18. Error Handling

Errors are delivered via `EkaScribeCallback.onError()` (during recording) and `onSessionFailed()` (after stop). Both receive a `ScribeError`:

```kotlin
data class ScribeError(
    val code: ErrorCode,
    val message: String,
    val isRecoverable: Boolean
)
```

### Error Code Reference

| Code | Description | Recoverable | Recommended action |
|---|---|---|---|
| `MIC_PERMISSION_DENIED` | `RECORD_AUDIO` permission not granted | Yes | Request permission before calling `startSession()` |
| `SESSION_ALREADY_ACTIVE` | Another session is already running | Yes | Call `stopSession()` or `cancelSession()` first |
| `INVALID_CONFIG` | SDK not initialized or called after `destroy()` | No | Call `EkaScribe.init()` before any other method |
| `ENCODER_FAILED` | MP3 encoding failure | No | Check device storage; report if persistent |
| `UPLOAD_FAILED` | S3 chunk upload failed after retries | Yes | Call `retrySession(sessionId)` |
| `MODEL_LOAD_FAILED` | Silero VAD or SQUIM model failed to load | No | VAD failure is fatal; SQUIM failure is non-fatal |
| `NETWORK_UNAVAILABLE` | No internet connectivity | Yes | Retry when connected; call `retrySession()` |
| `DB_ERROR` | Room database operation failed | No | Report as a bug |
| `INVALID_STATE_TRANSITION` | Illegal state change attempted | No | Check session state before calling controls |
| `INIT_TRANSACTION_FAILED` | Server rejected session init | Yes | Call `retrySession(sessionId)` |
| `STOP_TRANSACTION_FAILED` | Server rejected stop call | Yes | Call `retrySession(sessionId)` |
| `COMMIT_TRANSACTION_FAILED` | Server rejected commit | Yes | Call `retrySession(sessionId, forceCommit = true)` |
| `POLL_TIMEOUT` | Result polling timed out | Yes | Call `pollSessionResult(sessionId)` manually |
| `TRANSCRIPTION_FAILED` | Server-side transcription failed | No | Contact support with `sessionId` |
| `RETRY_EXHAUSTED` | Max retry attempts reached | No | Call `retrySession(sessionId, forceCommit = true)` |
| `UNKNOWN` | Unexpected error | Unknown | Log and report |

---

## 19. Java Interop Guide

`EkaScribe` is written in Kotlin. Java integration requires handling three differences.

### 19.1 Singleton Access

`EkaScribe` is a Kotlin `object`, which compiles to a Java class with a static `INSTANCE` field.

```java
// Correct
EkaScribe scribe = EkaScribe.INSTANCE;
scribe.init(config, context, callback);

// Wrong — EkaScribe has no public constructor
// EkaScribe scribe = new EkaScribe(); // compile error
```

### 19.2 No Default Parameters

Kotlin data classes with default values require **all parameters** to be passed from Java. There is no Java equivalent of Kotlin default arguments.

| Class | Total params | Required | Optional (have defaults in Kotlin) |
|---|---|---|---|
| `EkaScribeConfig` | 5 | 2 (`clientId`, `networkConfig`) | 3 (`flavour`, `enableAnalyser`, `debugMode`) |
| `NetworkConfig` | 8 | all 8 | none |
| `SessionConfig` | 7 | 0 (all have defaults) | 7 |
| `OutputTemplate` | 3 | 1 (`templateId`) | 2 (`templateType`, `templateName`) |

Pass all parameters explicitly from Java.

### 19.3 Suspend Functions and Flow Collection

Kotlin `suspend` functions compile to state machines that can suspend and resume at multiple points. Passing a plain Java lambda as a `Continuation` causes `"This continuation is already complete"` crashes because the lambda handles only one suspend/resume cycle.

**Solution:** A single Kotlin file (`CoroutineHelper.kt`) wraps all suspend calls and Flow collectors in properly structured coroutines.

**Copy this file into your project:**

```kotlin
// bridge/CoroutineHelper.kt
package com.example.yourapp.bridge

import android.content.Context
import com.eka.scribesdk.api.EkaScribe
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.api.models.VoiceActivityData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object CoroutineHelper {

    @JvmStatic
    fun startSession(
        scope: CoroutineScope,
        context: Context,
        sessionConfig: SessionConfig,
        onStart: (String) -> Unit,
        onError: (ScribeError) -> Unit
    ): Job = scope.launch(Dispatchers.Main) {
        EkaScribe.startSession(context, sessionConfig, onStart, onError)
    }

    @JvmStatic
    fun collectSessionState(
        scope: CoroutineScope,
        callback: (SessionState) -> Unit
    ): Job = scope.launch(Dispatchers.Main) {
        EkaScribe.getSessionState().collect { callback(it) }
    }

    @JvmStatic
    fun collectVoiceActivity(
        scope: CoroutineScope,
        callback: (VoiceActivityData) -> Unit
    ): Job = scope.launch(Dispatchers.Main) {
        EkaScribe.getVoiceActivity().collect { callback(it) }
    }
}
```

The complete version is at [`sample-java/.../bridge/CoroutineHelper.kt`](https://github.com/eka-care/Eka-Scribe-Android/blob/main/sample-java/src/main/java/com/eka/voice2rx/javasample/bridge/CoroutineHelper.kt).

**Extend for other suspend functions** (e.g. `pollSessionResult`):

```kotlin
@JvmStatic
fun pollSessionResult(
    scope: CoroutineScope,
    sessionId: String,
    onSuccess: (SessionResult) -> Unit,
    onFailure: (Throwable) -> Unit
): Job = scope.launch(Dispatchers.Main) {
    EkaScribe.pollSessionResult(sessionId)
        .onSuccess { onSuccess(it) }
        .onFailure { onFailure(it) }
}
```

**Getting a `CoroutineScope` in Java:**

```java
// Lifecycle-aware scope (auto-cancels on onDestroy)
CoroutineScope scope = LifecycleOwnerKt.getLifecycleScope(this);
```

**Lambda return type:**

Java lambdas passed to Kotlin `(T) -> Unit` parameters must explicitly return `Unit.INSTANCE`:

```java
sessionId -> {
    Log.d("Scribe", sessionId);
    return Unit.INSTANCE;   // required
}
```

**UI updates from Flow callbacks:**

Flow callbacks on `Dispatchers.Main` are already on the main thread, but wrap UI calls in `runOnUiThread` for safety when the dispatcher could vary:

```java
state -> {
    runOnUiThread(() -> updateUI(state));
    return Unit.INSTANCE;
}
```

**Build setup for a Java module with CoroutineHelper.kt:**

```kotlin
// In sample-java/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")  // required to compile CoroutineHelper.kt
}
```

---

## 20. Test App References

Working examples of full SDK integration are available in the GitHub repository.

| Reference | Language | Link |
|---|---|---|
| Kotlin test app (full module) | Kotlin + Compose | [app/](https://github.com/eka-care/Eka-Scribe-Android/tree/main/app) |
| Kotlin `TestActivity.kt` | Kotlin | [TestActivity.kt](https://github.com/eka-care/Eka-Scribe-Android/blob/main/app/src/main/java/com/eka/voice2rx/TestActivity.kt) |
| Java sample app (full module) | Java + Kotlin bridge | [sample-java/](https://github.com/eka-care/Eka-Scribe-Android/tree/main/sample-java) |
| Java `TestActivity.java` | Java | [TestActivity.java](https://github.com/eka-care/Eka-Scribe-Android/blob/main/sample-java/src/main/java/com/eka/voice2rx/javasample/TestActivity.java) |
| Java callback implementation | Java | [MyScribeCallback.java](https://github.com/eka-care/Eka-Scribe-Android/blob/main/sample-java/src/main/java/com/eka/voice2rx/javasample/MyScribeCallback.java) |
| Java token storage implementation | Java | [MyTokenStorage.java](https://github.com/eka-care/Eka-Scribe-Android/blob/main/sample-java/src/main/java/com/eka/voice2rx/javasample/MyTokenStorage.java) |
| CoroutineHelper bridge | Kotlin | [CoroutineHelper.kt](https://github.com/eka-care/Eka-Scribe-Android/blob/main/sample-java/src/main/java/com/eka/voice2rx/javasample/bridge/CoroutineHelper.kt) |

**What the Kotlin test app demonstrates:**
- Full initialization with live token storage
- Start, pause, resume, stop controls with Compose UI
- Real-time voice activity waveform visualization
- Session state flow collection
- Scanning for and processing pre-recorded call files via `processAudioFile()`

**What the Java sample app demonstrates:**
- `EkaScribe.INSTANCE` access pattern
- All constructor params passed explicitly (no default params)
- `CoroutineHelper` bridge for `startSession`, `collectSessionState`, `collectVoiceActivity`
- `LifecycleOwnerKt.getLifecycleScope(this)` for lifecycle-aware coroutine scope
- `return Unit.INSTANCE` in Java lambda callbacks
