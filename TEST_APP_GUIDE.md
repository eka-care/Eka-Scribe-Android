# EkaScribe SDK Test App Guide

## Overview

This test app provides a simple UI to test the core functionality of the EkaScribe SDK with minimal
configuration.

## Features

The test app includes:

1. **START RECORDING** - Starts a new voice recording session
2. **PAUSE** - Pauses the current recording
3. **RESUME** - Resumes a paused recording
4. **STOP RECORDING** - Stops recording and triggers transcription

## Test Configuration

The app uses minimal configuration:

- **Mode**: Consultation
- **Language**: English (India) - `en-IN`
- **Model Type**: Pro
- **Output Format**: Transcript only
- **Patient Details**: Test patient data

## Setup Instructions

### 1. Build and Install

```bash
./gradlew installDebug
```

### 2. Launch the App

The TestActivity is set as the launcher activity. When you install and open the app, it will
automatically launch the test screen.

### 3. Grant Permissions

On first launch, tap "START RECORDING" and grant the RECORD_AUDIO permission when prompted.

## How to Use

### Starting a Recording

1. Tap the **START RECORDING** button (green)
2. Grant audio permission if prompted
3. The status card will turn green and show "Recording"
4. A session ID will be displayed
5. Start speaking - the voice activity indicator will show real-time audio levels

### Pausing and Resuming

1. While recording, tap **PAUSE** (orange) to pause
2. Tap **RESUME** (blue) to continue recording
3. The voice activity monitoring continues even while paused

### Stopping Recording

1. Tap **STOP RECORDING** (red) when done
2. The recording will be uploaded and processed
3. Check logcat for processing updates

## Real-Time Monitoring

The app displays:

- **Recording Status**: Shows whether recording is active
- **Session ID**: Unique identifier for the current session
- **Voice Activity**: Real-time speech detection and amplitude
    - "Speaking (amplitude)" when speech is detected
    - "Silent (amplitude)" during silence
- **Recorded Files**: Number of audio segments recorded

## Logging

All SDK events are logged with the following tags:

- `TestActivity` - Main activity events and errors
- `MyLifecycleCallbacks` - Session lifecycle events
    - Session started
    - Session stopped
    - Session paused
    - Session resumed
    - Errors

### Viewing Logs

```bash
# View all test app logs
adb logcat -s TestActivity MyLifecycleCallbacks

# View SDK debug logs (if debugMode is enabled)
adb logcat | grep Voice2Rx
```

## Configuration

### Network Configuration

Located in `TestActivity.kt`:

```kotlin
NetworkConfig(
    tokenStorage = MyTokenStorage(),
    appId = "scribe-android",
    appVersionCode = 1,
    appVersionName = "1.0.0",
    apiCallTimeOutInSec = 30,
    isDebugApp = true,
    baseUrl = "https://api.eka.care/",
    headers = mapOf()
)
```

### SDK Configuration

```kotlin
Voice2RxInitConfig(
    voice2RxLifecycle = MyLifecycleCallbacks(),
    networkConfig = networkConfig,
    debugMode = true  // Enable for detailed SDK logs
)
```

### Recording Parameters

Located in `startRecording()` method:

```kotlin
Voice2Rx.startVoice2Rx(
    mode = Voice2RxType.CONSULTATION.value,
    patientDetails = PatientDetails(
        age = "30",
        biologicalSex = "M",
        name = "Test Patient",
        patientId = "TEST-001",
        visitId = "VISIT-001"
    ),
    outputFormats = listOf(
        Template(templateId = "transcript", templateName = "Transcript")
    ),
    languages = listOf("en-IN"),
    modelType = "pro",
    onError = { /* error handling */ },
    onStart = { /* success callback */ }
)
```

## Customization

### Changing Language

Edit `startRecording()` in `TestActivity.kt`:

```kotlin
languages = listOf("hi"),  // For Hindi
// or
languages = listOf("en-IN", "hi"),  // For bilingual (max 2)
```

### Changing Output Format

```kotlin
outputFormats = listOf(
    Template(templateId = "soap_note", templateName = "SOAP Note"),
    Template(templateId = "transcript", templateName = "Transcript")
)
// Max 2 output formats allowed
```

### Changing Model Type

```kotlin
modelType = "lite",  // For faster processing
// or
modelType = "pro",   // For higher accuracy
```

### Changing Mode

```kotlin
mode = Voice2RxType.DICTATION.value,  // For dictation mode
// or
mode = Voice2RxType.CONSULTATION.value,  // For consultation mode
```

## Troubleshooting

### Permission Denied Error

- Ensure RECORD_AUDIO permission is granted
- Go to App Settings > Permissions > Microphone and enable it

### Recording Not Starting

- Check logcat for error messages
- Verify network connectivity
- Ensure authentication token is valid

### No Voice Activity Detected

- Check microphone hardware
- Verify app has microphone access
- Test with louder speech

### SDK Not Initialized Error

- Ensure `Voice2Rx.init()` is called before using SDK functions
- Check for initialization errors in logcat

## Testing Workflow

### Basic Test Flow

1. Launch app
2. Grant microphone permission
3. Tap START RECORDING
4. Speak for 10-15 seconds
5. Tap PAUSE (verify voice activity stops)
6. Tap RESUME (verify voice activity resumes)
7. Speak for another 10-15 seconds
8. Tap STOP RECORDING
9. Check logcat for upload/processing status

### Expected Behavior

- Green status card when recording
- Real-time voice activity updates
- Toast messages for all button actions
- Session ID displayed after starting
- Lifecycle callbacks logged

## Files Modified

1. `app/src/main/java/com/eka/voice2rx/TestActivity.kt` - Test UI implementation
2. `app/src/main/AndroidManifest.xml` - Activity declaration and permissions

## Next Steps

After verifying basic functionality:

1. Test with different languages
2. Test with multiple output formats
3. Test longer recording sessions
4. Test network error scenarios
5. Verify transcription results using `getSessionOutput()`

## Support

For issues or questions about the SDK, refer to `EKASCRIBE_SDK_DOCUMENTATION.md` for complete API
reference.
