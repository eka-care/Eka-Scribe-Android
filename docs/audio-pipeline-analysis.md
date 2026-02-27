# Audio Pipeline: Recording to Chunking — Detailed Flow & Loss Analysis

## Complete Data Flow

```
AndroidAudioRecorder (dedicated Thread, THREAD_PRIORITY_AUDIO)
        | callback.onFrame(frame)
        v
PreBuffer (lock-free ring buffer, capacity=200)
        | drain() every 5ms
        v
FrameProducer (coroutine, Dispatchers.Default)
        | channel.send(frame)
        v
frameChannel (Channel, capacity=64)
        | for (frame in frameChannel)
        v
Analyser Coroutine (Dispatchers.Default)
  |-- PipelineMonitor.shouldSkipAnalyser()
  |-- AudioAnalyser.analyse(frame) -> AnalysedFrame
  |-- VadAudioChunker.feed(analysedFrame)
  |     |-- SileroVAD.detect(pcm) -> isSpeech
  |     |-- accumulate frames + track speech/silence duration
  |     +-- shouldChunk()? -> createChunk()
  +-- chunkChannel.send(chunk)
        |
        v
chunkChannel (Channel, capacity=8)
        | for (chunk in chunkChannel)
        v
Persistence Coroutine (Dispatchers.IO)
  |-- M4aAudioEncoder.encode(frames -> WAV -> M4A)
  |-- DB: save AudioChunkEntity
  +-- S3ChunkUploader.upload()
```

---

## Stage-by-Stage Breakdown

### Stage 1: AndroidAudioRecorder

**File:** `recorder/AndroidAudioRecorder.kt`

- Uses Android's `AudioRecord` with `MediaRecorder.AudioSource.MIC`
- Runs on a **dedicated thread** with `THREAD_PRIORITY_AUDIO` (highest audio priority)
- Reads PCM samples in a tight loop: `audioRecord.read(buffer, 0, frameSize)`
- Each read produces one `AudioFrame` (512 samples = **32ms** at 16kHz)
- Fires `callback.onFrame(frame)` synchronously on the recording thread
- Supports pause/resume via `isPaused` AtomicBoolean flag

**Key config:**
| Parameter | Default | Description |
|-----------|---------|-------------|
| sampleRate | 16000 Hz | Mono 16-bit PCM |
| frameSize | 512 samples | ~32ms per frame |
| encoding | PCM_16BIT | 2 bytes per sample |

### Stage 2: PreBuffer (Lock-Free Ring Buffer)

**File:** `pipeline/stage/PreBuffer.kt`

- **Purpose:** Decouple the real-time audio thread from the coroutine-based pipeline
- Lock-free SPSC (single-producer, single-consumer) ring buffer using `AtomicInteger`
- **Capacity:** 200 frames = ~6.4 seconds of audio
- `write(frame)`: Called from audio thread. Returns `false` if full (frame dropped).
- `drain()`: Called from FrameProducer coroutine. Returns all available frames at once.

**Thread safety model:**

- One writer (audio thread) calls `write()`
- One reader (FrameProducer coroutine) calls `drain()`
- Three `AtomicInteger` fields: `writeIndex`, `readIndex`, `count`
- Safe under SPSC assumption — no locks needed

### Stage 3: FrameProducer (Drain Loop)

**File:** `pipeline/stage/FrameProducer.kt`

- Runs as a coroutine on `Dispatchers.Default`
- Polls `preBuffer.drain()` every **5ms** (`DRAIN_INTERVAL_MS`)
- Sends each drained frame to `frameChannel.send(frame)` — **suspends on backpressure**
- If the channel is full, `send()` suspends, which means `drain()` stops being called, which means
  PreBuffer fills up

### Stage 4: frameChannel (Kotlin Channel)

- **Type:** `Channel<AudioFrame>(capacity = 64)`
- Holds up to 64 frames = ~2 seconds of audio
- Backpressure: when full, `send()` suspends the FrameProducer
- Combined with PreBuffer: **~8.4 seconds** of total buffering before frame loss

### Stage 5: Analyser Coroutine

**File:** `pipeline/Pipeline.kt` — `startAnalyserCoroutine()`

- Reads from `frameChannel` in a `for` loop
- Optionally runs SQUIM audio quality analysis (batches 3 seconds of audio)
- Passes each frame through `AudioAnalyser.analyse()` -> `AnalysedFrame`
- Feeds `AnalysedFrame` to `VadAudioChunker.feed()`
- If chunker returns a chunk, sends it to `chunkChannel`

**Degradation:** `PipelineMonitor.shouldSkipAnalyser()` can bypass SQUIM when frameChannel is >80%
full (but see Loss Point 2 — this is currently broken).

### Stage 6: VadAudioChunker

**File:** `chunker/VadAudioChunker.kt`

- Accumulates `AudioFrame`s and quality scores
- Runs Silero VAD on each frame's PCM data to detect speech/silence
- Tracks `speechDurationMs` and `silenceDurationMs`
- **Chunking decision** (`shouldChunk()`):

| Condition                                                                | Trigger         |
|--------------------------------------------------------------------------|-----------------|
| `speechDuration > preferred (10s)` AND `silence > minSilence (500ms)`    | Natural break   |
| `speechDuration > desperation (20s)` AND `silence > despSilence (100ms)` | Desperation cut |
| `speechDuration >= max (25s)`                                            | Forced cut      |

- `createChunk()`: Bundles accumulated frames into `AudioChunk`, resets accumulators
- `flush()`: Creates a chunk from whatever is accumulated (used at stop)

### Stage 7: chunkChannel + Persistence Coroutine

**File:** `pipeline/Pipeline.kt` — `startPersistenceCoroutine()`

- **chunkChannel:** `Channel<AudioChunk>(capacity = 8)`
- Persistence coroutine runs on `Dispatchers.IO`
- For each chunk:
    1. **Encode:** PCM frames -> WAV -> M4A (AAC, 64kbps via MediaCodec)
    2. **Persist:** Save `AudioChunkEntity` to Room DB
    3. **Upload:** S3ChunkUploader uploads to AWS, marks state in DB
    4. **Cleanup:** Delete local file after successful upload

---

## Frame Timing Math

| Metric                       | Value                 |
|------------------------------|-----------------------|
| Sample rate                  | 16,000 Hz             |
| Frame size                   | 512 samples           |
| Frame duration               | 32 ms                 |
| Frames per second            | ~31.25                |
| PreBuffer capacity           | 200 frames = ~6.4 sec |
| frameChannel capacity        | 64 frames = ~2.0 sec  |
| **Total buffer before drop** | **~8.4 seconds**      |

---

## Is It Lossless?

**No.** There are **7 identified points** where audio data can be lost.

---

### Loss Point 1: PreBuffer Full -> Frame Dropped (HIGH)

**Location:** `Pipeline.kt:83-85`

```kotlin
recorder.setFrameCallback { frame ->
    if (!preBuffer.write(frame)) {
        logger.warn(TAG, "PreBuffer full, frame dropped: ${frame.frameIndex}")
    }
}
```

**Cause:** If the downstream pipeline (analyser, chunker, encoder, uploader) collectively stalls
for >6.4 seconds, the PreBuffer fills up. Every subsequent frame from the mic is dropped with only a
log warning.

**Trigger scenarios:**

- SQUIM analyser inference takes too long on a low-end device
- M4A MediaCodec encoding stalls
- `chunkChannel.send()` suspends because persistence is slow -> blocks analyser ->
  `frameChannel.send()` suspends -> blocks FrameProducer -> PreBuffer stops draining -> fills up

**Severity: HIGH** — most likely loss point under load.

---

### Loss Point 2: PipelineMonitor Never Updated — Dead Code (BUG)

**Location:** `PipelineMonitor.kt:25-33`

```kotlin
fun updateFrameQueueSize(size: Int) { ... }  // Never called anywhere
fun updateChunkQueueSize(size: Int) { ... }  // Never called anywhere
```

`shouldSkipAnalyser()` checks `frameQueueUtilization() > 0.8f`, but `currentFrameQueueSize` is *
*always 0** because `updateFrameQueueSize()` is never called from Pipeline or any other class.

**Impact:** The degradation policy is implemented but **never activated**. The analyser never gets
skipped even under heavy backpressure, making Loss Point 1 worse than it needs to be.

**Severity: MEDIUM** — bug that disables a safety mechanism.

---

### Loss Point 3: Pipeline.stop() -> Frames Abandoned in PreBuffer (MEDIUM)

**Location:** `Pipeline.kt:105-125`

```kotlin
fun stop() {
    recorder.stop()          // 1. Stop mic
    frameProducer.stop()     // 2. Cancel drain loop  <-- frames still in PreBuffer!

    val lastChunk = chunker.flush()  // 3. Flush what chunker has
    ...
    preBuffer.clear()        // 4. Discard remaining PreBuffer frames
}
```

**Problem:** After `recorder.stop()`, there may still be frames in the PreBuffer that the
FrameProducer hasn't drained yet. `frameProducer.stop()` cancels the drain job immediately. Those
frames never reach the chunker. Then `preBuffer.clear()` destroys them.

**Data lost:** Up to ~160ms of audio (frames between last drain and stop).

**Severity: MEDIUM** — happens every stop; tail-end of recording could contain important speech.

---

### Loss Point 4: Pipeline.stop() -> Frames Abandoned in frameChannel (MEDIUM)

**Location:** `Pipeline.kt:114-118`

```kotlin
frameChannel.close()       // Signal no more frames
chunkChannel.close()       // Signal no more chunks
analyserJob?.cancel()      // Cancel immediately -- may not have consumed remaining frames!
persistenceJob?.cancel()   // Cancel immediately -- may not have processed remaining chunks!
```

**Problem:** `frameChannel.close()` correctly signals the analyser coroutine's
`for (frame in frameChannel)` loop to terminate after draining remaining items. **But**
`analyserJob?.cancel()` is called immediately after, which cancels the coroutine before it finishes
draining the channel. Same issue with `persistenceJob` and `chunkChannel`.

**Data lost:** Up to 64 frames (~2 sec) in frameChannel + up to 8 chunks in chunkChannel.

**Severity: MEDIUM** — race between channel close and job cancellation.

---

### Loss Point 5: Pipeline.stop() -> Flushed Chunk May Be Dropped (HIGH)

**Location:** `Pipeline.kt:109-112`

```kotlin
val lastChunk = chunker.flush()
if (lastChunk != null) {
    chunkChannel.trySend(lastChunk)  // Non-suspending, can fail if channel full!
}
```

**Problem:** `trySend()` is non-suspending. If `chunkChannel` is full (capacity=8), the flushed
chunk is **silently dropped**. This is the last partial chunk of the recording — the final segment
of speech.

**Severity: HIGH** — losing the last chunk means losing the tail of the conversation.

---

### Loss Point 6: Android AudioRecord Internal Buffer Overflow (LOW)

**Location:** `AndroidAudioRecorder.kt:80`

```kotlin
val readCount = audioRecord?.read(buffer, 0, config.frameSize) ?: -1
```

**Problem:** Android's `AudioRecord` has an internal OS-level buffer (size = `getMinBufferSize()`).
If our recording thread doesn't call `read()` fast enough, the OS buffer overflows and samples are
lost silently — `read()` returns the next available data with a gap.

**Likelihood:** Low — the recording thread is high-priority and `PreBuffer.write()` is non-blocking,
so `read()` loops quickly. But possible under extreme CPU pressure.

---

### Loss Point 7: Pause Discards Mic Samples (BY DESIGN)

**Location:** `AndroidAudioRecorder.kt:74-78`

```kotlin
if (isPaused.get()) {
    Thread.sleep(10)
    continue   // AudioRecord keeps buffering -> internal buffer overflows
}
```

**Problem:** When paused, the recording thread sleeps but `AudioRecord` keeps capturing audio into
its internal buffer. Since nobody calls `read()`, the internal buffer overflows. This is by design (
pause should discard), but `AudioRecord` is still consuming power/resources while paused.

**Severity: LOW** — intentional behavior, but `audioRecord.stop()` / `audioRecord.startRecording()`
would be cleaner.

---

## Summary Table

| # | Location                         | What's Lost                             | Severity | Likelihood        |
|---|----------------------------------|-----------------------------------------|----------|-------------------|
| 1 | PreBuffer.write() returns false  | Individual frames during backpressure   | HIGH     | Medium            |
| 2 | PipelineMonitor never updated    | Degradation never triggers (worsens #1) | MEDIUM   | Certain (bug)     |
| 3 | stop() -> PreBuffer not drained  | Tail frames (~5-160ms)                  | MEDIUM   | Every stop        |
| 4 | stop() -> cancel before drain    | Frames/chunks in channels               | MEDIUM   | Every stop        |
| 5 | stop() -> trySend for last chunk | Final chunk of recording                | HIGH     | When channel full |
| 6 | AudioRecord internal overflow    | Raw samples                             | LOW      | Extreme CPU       |
| 7 | Pause doesn't stop AudioRecord   | Samples during pause                    | LOW      | By design         |

---

## Recommended Improvements

### Fix 1: Wire PipelineMonitor (Bug Fix)

The monitor's `updateFrameQueueSize` / `updateChunkQueueSize` are never called. Wire them into the
analyser coroutine so degradation actually activates.

Since Kotlin's `Channel` doesn't expose queue size directly, track it manually with an
`AtomicInteger` incremented on send and decremented on receive.

### Fix 2: Graceful Shutdown (Critical)

Replace the current cancel-based shutdown with a drain-then-close approach:

```kotlin
fun stop() {
    recorder.stop()

    // 1. Let FrameProducer drain remaining PreBuffer, then stop
    frameProducer.drainAndStop()

    // 2. Close frameChannel -> analyser loop terminates naturally
    frameChannel.close()
    analyserJob?.join()  // Wait for all frames to be processed

    // 3. Flush last partial chunk (suspending send - guaranteed delivery)
    val lastChunk = chunker.flush()
    if (lastChunk != null) {
        chunkChannel.send(lastChunk)
    }

    // 4. Close chunkChannel -> persistence loop terminates naturally
    chunkChannel.close()
    persistenceJob?.join()  // Wait for all chunks to be encoded+uploaded

    // 5. Now safe to release
    analyser.release()
    chunker.release()
    preBuffer.clear()
}
```

This ensures every frame reaches the encoder. The `join()` calls wait for natural completion instead
of cancelling mid-work.

### Fix 3: Replace `trySend` with Suspending `send`

The `chunker.flush()` result must not be dropped. Since stop runs in a coroutine scope, use `send()`
which suspends until there's room.

### Fix 4: Track Frame Sequence Gaps

Add a gap detector in the analyser coroutine for observability:

```kotlin
var lastFrameIndex = -1L
for (frame in frameChannel) {
    if (lastFrameIndex >= 0 && frame.frameIndex != lastFrameIndex + 1) {
        val dropped = frame.frameIndex - lastFrameIndex - 1
        logger.warn(TAG, "Detected $dropped dropped frames after index $lastFrameIndex")
    }
    lastFrameIndex = frame.frameIndex
    // ...
}
```

### Fix 5: Replace PreBuffer + FrameProducer with Channel(UNLIMITED)

The PreBuffer exists to decouple the real-time audio thread from coroutines. But
`Channel(Channel.UNLIMITED)` with `trySend()` achieves the same with less code and no fixed
capacity:

```kotlin
val frameChannel = Channel<AudioFrame>(Channel.UNLIMITED)

recorder.setFrameCallback { frame ->
    frameChannel.trySend(frame)  // Never fails with UNLIMITED
}
```

This eliminates the PreBuffer, FrameProducer, and the 5ms polling overhead entirely. Memory grows
unbounded in theory, but since downstream consumes frames continuously, it stays small in practice.
A cap can be added with `Channel(10000)` if needed.

### Fix 6: Pause Should Stop AudioRecord

Instead of spinning in `Thread.sleep(10)` while `AudioRecord` keeps buffering:

```kotlin
override fun pause() {
    isPaused.set(true)
    audioRecord?.stop()           // Actually stop hardware capture
}

override fun resume() {
    isPaused.set(false)
    audioRecord?.startRecording() // Resume hardware capture
}
```

This saves battery and avoids internal buffer overflow during pause.

---

## Priority Order for Fixes

1. **Fix 2 (Graceful Shutdown)** — Prevents data loss on every single stop. Critical.
2. **Fix 3 (trySend -> send)** — Prevents losing the last chunk. One-line change.
3. **Fix 1 (Wire PipelineMonitor)** — Enables the degradation safety net. Prevents cascading
   backpressure.
4. **Fix 4 (Frame Gap Tracking)** — Observability. Helps detect and quantify data loss in
   production.
5. **Fix 5 (Channel UNLIMITED)** — Simplifies architecture, removes polling overhead, eliminates
   PreBuffer loss point.
6. **Fix 6 (Pause stops AudioRecord)** — Minor improvement, saves battery.
