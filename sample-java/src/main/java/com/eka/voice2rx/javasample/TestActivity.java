package com.eka.voice2rx.javasample;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwnerKt;

import com.eka.networking.client.NetworkConfig;
import com.eka.scribesdk.api.EkaScribe;
import com.eka.scribesdk.api.EkaScribeConfig;
import com.eka.scribesdk.api.models.OutputTemplate;
import com.eka.scribesdk.api.models.ScribeError;
import com.eka.scribesdk.api.models.SectionData;
import com.eka.scribesdk.api.models.SessionConfig;
import com.eka.scribesdk.api.models.SessionResult;
import com.eka.scribesdk.api.models.SessionState;
import com.eka.scribesdk.api.models.TemplateOutput;
import com.eka.voice2rx.javasample.bridge.CoroutineHelper;
import com.eka.voice2rx.javasample.databinding.ActivityTestBinding;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;

/**
 * Java sample activity demonstrating EkaScribe SDK integration.
 * All app logic is Java. A single Kotlin bridge file (CoroutineHelper.kt) handles
 * suspend function calls and Flow collection — this is required because Kotlin suspend
 * functions need compiler-generated state machines that pure Java cannot produce.
 * <p>
 * Key patterns:
 * - EkaScribe is a Kotlin object → access via EkaScribe.INSTANCE
 * - startSession() is a suspend function → called via CoroutineHelper.startSession()
 * - Flow collection (state, voice activity) → via CoroutineHelper.collect*()
 * - pauseSession/resumeSession/stopSession → regular methods, called directly
 * - Session lifecycle events → EkaScribeCallback
 */
public class TestActivity extends AppCompatActivity implements MyScribeCallback.Listener {

    private static final String TAG = "TestActivityJava";
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (isGranted) {
                            showToast("Permission granted");
                        } else {
                            showToast("Permission denied");
                        }
                    }
            );
    private ActivityTestBinding binding;
    // Session state
    private String currentSessionId = "";
    private SessionState currentState = SessionState.IDLE;
    private String errorMessage = "";
    private int chunkCount = 0;
    private String voiceActivityText = "No activity";
    private String resultInfo = "";
    private SessionResult sessionResult = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTestBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initializeSdk();
        setupButtons();
        updateUI();
    }

    // ---- SDK Initialization ----

    private void initializeSdk() {
        // Build NetworkConfig (all params required from Java — no @JvmOverloads)
        // Constructor order: appId, baseUrl, appVersionName, appVersionCode,
        //                    isDebugApp, apiCallTimeOutInSec, headers, tokenStorage
        NetworkConfig networkConfig = new NetworkConfig(
                "scribe-android",
                "https://api.eka.care/",
                "1.0.0",
                1,
                true,
                30L,
                new HashMap<>(),
                new MyTokenStorage()
        );

        // Build EkaScribeConfig (all params required from Java — no @JvmOverloads)
        EkaScribeConfig config = new EkaScribeConfig(
                "client-id",      // clientId
                "android",         // flavour (default)
                true,              // enableAnalyser
                true,              // debugMode
                networkConfig      // networkConfig
        );

        // EkaScribe is a Kotlin object — access via INSTANCE from Java
        EkaScribe.INSTANCE.init(config, this, new MyScribeCallback(this));
    }

    // ---- Recording Controls ----

    /**
     * Start a recording session.
     * Uses CoroutineHelper (Kotlin bridge) for suspend function and Flow collection.
     */
    private void startRecording() {
        if (!checkAndRequestPermission()) {
            showToast("Audio permission required");
            return;
        }

        try {
            errorMessage = "";
            resultInfo = "";
            sessionResult = null;
            updateUI();

            // Build SessionConfig (all 7 params required from Java — no @JvmOverloads)
            SessionConfig sessionConfig = new SessionConfig(
                    Arrays.asList("en-IN"),  // languages
                    "dictation",             // mode
                    "pro",                   // modelType
                    Arrays.asList(new OutputTemplate("19288d2f-81a9-46a6-b804-9651242a9b3e", "custom", "SOAP Notes")),                    // outputTemplates
                    null,                    // patientDetails
                    null,                    // section
                    null                     // speciality
            );

            // Get lifecycle-aware coroutine scope (auto-cancels on destroy)
            CoroutineScope lifecycleScope = LifecycleOwnerKt.getLifecycleScope(this);

            // Start session via Kotlin bridge — handles suspend function properly
            CoroutineHelper.startSession(
                    lifecycleScope,
                    this,
                    sessionConfig,
                    sessionId -> {
                        currentSessionId = sessionId;
                        runOnUiThread(this::updateUI);
                        return Unit.INSTANCE;
                    },
                    error -> {
                        errorMessage = error.getMessage();
                        runOnUiThread(this::updateUI);
                        return Unit.INSTANCE;
                    }
            );

            // Collect voice activity Flow via Kotlin bridge
            CoroutineHelper.collectVoiceActivity(lifecycleScope, data -> {
                String text;
                if (data.isSpeech()) {
                    text = String.format("Speaking (%.2f)", data.getAmplitude());
                } else {
                    text = String.format("Silent (%.2f)", data.getAmplitude());
                }
                runOnUiThread(() -> {
                    voiceActivityText = text;
                    updateUI();
                });
                return Unit.INSTANCE;
            });

            // Collect session state Flow via Kotlin bridge
            CoroutineHelper.collectSessionState(lifecycleScope, state -> {
                runOnUiThread(() -> {
                    currentState = state;
                    updateUI();
                });
                return Unit.INSTANCE;
            });

            showToast("Session starting...");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start session", e);
            showToast("Error: " + e.getMessage());
        }
    }

    /**
     * Pause recording — regular (non-suspend) SDK method, called directly.
     */
    private void pauseRecording() {
        try {
            EkaScribe.INSTANCE.pauseSession();
            showToast("Session paused");
        } catch (Exception e) {
            showToast("Error: " + e.getMessage());
        }
    }

    /**
     * Resume recording — regular (non-suspend) SDK method, called directly.
     */
    private void resumeRecording() {
        try {
            EkaScribe.INSTANCE.resumeSession();
            showToast("Session resumed");
        } catch (Exception e) {
            showToast("Error: " + e.getMessage());
        }
    }

    /**
     * Stop recording — regular (non-suspend) SDK method, called directly.
     */
    private void stopRecording() {
        try {
            EkaScribe.INSTANCE.stopSession();
            showToast("Session stopping");
        } catch (Exception e) {
            showToast("Error: " + e.getMessage());
        }
    }

    // ---- Permission ----

    private boolean checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return false;
        }
    }

    // ---- UI ----

    private void setupButtons() {
        binding.btnStart.setOnClickListener(v -> startRecording());
        binding.btnPause.setOnClickListener(v -> pauseRecording());
        binding.btnResume.setOnClickListener(v -> resumeRecording());
        binding.btnStop.setOnClickListener(v -> stopRecording());
    }

    private void updateUI() {
        // State display
        binding.tvState.setText("State: " + currentState.name());

        // Session ID
        if (!currentSessionId.isEmpty()) {
            binding.tvSessionId.setText("Session: " + currentSessionId);
        } else {
            binding.tvSessionId.setText("");
        }

        // Voice activity
        binding.tvVoiceActivity.setText("Voice: " + voiceActivityText);

        // Chunks
        if (chunkCount > 0) {
            binding.tvChunks.setText("Chunks uploaded: " + chunkCount);
        } else {
            binding.tvChunks.setText("");
        }

        // Result info
        if (!resultInfo.isEmpty()) {
            binding.tvResultInfo.setText(resultInfo);
        } else {
            binding.tvResultInfo.setText("");
        }

        // Error card
        if (!errorMessage.isEmpty()) {
            binding.cardError.setVisibility(View.VISIBLE);
            binding.tvError.setText("Error: " + errorMessage);
        } else {
            binding.cardError.setVisibility(View.GONE);
        }

        // Output card
        if (sessionResult != null && !sessionResult.getTemplates().isEmpty()) {
            binding.cardOutput.setVisibility(View.VISIBLE);
            binding.tvOutputContent.setText(formatOutputs(sessionResult));
        } else {
            binding.cardOutput.setVisibility(View.GONE);
        }

        // Status card background color
        int color;
        switch (currentState) {
            case RECORDING:
                color = 0xFFE8F5E9;
                break;
            case PAUSED:
                color = 0xFFFFF3E0;
                break;
            case PROCESSING:
                color = 0xFFE1F5FE;
                break;
            case ERROR:
                color = 0xFFFFEBEE;
                break;
            case COMPLETED:
                color = 0xFFE8F5E9;
                break;
            default:
                color = 0xFFF5F5F5;
                break;
        }
        binding.cardStatus.setCardBackgroundColor(color);

        // Button enable/disable states
        boolean isRecording = currentState == SessionState.RECORDING;
        boolean canStop = currentState == SessionState.RECORDING
                || currentState == SessionState.PAUSED;
        boolean canStart = currentState == SessionState.IDLE
                || currentState == SessionState.COMPLETED
                || currentState == SessionState.ERROR;

        binding.btnStart.setEnabled(canStart);
        binding.btnPause.setEnabled(isRecording);
        binding.btnResume.setEnabled(currentState == SessionState.PAUSED);
        binding.btnStop.setEnabled(canStop);
    }

    // ---- MyScribeCallback.Listener ----

    @Override
    public void onStarted(String sessionId) {
        runOnUiThread(() -> showToast("Recording started"));
    }

    @Override
    public void onPaused(String sessionId) {
        // State update handled by session state Flow
    }

    @Override
    public void onResumed(String sessionId) {
        // State update handled by session state Flow
    }

    @Override
    public void onStopped(String sessionId, int chunks) {
        chunkCount = chunks;
        runOnUiThread(() -> {
            updateUI();
            showToast("Stopped. Chunks: " + chunks);
        });
    }

    @Override
    public void onError(ScribeError error) {
        errorMessage = error.getMessage();
        runOnUiThread(() -> {
            updateUI();
            showToast("Error: " + error.getMessage());
        });
    }

    @Override
    public void onCompleted(String sessionId, SessionResult result) {
        int outputCount = result.getTemplates().size();
        resultInfo = "Completed! Templates: " + outputCount;
        sessionResult = result;
        runOnUiThread(() -> {
            updateUI();
            showToast("Session completed with " + outputCount + " templates");
        });
    }

    @Override
    public void onFailed(String sessionId, ScribeError error) {
        errorMessage = "Failed: " + error.getMessage();
        runOnUiThread(() -> {
            updateUI();
            showToast("Session failed: " + error.getMessage());
        });
    }

    // ---- Helpers ----

    /**
     * Format SessionResult templates into displayable text.
     * Each TemplateOutput contains sections with title/value pairs.
     */
    private String formatOutputs(SessionResult result) {
        StringBuilder sb = new StringBuilder();
        List<TemplateOutput> templates = result.getTemplates();

        for (int i = 0; i < templates.size(); i++) {
            TemplateOutput template = templates.get(i);

            // Template header
            String name = template.getTitle() != null ? template.getTitle()
                    : template.getName() != null ? template.getName()
                    : "Template " + (i + 1);
            sb.append("--- ").append(name).append(" ---\n");

            // Raw output (if available, e.g. MARKDOWN type)
            if (template.getRawOutput() != null && !template.getRawOutput().isEmpty()) {
                sb.append(template.getRawOutput()).append("\n");
            }

            // Sections
            for (SectionData section : template.getSections()) {
                if (section.getTitle() != null) {
                    sb.append("\n").append(section.getTitle()).append(":\n");
                }
                if (section.getValue() != null) {
                    sb.append(section.getValue()).append("\n");
                }
            }

            if (i < templates.size() - 1) {
                sb.append("\n");
            }
        }

        // Audio quality
        if (result.getAudioQuality() != null) {
            sb.append("\nAudio Quality: ").append(String.format("%.2f", result.getAudioQuality()));
        }

        return sb.toString().trim();
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EkaScribe.INSTANCE.destroy();
    }
}
