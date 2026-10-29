# EkaScribe Android SDK — Developer Guide

A conceptual introduction to the EkaScribe Android SDK for developers who are new to the codebase.
This guide covers what the SDK does, the key concepts behind it, and how its internal pieces fit together.
For step-by-step integration code see [usage-guide.md](usage-guide.md).

---

## Table of Contents

1. [What is EkaScribe SDK](#1-what-is-ekascribe-sdk)
2. [Core Concepts](#2-core-concepts)
3. [Audio Processing Pipeline](#3-audio-processing-pipeline)
4. [Session State Machine](#4-session-state-machine)
5. [SDK Modules Reference](#5-sdk-modules-reference)
6. [Key Design Patterns](#6-key-design-patterns)
7. [Offline-First and Error Recovery](#7-offline-first-and-error-recovery)
8. [Server Protocol: Sessions API](#8-server-protocol-sessions-api)
9. [Related Documentation](#9-related-documentation)

---

## 1. What is EkaScribe SDK

EkaScribe is an Android SDK that records speech during a clinical consultation or dictation session, streams audio to the Eka Care backend in real time, and returns structured clinical documents — SOAP notes, discharge summaries, transcripts, or any custom template — through a simple callback interface.

**Supported workflows:**
- **Live consultation** — doctor and patient speaking together; SDK records, chunks, and uploads in real time
- **Dictation** — doctor speaking alone post-consultation
- **Pre-recorded file** — process an existing audio file (call recording, memo) through the same transcription pipeline

**Single entry point.** Everything goes through one Kotlin `object`:

```
EkaScribe  (com.eka.scribesdk.api.EkaScribe)
```

The host app never directly touches audio capture, encoding, upload, or polling. The SDK handles all of it.

**Version:** 4.4.0 | **Min SDK:** 24 (Android 7.0) | **Compile SDK:** 36 | **Java:** 17

---

## 2. Core Concepts

### 2.1 Sessions

A **session** is a single recording-to-result lifecycle. Each session gets a `sessionId` (`a-` plus a UUID) that ties together the recorded audio, upload state, and transcription result. Sessions on the Sessions API use the UUID's first 30 hex characters so the id fits the API's 32-character limit.

- Only one session can be active at a time — `SessionManager` enforces this.
- Sessions are persisted to a local Room database before any upload starts. If the app is killed mid-recording, no audio data is lost.
- The local model is `ScribeSession` (fields: `sessionId`, `state`, `chunkCount`, `uploadStage`, `createdAt`, `updatedAt`).

### 2.2 Session Configuration

`SessionConfig` tells the server what to do with the recorded audio:

| Field | What it controls |
|---|---|
| `languages` | Languages spoken (BCP-47 codes, e.g. `"en-IN"`, `"hi"`) — max 2 |
| `mode` | `"dictation"` (single speaker) or `"consultation"` (multi-speaker) |
| `modelType` | `"pro"` for higher accuracy, `"lite"` for faster turnaround |
| `outputTemplates` | Which clinical document formats to generate |
| `patientDetails` | Optional patient context passed to the AI model |
| `section` / `speciality` | Medical context filters |

### 2.3 Templates

Templates define the output format of the transcription result. The server produces one `TemplateOutput` per requested template. Each `TemplateOutput` contains:

- `type` — one of `MARKDOWN`, `JSON`, or `EKA_EMR`
- `sections` — a list of `SectionData(title, value)` pairs (e.g. Subjective, Objective, Assessment, Plan for SOAP notes)
- `rawOutput` — the full raw string, populated for MARKDOWN type

Available templates can be fetched via `EkaScribe.getTemplates()` and user favorites saved via `EkaScribe.updateTemplates()`.

### 2.4 Voice Activity Detection (VAD)

The SDK uses the **Silero VAD** ONNX model to classify every audio frame as speech or silence in real time.

VAD drives two things simultaneously:
1. **Chunking decisions** — frames accumulate until VAD detects a silence boundary, at which point a chunk is sealed and sent for upload.
2. **UI data** — the `VoiceActivityData` flow emits on every frame, giving the host app real-time amplitude and speech/silence signals to drive waveform animations or speaking indicators.

### 2.5 Audio Chunks

Audio is never uploaded as a single file. It is split into variable-duration MP3 segments and uploaded incrementally *during* recording.

- **Duration:** VAD-guided; preferred ~10 s, forced cut at 25 s maximum (20 s for Sessions API sessions, the limit the API advertises).
- **Overlap:** 0.5 s overlap between consecutive chunks prevents word loss at boundaries.
- **Chunk ID:** deterministic — `"{sessionId}_{index}"`. Re-uploading the same chunk is a no-op on the server. On the Sessions API the same chunk is uploaded as `audio_{index}.mp3`.
- Chunks are written to Room DB as `AudioChunkEntity` with `uploadState = PENDING` before the upload is attempted.

This design means the transcription pipeline can begin processing audio while recording is still in progress.

### 2.6 Audio Quality Analysis (SQUIM)

The **SQUIM** (Speech Quality Intuitive Metric) ONNX model runs reference-free speech quality scoring on the captured audio. It produces three metrics:

| Metric | Range | Meaning |
|---|---|---|
| STOI | 0.0 – 1.0 | Short-Time Objective Intelligibility |
| PESQ | -0.5 – 4.5 | Perceptual Evaluation of Speech Quality |
| SI-SDR | dB | Scale-Invariant Signal-to-Distortion Ratio |

Key characteristics:
- Downloaded once from CDN and cached; does not block session startup.
- Runs on a dedicated background thread at `BACKGROUND` priority — entirely off the critical recording path.
- If the model is still downloading when a session starts, frames are silently discarded (fire-and-forget).
- Can be disabled via `EkaScribeConfig(enableAnalyser = false)`.
- Model readiness is tracked by `EkaScribe.analyserStateFlow: StateFlow<AnalyserState>`.

### 2.7 Recording Modes

| Mode | Use case |
|---|---|
| `"dictation"` | Single speaker; doctor recording notes alone. Optimized for monologue transcription. |
| `"consultation"` | Multi-speaker; doctor and patient. Optimized for speaker-aware clinical note generation. |

---

## 3. Audio Processing Pipeline

The entire pipeline is built from bounded Kotlin Channels connecting independent coroutines. Backpressure is natural: a slow consumer suspends its producer rather than dropping data.

```
┌──────────────────────────────────────────────────────────────────────┐
│  MICROPHONE THREAD (Android AudioRecord — hard real-time)            │
│  16 kHz, mono, 16-bit PCM, 512 samples/frame (32 ms)                 │
│  → writes frames to PreBuffer (lock-free ring buffer, ~64 s)         │
└─────────────────────────────┬────────────────────────────────────────┘
                              │ drained by FrameProducer coroutine
                              ▼
             ┌────────────────────────────────┐
             │  FrameChannel (bounded, ~20 s) │
             └────────────┬───────────────────┘
                          │
               ┌──────────┴──────────┐
               │                     │
               ▼                     ▼ (fire-and-forget)
    VadAudioChunker          SquimAudioAnalyser
    (receives frames)        (background thread)
    applies Silero VAD       downloads model once
    accumulates speech       runs ONNX inference
    seals chunk on           emits AudioQualityMetrics
    silence boundary         to qualityFlow
               │
               ▼
     AudioChunk (MP3 frames + optional quality score)
               │
     ChunkChannel (bounded, ~80 chunks)
               │
               ▼
     Persistence coroutine:
       Mp3AudioEncoder.encode(frames) → .mp3 file
       DataManager.saveChunk()        → AudioChunkEntity (PENDING) in Room DB
       ChunkUploader.upload()         → Sessions API: POST /voice/v1/sessions/{id}/audio/audio_{n}.mp3
                                        (legacy sessions: AWS S3)
       DataManager.markUploaded()     → AudioChunkEntity (SUCCESS)
               │
               ▼  (session lifecycle: TransactionManager → SessionsApiFlow)
     startSession : POST  /voice/v1/sessions             create
                    PATCH /voice/v1/sessions/{id}        session_mode (in the background)
     stopSession  : POST  /voice/v1/sessions/{id}/end    starts server processing
     result       : GET   /voice/v1/sessions/{id}        poll every 2 s: 202 → 200 / 206
               │
               ▼
     SessionResult → EkaScribeCallback.onSessionCompleted()
```

The diagram shows the Sessions API flow. Sessions created with `useSessionsApi = false` (the default) use the legacy transaction APIs and S3 instead; see [§ 8](#8-server-protocol-sessions-api).

**Why the PreBuffer?** The Android `AudioRecord` callback runs on a hard-real-time thread that must never block. The PreBuffer is a lock-free ring buffer that the audio thread writes to without any coroutine overhead. The `FrameProducer` coroutine drains it at its own pace on `Dispatchers.Default`.

**Why is SQUIM off the critical path?** Quality scoring is best-effort. The chunker and uploader never wait for a quality score. Scores are forwarded asynchronously and attached to the next chunk emitted — ensuring the recording pipeline is never stalled by model inference.

---

## 4. Session State Machine

`SessionState` (at `com.eka.scribesdk.api.models.SessionState`) defines 8 states. `SessionManager` enforces that only valid transitions occur; invalid calls throw `ScribeException(INVALID_STATE_TRANSITION)`.

### State Transition Table

| From | To | Trigger |
|---|---|---|
| `IDLE` | `STARTING` | `startSession()` called |
| `STARTING` | `RECORDING` | Create Session succeeded (legacy: `initTransaction`) |
| `STARTING` | `ERROR` | Create Session failed (legacy: `initTransaction`) |
| `RECORDING` | `PAUSED` | `pauseSession()` called |
| `RECORDING` | `STOPPING` | `stopSession()` called |
| `RECORDING` | `ERROR` | Unrecoverable error during recording |
| `PAUSED` | `RECORDING` | `resumeSession()` called |
| `PAUSED` | `STOPPING` | `stopSession()` called |
| `PAUSED` | `ERROR` | Unrecoverable error |
| `STOPPING` | `PROCESSING` | All remaining chunks flushed; End Session succeeded (legacy: `stopTransaction` + `commitTransaction`) |
| `STOPPING` | `ERROR` | Transaction or upload failure |
| `PROCESSING` | `COMPLETED` | Server result received via polling |
| `PROCESSING` | `ERROR` | Poll timeout or transcription failure |
| `COMPLETED` | `IDLE` | Result delivered to callback |
| `ERROR` | `IDLE` | `retrySession()` or new `startSession()` |

### What each state means

| State | System activity |
|---|---|
| `IDLE` | No active session. SDK is ready. |
| `STARTING` | Create Session call in flight (legacy: `initTransaction`). Microphone not yet open. |
| `RECORDING` | Microphone active. Pipeline running. Chunks uploading in background. |
| `PAUSED` | Microphone paused. In-progress chunk flushed. Pipeline suspended. |
| `STOPPING` | Microphone stopped. End Session call in flight (legacy: `stopTransaction`). Remaining chunks flushing. |
| `PROCESSING` | Session ended (legacy: `commitTransaction` completed). Server processing audio. SDK polling for result. |
| `COMPLETED` | Result delivered via `onSessionCompleted`. State resets to `IDLE` after delivery. |
| `ERROR` | Unrecoverable error. State resets to `IDLE` when retry or new session starts. |

The host app observes state via `EkaScribe.getSessionState(): Flow<SessionState>` or via the `EkaScribeCallback` events. See [usage-guide.md § 7](usage-guide.md#7-observing-session-state).

---

## 5. SDK Modules Reference

All source lives under `scribesdk/src/main/java/com/eka/scribesdk/`. For full class diagrams see [LLD.md](LLD.md).

| Module | Package | Responsibility |
|---|---|---|
| **api** | `com.eka.scribesdk.api` | Public facade (`EkaScribe`), SDK config (`EkaScribeConfig`), callback interface (`EkaScribeCallback`), all public data models |
| **session** | `com.eka.scribesdk.session` | Session lifecycle (`SessionManager`), server API orchestration (`TransactionManager` runs the Sessions API lifecycle through `SessionsApiFlow`: create → end → poll; legacy: init → stop → commit → poll), Sessions API reads, edits, conversions and audio download (`SessionsResultFetcher`, `SessionsDocumentWriter`, `SessionsTemplateProcessor`, `SessionAudioDownloader`), per-session backend routing (`SessionApiRouter`), pre-recorded file processing (`AudioFileProcessor`) |
| **recorder** | `com.eka.scribesdk.recorder` | Raw PCM capture from Android `AudioRecord` at 16 kHz mono; emits `AudioFrame` objects |
| **pipeline** | `com.eka.scribesdk.pipeline` | Wires all processing stages together using bounded Kotlin Channels; owns all coroutines and their scopes |
| **chunker** | `com.eka.scribesdk.chunker` | VAD-driven audio segmentation using Silero ONNX; produces `AudioChunk` objects |
| **analyser** | `com.eka.scribesdk.analyser` | SQUIM audio quality scoring; lazy CDN model download and ONNX inference |
| **encoder** | `com.eka.scribesdk.encoder` | PCM-to-MP3 encoding (`Mp3AudioEncoder`); file-based chunking for pre-recorded files (`AudioFileChunker`) |
| **data** | `com.eka.scribesdk.data` | Room DB persistence, chunk upload (`SessionsChunkUploader`; legacy `S3ChunkUploader`), REST API calls via Retrofit (`SessionsApiService`; legacy `ScribeApiService`); `DataManager` interface; `ScribeRepository` for result/template/config APIs |
| **common** | `com.eka.scribesdk.common` | Error types (`ErrorCode`, `ScribeException`), structured logger, utilities (`IdGenerator`, `FileUtils`, `TimeProvider`, `PermissionUtils`) |

---

## 6. Key Design Patterns

| Pattern | Where | Why |
|---|---|---|
| **Facade** | `EkaScribe` object | Single entry point hides the entire internal object graph from the host app |
| **State Machine** | `SessionManager` + `SessionState.canTransitionTo()` | Prevents illegal state changes; invalid calls fail immediately with a clear error |
| **Pipeline / Pipes-and-Filters** | `Pipeline` + bounded `Channel`s | Each stage is independently testable; backpressure is structural, not code |
| **Repository** | `DataManager`, `ScribeRepository` | Abstracts persistence (Room DB + files + REST; chunk upload to the Sessions API or, for legacy sessions, S3) behind stable interfaces |
| **Router** | `SessionApiRouter`, `ApiFlowChunkUploader` | Picks the backend flow (Sessions API or legacy) per session, so a session always finishes on the backend it started on |
| **Observer / Reactive Streams** | All `Flow<>` properties | Host app reacts to state changes without polling SDK state |
| **Null Object** | `NoOpAudioAnalyser` | Eliminates null-checks throughout the pipeline when `enableAnalyser = false` |
| **Offline-First** | `AudioChunkEntity` written to Room DB before upload | Process death during upload loses nothing; retry re-reads persisted chunks |
| **Idempotent Upload** | Deterministic `chunkId = "{sessionId}_{index}"` | Re-uploading the same chunk is safe; no duplicates on the server |

---

## 7. Offline-First and Error Recovery

### Why chunks are persisted before upload

Every `AudioChunk` is written to Room DB (`AudioChunkEntity`) with `uploadState = PENDING` before the upload is attempted. If the app process is killed — mid-recording or mid-upload — all pending chunks survive. On restart, `retrySession(sessionId)` resumes from wherever the session stopped.

### TransactionManager and stage tracking

The server-side session lifecycle runs through sequential stages: `INIT → STOP → COMMIT → ANALYZING → COMPLETED`. `TransactionManager` stores the current `TransactionStage` in the local DB and advances it as each step succeeds. **The stored stage is the next step to run.** `retrySession()` calls `TransactionManager.checkAndProgress()`, which reads the stage and continues from there, skipping steps that already succeeded.

Both backend flows share the same stages:

| Stage (next step to run) | Sessions API | Legacy |
|---|---|---|
| `INIT` | Create Session (PATCH `session_mode` runs in the background) | `initTransaction` |
| `STOP` | Upload any pending chunks, then End Session | Upload any pending chunks, then `stopTransaction` |
| `COMMIT` | No call. End Session already started processing, so the stage moves straight on | `commitTransaction` |
| `ANALYZING` | Poll Get Session until 200 / 206 | Poll `v3/status` |
| `COMPLETED` / `FAILURE` / `ERROR` | Terminal | Terminal |

### UploadStage enum

`UploadStage` mirrors that stage and is observable via `EkaScribe.getUploadProgress(sessionId): Flow<UploadStage?>`:

| Stage | Meaning |
|---|---|
| `INIT` | Session not created on the server yet |
| `STOP` | Session created; recording and uploading chunks, End Session still to come |
| `COMMIT` | Session ended; the legacy flow still has to commit (the Sessions API skips this) |
| `ANALYZING` | Server is processing the audio; the SDK is polling |
| `COMPLETED` | Result ready |
| `FAILURE` | Processing finished without usable output, the session expired (410), or all retry attempts failed |
| `ERROR` | Unexpected error |

### forceCommit

`retrySession(sessionId, forceCommit = true)` tells the SDK to go ahead with End Session (legacy: stop and commit) even if some chunk uploads failed. Use this when network conditions were poor but enough audio was captured to produce a useful result. End Session reports how many chunks were uploaded (`audio_files_sent`).

---

## 8. Server Protocol: Sessions API

New sessions run on the EkaScribe Sessions API (`/voice/v1/sessions`, [docs](https://developer.eka.care/ekascribe/api-reference/sessions/overview)) when `EkaScribeConfig(useSessionsApi = true)`. With the flag off (the default) new sessions use the legacy transaction APIs and S3.

### 8.1 Recording lifecycle

| Step | Sessions API | Legacy |
|---|---|---|
| Start | `POST /voice/v1/sessions` (create), then `PATCH /voice/v1/sessions/{id}` (`session_mode`, `user_status = recording_started`) in the background; End waits for it | `POST voice/api/v2/transaction/init/{id}` |
| Upload each chunk | `POST /voice/v1/sessions/{id}/audio/audio_{n}.mp3`, raw bytes | PUT to S3 with credentials from `getS3Config` |
| Stop | `POST /voice/v1/sessions/{id}/end` with `audio_files_sent`, then a best-effort `PATCH` of `additional_data.audio_duration` | `POST voice/api/v2/transaction/stop/{id}` |
| Commit | None. End Session starts processing | `POST voice/api/v2/transaction/commit/{id}` |
| Result | `GET /voice/v1/sessions/{id}` every 2 s for up to 2 minutes: 202 still processing, 200 done, 206 done with some failed templates, 410 expired | `GET voice/api/v3/status/{id}` |

Details worth knowing:

- **Create body:** up to 2 `templates` (extras are dropped), `language_hint` as ISO 639-1 codes (`en-IN` becomes `en`; `auto_detect` is kept), `patient_details` (`oid`, `name`, `age`, `gender`) and `additional_data` (`_flavour`, `sdk_version`, `app_version`, `speciality`, `section`, `visit_id`).
- **Recovery is idempotent:** a 409 on create means an earlier attempt already created the session, and a 400 "already ended" on End Session means an earlier end succeeded. Both continue instead of failing.
- **No full-audio upload.** The Sessions API only takes chunks, so a full-recording upload fails with a non-retryable error.

### 8.2 Reading, editing and converting a session

| Public API | Sessions API | Legacy |
|---|---|---|
| `getSessionOutput`, `pollSessionResult` | Get Session (`GET /voice/v1/sessions/{id}`) for content, plus List Session Documents (`GET /voice/api/v1/sessions/{id}`) for note names and audio quality | `GET voice/api/v3/status/{id}` |
| `convertTransactionResult` | Process Template: `POST /voice/v1/sessions/{id}/process/template/{template_id}` returns 202 and a `document_id`, then `GET /voice/v1/sessions/{id}?document_id=` every 2 s (up to 60 tries) until that note finishes | `POST voice/api/v1/transaction/{id}/convert-to-template/{template_id}` |
| `updateSessionResult` | Create/Update Document: `POST /voice/api/v1/documents` returns a pre-signed URL, then PUT the base64 text (`text/plain`) to it | `PATCH voice/api/v3/status/{id}` |
| `getSessionAudioLink`, `downloadSessionAudio` | Get Session Audio: `GET /voice/v1/sessions/{id}/audio` returns a fresh pre-signed MP3 URL. 403 `audio_not_enabled` when full audio is off for the business; 404 while it is still being combined | Not available |
| Templates, user configs, history | Unchanged (`voice/api/v1/template`, `voice/api/v2/config`, `voice/api/v2/transaction/history`) | Same |

How a Sessions API result is shaped:

- **A note is a document.** Every note has a `documentId`. Process Template creates a new document on every call, so one session can hold several notes made from the same template. UIs should key note tabs by `documentId`, not by `templateId`.
- **Names and quality come from List Session Documents.** Get Session carries each note's content inline but not its name or `audio_matrix.quality`, so the SDK calls List Session Documents once per result for those.
- **Context** (template id `__non_tmp_doc`) is listed by Get Session without its text. The SDK reads the text through Get Document and its pre-signed GET URL. A session with no context document still shows an empty, editable Context; the document is created (`type: context`) on the first save.
- **Clinical Notes** (`clinical_notes_template`) is always shown and is blank when the server produced nothing. If a session holds several, the newest one that can be shown is kept. Other empty integration documents (for example `eka_emr_template`) are skipped.
- **Edits are plain text.** `updateSessionResult` stores markdown only. The SDK does not write the web editor's formatted (Tiptap JSON) copy, so a note that was edited on the web can keep showing the web version. This is a known backend limitation.
- **Stuck notes.** Get Session reports a note that failed as `failure`, while List Session Documents can keep showing `in-progress`. The SDK trusts Get Session.

### 8.3 Which backend a session uses

`SessionApiRouter` decides per session, so a session always finishes on the backend it started on:

1. **Recorded on this device:** the flow stored in the local DB (`SessionEntity.api_flow`: `sessions_v1`, or null for legacy). `ApiFlowChunkUploader` applies the same rule to each chunk upload.
2. **Not on this device** (web, another phone, desktop): the Sessions API when `useSessionsApi` is on, as the web does for every session.
3. **Fallback:** if the Sessions API answers 400, 404 or 422 for such a session (an old pre-migration id), the SDK retries on the legacy APIs and remembers that for the session until the process ends.

With `useSessionsApi = false`, everything goes to the legacy APIs except sessions already recorded on this device on the Sessions API, which keep their own flow.

---

## 9. Related Documentation

| Document | What it covers |
|---|---|
| [usage-guide.md](usage-guide.md) | Complete integration guide: every public API with Kotlin and Java code examples |
| [setup.md](setup.md) | Development environment setup, running the sample apps, build commands |
| [LLD.md](LLD.md) | Full low-level design: class diagrams, sequence diagrams, Room DB schema |
| [README.md](../README.md) | Quick-start installation and minimal integration example |
| [EkaScribe Sessions API](https://developer.eka.care/ekascribe/api-reference/sessions/overview) | The server API this SDK uses for new sessions |
| [TEST_APP_GUIDE.md](../TEST_APP_GUIDE.md) | How to use the Kotlin test app (app module) |
| [Kotlin Test App](https://github.com/eka-care/Eka-Scribe-Android/blob/main/app/src/main/java/com/eka/voice2rx/TestActivity.kt) | Full Kotlin integration: init, session controls, voice activity, state flow, file processing |
| [Java Sample App](https://github.com/eka-care/Eka-Scribe-Android/tree/main/sample-java/src/main/java/com/eka/voice2rx/javasample) | Full Java integration with CoroutineHelper bridge |
