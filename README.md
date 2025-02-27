# Voice2Rx Android SDK

Voice2Rx is an Android SDK that allows voice transcription and structured prescription data generation for healthcare applications.

## Table of Contents

- [Prerequisites](/mobile-sdk/android/voice-to-rx#prerequisites)
- [Installation](/mobile-sdk/android/voice-to-rx#installation)
- [Initialization](/mobile-sdk/android/voice-to-rx#initialization)
- [Basic Usage](/mobile-sdk/android/voice-to-rx#basic-usage)
    - [Starting a Voice Session](/mobile-sdk/android/voice-to-rx#starting-a-voice-session)
    - [Stopping a Voice Session](/mobile-sdk/android/voice-to-rx#stopping-a-voice-session)
    - [Checking Session Status](/mobile-sdk/android/voice-to-rx#checking-session-status)
- [Working with Sessions](/mobile-sdk/android/voice-to-rx#working-with-sessions)
    - [Retrieving Session History](/mobile-sdk/android/voice-to-rx#retrieving-session-history)
    - [Sample Structured Rx Data](/mobile-sdk/android/voice-to-rx#sample-structured-rx-data)
    - [Sample Markdown Output](/mobile-sdk/android/voice-to-rx#sample-markdown-output)
- [Advanced Features](/mobile-sdk/android/voice-to-rx#advanced-features)
    - [Retrying a Session](/mobile-sdk/android/voice-to-rx#retrying-a-session)
- [Cleanup](/mobile-sdk/android/voice-to-rx#cleanup)
- [Links](/mobile-sdk/android/voice-to-rx#links)

## Prerequisites

Before integrating the Voice2Rx SDK, ensure that your Android project meets the following requirements:

- Android API level 24 or higher
- Kotlin 1.9.0 or higher
- Microphone permission in your AndroidManifest.xml:
    
    ```xml
    
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.INTERNET" />
    ```
    
- Runtime permission handling for microphone access

## Installation

Add the following dependency to your `app/build.gradle.kts` file:

```kotlin
implementation("com.github.eka-care:eka-v2rx-android:1.0.5")

```

## Initialization

To initialize the SDK, you need to implement the `IOkHttpSetup` interface and provide the necessary configuration:

```kotlin
// Create object implementing IOkHttpSetup interface to provide auth tokens
class OkHttpImpl : IOkHttpSetup {
    // Provide headers which are necessary for authentication
    override fun getDefaultHeaders(url: String): Map<String, String> {
        val headers = HashMap<String, String>()
        headers["auth"] = // provide session token here for authentication
        return headers
    }

    override fun refreshAuthToken(url: String): Map<String, String>? {
        // refresh auth token here
        return null
    }

    override fun onSessionExpire() {
        // handle token expiration
    }
}

// Initialize the SDK
Voice2Rx.init(
    context = context,
    config = Voice2RxInitConfig(
        onStart = {
            // Code to execute when recording starts
        },
        onStop = { sessionId, recordedFiles ->
            // Code to execute when recording stops
        },
        onError = { sessionId, errorMsg ->
            // Handle errors
        },
        docOid = "",       // Doctor oid
        docUuid = "",      // Doctor UUID
        ownerId = "",      // Owner ID
        contextData = ContextData(
            doctor = null,
            patient = null,
            visitid = sessionId
        ),
        okHttpSetup = OkHttpImpl(),
        callerId = "",     // Caller ID
    )
)

```

## Basic Usage

### Starting a Voice Session

To start a voice recording session:

```kotlin
// Start a voice session in DICTATION or CONSULTATION mode
Voice2Rx.startVoice2Rx(mode = Voice2RxType.DICTATION)
// The onStart callback (defined in init) will be triggered when the session begins

```

### Stopping a Voice Session

To stop a voice recording session:

```kotlin
// Stop the current voice session
Voice2Rx.stopVoice2Rx()
// The onStop callback will be triggered with the sessionId

```

### Checking Session Status

To check if a recording session is currently active:

```kotlin
// Returns true if a recording session is in progress
val isRecording = Voice2Rx.isCurrentlyRecording()

```

## Working with Sessions

### Retrieving Session History

To access previous recording sessions:

```kotlin
// Get all recording sessions
val sessions = Voice2Rx.getSessions()

// Access a specific session (example using the first session)
val session = sessions.firstOrNull()

// Access session data
session?.let {
    val transcript = it.transcript       // Raw transcript text
    val structuredRx = it.structuredRx   // Structured prescription data
}

```

### Sample Structured Rx Data

The SDK returns structured prescription data in JSON format:

```json
{
  "prescription": {
    "advices": [
      {
        "id": "locale-4f1e59b7c71003546b7bb5aadb89280b",
        "parsedText": "Take bedrest for at least 7 days",
        "text": "Take bedrest for at least 7 days"
      },
      {
        "id": "locale-b3ca8e9447de58b3738549ec3c8db922",
        "parsedText": "Do exercise with physiotherapist",
        "text": "Do exercise with physiotherapist"
      }
    ],
    "followup": {
      "date": "2025-03-09T00:00:00.000000Z"
    },
    "labTests": [
      {
        "common_name": "Blood sugar test",
        "id": "locale-5686e09a70ffcbc355b372f57b744752",
        "name": "Blood sugar test"
      },
      {
        "common_name": "Liver function test",
        "id": "locale-9b501b45359316407782679ce7eeabac",
        "name": "Liver function test"
      },
      {
        "common_name": "X-ray",
        "id": "locale-3c51a99d9bbfefde7ba517f3316899f2",
        "name": "X-ray"
      }
    ],
    "language": "EN",
    "medications": [
      {
        "duration": {
          "custom": "3 Days",
          "unit": "Days",
          "value": 3
        },
        "frequency": {
          "custom": "1-1-1",
          "dose": [
            "1",
            "1",
            "1"
          ],
          "frequency": "3",
          "pattern_id": "fp-1",
          "period": 1,
          "period_unit": "day"
        },
        "id": "locale-a0fdc63d09d1d255b00796ade40bb3c6",
        "name": "Molo 650 tablet",
        "original_name": "mdolo 650 tablet",
        "linked": {
          "eka_id": "b-4974923412"
        }
      }
    ],
    "symptoms": [
      {
        "id": "locale-f532fde6e2a357af72b9de6aa1f0e65e",
        "name": "Fever",
        "status": "preliminary"
      },
      {
        "id": "locale-6a44898c82426d50cb083373d59b155a",
        "name": "Headache",
        "status": "preliminary"
      },
      {
        "id": "locale-867fc9a3c94474b3c77ee9a0b5aae022",
        "name": "Leg pain",
        "status": "preliminary"
      },
      {
        "id": "locale-a7033cb31b1991cc8f4c9c751a8452b6",
        "name": "Neck pain",
        "status": "preliminary"
      }
    ]
  }
}

```

### Sample Markdown Output

The SDK can also generate a formatted markdown output:

```markdown
**Chief Complaints**
- Fever
- Headache
- Leg pain
- Neck pain

**Assessment**
- Symptomatic fever with multiple body pains

**Plan**
- Medications:
  - Mdolo 650 tablet - TID for 3 days
- Investigations advised:
  - Blood test
  - Blood sugar test
  - Liver function test
  - X-ray
- Physiotherapy referral

**Patient Instructions**
- Take complete bed rest for 7 days
- Follow up after 15 days with all investigation reports
- Continue physiotherapy sessions
- Take prescribed medications as directed

```

## Advanced Features

### Retrying a Session

To retry processing a session:

```kotlin
Voice2Rx.retrySession(
    context = context,
    sessionId = sessionId,
    onResponse = { responseState ->
        // Handle the retry response
        // Indicates whether retry was successful or not
    }
)

```

## Cleanup

When you're done using the SDK, free up resources:

```kotlin
// Release all resources
Voice2Rx.dispose()

```
