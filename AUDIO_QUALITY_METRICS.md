# Real-time Audio Quality Metrics

## Overview

The Voice2Rx SDK now provides real-time audio quality metrics to help you monitor and display audio
quality feedback to users during recording sessions.

## Available Metrics

The SDK provides the following audio quality metrics through the `VoiceActivityData` model:

### 1. **Signal-to-Noise Ratio (SNR)**

- **Field**: `signalToNoiseRatio: Float`
- **Description**: Ratio of signal power to background noise power in decibels (dB)
- **Range**: 0+ dB (higher is better)
- **Recommended Threshold**: > 15 dB for good quality
- **Use Case**: Detect background noise interference

### 2. **Clipping Detection**

- **Field**: `clippingDetected: Boolean`
- **Description**: Indicates if audio distortion (clipping) is detected
- **Values**: `true` if clipping detected, `false` otherwise
- **Use Case**: Alert users to move microphone away or reduce volume

### 3. **RMS Level (Root Mean Square)**

- **Field**: `rmsLevel: Float`
- **Description**: Average signal power/loudness
- **Range**: 0.0 to 1.0 (normalized)
- **Optimal Range**: 0.05 - 0.5
- **Use Case**: Monitor average audio levels

### 4. **Peak Level**

- **Field**: `peakLevel: Float`
- **Description**: Maximum amplitude in the current frame
- **Range**: 0.0 to 1.0 (normalized)
- **Recommended Max**: < 0.95 to avoid clipping
- **Use Case**: Detect volume spikes

### 5. **Zero Crossing Rate (ZCR)**

- **Field**: `zeroCrossingRate: Float`
- **Description**: Rate at which signal changes sign (useful for voice/noise distinction)
- **Range**: 0.0 to 1.0
- **Typical Speech Range**: 0.01 - 0.1
- **Use Case**: Distinguish between speech and noise

### 6. **Amplitude** (existing)

- **Field**: `amplitude: Float`
- **Description**: Current audio amplitude
- **Range**: 0.0 to 1.0
- **Optimal Range**: 0.1 - 0.8

### 7. **Speech Detection** (existing)

- **Field**: `isSpeech: Boolean`
- **Description**: Voice Activity Detection (VAD) result
- **Values**: `true` if speech detected, `false` if silence

## Usage Example

### Basic Implementation

```kotlin
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RecordingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Subscribe to voice activity flow
        lifecycleScope.launch {
            Voice2Rx.getVoiceActivityFlow()?.collect { data ->
                updateAudioQualityUI(data)
            }
        }
    }

    private fun updateAudioQualityUI(data: VoiceActivityData) {
        // Display audio quality metrics
        binding.apply {
            // Show SNR
            snrText.text = "SNR: ${String.format("%.1f", data.signalToNoiseRatio)} dB"

            // Show RMS level
            rmsLevel.text = "Volume: ${String.format("%.2f", data.rmsLevel)}"

            // Show peak level
            peakIndicator.progress = (data.peakLevel * 100).toInt()

            // Speech indicator
            speechIndicator.isActivated = data.isSpeech

            // Show warnings
            if (data.clippingDetected) {
                showWarning("⚠️ Audio clipping detected - move microphone away")
            }

            if (data.signalToNoiseRatio < 10f && data.signalToNoiseRatio > 0f) {
                showWarning("⚠️ High background noise - find a quieter location")
            }

            if (data.rmsLevel < 0.05f && data.isSpeech) {
                showWarning("⚠️ Low volume - speak louder or move closer to microphone")
            }
        }
    }

    private fun showWarning(message: String) {
        // Display warning to user
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
```

### Advanced: Audio Quality Scoring

```kotlin
class AudioQualityMonitor {

    fun calculateQualityScore(data: VoiceActivityData): AudioQuality {
        var score = 100
        var issues = mutableListOf<String>()

        // Check SNR
        when {
            data.signalToNoiseRatio < 5f -> {
                score -= 40
                issues.add("Very high background noise")
            }
            data.signalToNoiseRatio < 10f -> {
                score -= 20
                issues.add("High background noise")
            }
            data.signalToNoiseRatio < 15f -> {
                score -= 10
                issues.add("Moderate background noise")
            }
        }

        // Check clipping
        if (data.clippingDetected) {
            score -= 30
            issues.add("Audio distortion detected")
        }

        // Check RMS level
        when {
            data.rmsLevel < 0.05f -> {
                score -= 20
                issues.add("Volume too low")
            }
            data.rmsLevel > 0.8f -> {
                score -= 15
                issues.add("Volume too high")
            }
        }

        // Check peak level
        if (data.peakLevel > 0.95f) {
            score -= 20
            issues.add("Peak level too high")
        }

        return AudioQuality(
            score = score.coerceIn(0, 100),
            level = when {
                score >= 80 -> QualityLevel.EXCELLENT
                score >= 60 -> QualityLevel.GOOD
                score >= 40 -> QualityLevel.FAIR
                else -> QualityLevel.POOR
            },
            issues = issues
        )
    }
}

data class AudioQuality(
    val score: Int,
    val level: QualityLevel,
    val issues: List<String>
)

enum class QualityLevel {
    EXCELLENT, GOOD, FAIR, POOR
}
```

### UI Example: Visual Indicators

```kotlin
class AudioQualityIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val snrIndicator: ProgressBar
    private val volumeIndicator: ProgressBar
    private val qualityText: TextView

    init {
        inflate(context, R.layout.audio_quality_indicator, this)
        snrIndicator = findViewById(R.id.snr_indicator)
        volumeIndicator = findViewById(R.id.volume_indicator)
        qualityText = findViewById(R.id.quality_text)
    }

    fun update(data: VoiceActivityData) {
        // Update SNR indicator (0-30 dB scale)
        snrIndicator.progress = (data.signalToNoiseRatio.coerceIn(0f, 30f) / 30f * 100).toInt()

        // Update volume indicator
        volumeIndicator.progress = (data.rmsLevel * 100).toInt()

        // Update quality text with color
        val quality = when {
            data.clippingDetected -> {
                qualityText.setTextColor(Color.RED)
                "Poor - Distortion"
            }
            data.signalToNoiseRatio < 10f -> {
                qualityText.setTextColor(Color.YELLOW)
                "Fair - Noisy"
            }
            data.rmsLevel < 0.05f -> {
                qualityText.setTextColor(Color.YELLOW)
                "Fair - Low Volume"
            }
            else -> {
                qualityText.setTextColor(Color.GREEN)
                "Good"
            }
        }
        qualityText.text = quality
    }
}
```

## Quality Thresholds Reference

| Metric       | Excellent | Good                | Fair                 | Poor            |
|--------------|-----------|---------------------|----------------------|-----------------|
| SNR (dB)     | > 20      | 15-20               | 10-15                | < 10            |
| RMS Level    | 0.1-0.5   | 0.05-0.1 or 0.5-0.7 | 0.02-0.05 or 0.7-0.8 | < 0.02 or > 0.8 |
| Peak Level   | 0.3-0.8   | 0.2-0.3 or 0.8-0.9  | 0.1-0.2 or 0.9-0.95  | < 0.1 or > 0.95 |
| Clipping     | false     | false               | false                | true            |
| ZCR (speech) | 0.01-0.1  | 0.1-0.15            | 0.15-0.2             | > 0.2           |

## Best Practices

1. **Real-time Feedback**: Update UI every 50-100ms for smooth visual feedback
2. **Aggregated Metrics**: Consider averaging metrics over 1-2 seconds for stability
3. **User Guidance**: Provide clear, actionable feedback when quality is poor
4. **Quality History**: Track quality over session for post-recording analysis
5. **Adaptive Thresholds**: Adjust thresholds based on environment and use case

## Session Quality Report

```kotlin
class SessionQualityTracker {
    private val qualityHistory = mutableListOf<VoiceActivityData>()

    fun addSample(data: VoiceActivityData) {
        qualityHistory.add(data)
    }

    fun generateReport(): SessionQualityReport {
        val speechFrames = qualityHistory.filter { it.isSpeech }

        return SessionQualityReport(
            averageSNR = speechFrames.map { it.signalToNoiseRatio }.average().toFloat(),
            clippingPercentage = (qualityHistory.count { it.clippingDetected } /
                                 qualityHistory.size.toFloat()) * 100,
            averageRMS = speechFrames.map { it.rmsLevel }.average().toFloat(),
            speechToSilenceRatio = (speechFrames.size / qualityHistory.size.toFloat()) * 100,
            overallQualityScore = calculateOverallScore()
        )
    }

    private fun calculateOverallScore(): Int {
        // Implement scoring logic
        return 85 // Example
    }
}

data class SessionQualityReport(
    val averageSNR: Float,
    val clippingPercentage: Float,
    val averageRMS: Float,
    val speechToSilenceRatio: Float,
    val overallQualityScore: Int
)
```

## Troubleshooting

### Low SNR

- **Cause**: Background noise (AC, traffic, people talking)
- **Solution**: Move to quieter location or use noise-canceling microphone

### Clipping Detected

- **Cause**: Audio input too loud
- **Solution**: Reduce microphone gain, move mic further away, or speak softer

### Low RMS Level

- **Cause**: User speaking too quietly or microphone too far
- **Solution**: Speak louder or move closer to microphone

### High Zero Crossing Rate

- **Cause**: Noise or non-speech sounds
- **Solution**: Reduce background noise or ensure proper speech input

## API Reference

### VoiceActivityData

```kotlin
data class VoiceActivityData(
    val isSpeech: Boolean = false,
    val amplitude: Float = 0f,
    val timeStamp: Long = 0L,
    val signalToNoiseRatio: Float = 0f,
    val clippingDetected: Boolean = false,
    val rmsLevel: Float = 0f,
    val peakLevel: Float = 0f,
    val zeroCrossingRate: Float = 0f
)
```

### Accessing the Flow

```kotlin
Voice2Rx.getVoiceActivityFlow(): Flow<VoiceActivityData>?
```