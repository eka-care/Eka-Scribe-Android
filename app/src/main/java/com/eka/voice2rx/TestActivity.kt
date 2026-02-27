package com.eka.voice2rx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eka.networking.client.NetworkConfig
import com.eka.networking.token.TokenStorage
import com.eka.scribesdk.api.EkaScribe
import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.EkaScribeConfig
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionResult
import com.eka.scribesdk.api.models.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TestActivity : ComponentActivity() {
    companion object {
        const val TAG = "TestActivity"
        var TEST_ACCESS_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJkb2Mtd2ViIiwiYi1pZCI6IjcxNzU2MjAyNDU0ODM3NzciLCJjYyI6eyJlc2MiOjAsInBleCI6MTgwMTUyNjQwMCwicHNuIjoiRCIsInBzdCI6InRydWUiLCJzdHkiOiJwIn0sImRvYiI6IjIwMjUtMDgtMjUiLCJleHAiOjE3NzIxNzkwNzIsImZuIjoiSW10aXlheiIsImdlbiI6Ik0iLCJpYXQiOjE3NzIxNzcyNzIsImlkcCI6Im1vYiIsImlzcyI6ImVtci5la2EuY2FyZSIsImp0aSI6ImNjYTE5ZWFhLTBmYTEtNGE1Yi1iMDJhLWY5M2U1ZDYxMTNhZSIsImxuIjoibSwiLCJvaWQiOiIxNzU2MjAyNDU1MDI4NDIiLCJwcmkiOnRydWUsInBzIjoiRCIsInIiOiJJTiIsInMiOiJEciIsInV1aWQiOiI2NDIyYmRjMi0yMTgyLTRhMTMtYjYyMC0wNDdmNTBjZDQyZTgiLCJ3LWlkIjoiNzE3NTYyMDI0NTQ4Mzc3NyIsInctbiI6IkltdGl5YXoifQ.-vaPsA8I1XOX-OPhVfxPtHIDCBvyYlsrtj0HkaTbdo0"
        const val TEST_REFRESH_TOKEN = "0343d03f558045108c3ec13aad7e2f72"
    }

    private val currentSessionId = mutableStateOf("")
    private val currentState = mutableStateOf(SessionState.IDLE)
    private val errorMessage = mutableStateOf("")
    private val chunkCount = mutableIntStateOf(0)
    private val voiceActivity = mutableStateOf("No activity")
    private val resultInfo = mutableStateOf("")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showToast("Permission granted")
        } else {
            showToast("Permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize the new EkaScribe SDK
        EkaScribe.init(
            config = EkaScribeConfig(
                networkConfig = NetworkConfig(
                    tokenStorage = MyTokenStorage(),
                    appId = "scribe-android",
                    appVersionCode = 1,
                    appVersionName = "1.0.0",
                    apiCallTimeOutInSec = 30,
                    isDebugApp = true,
                    baseUrl = "https://api.eka.care/",
                    headers = mapOf()
                ),
                debugMode = true
            ),
            context = this,
            callback = MyScribeCallback()
        )

        setContent {
            MaterialTheme {
                TestScreen(
                    sessionId = currentSessionId.value,
                    sessionState = currentState.value,
                    errorMsg = errorMessage.value,
                    chunks = chunkCount.intValue,
                    voiceActivity = voiceActivity.value,
                    resultInfo = resultInfo.value,
                    onStartClick = { startRecording() },
                    onPauseClick = { pauseRecording() },
                    onResumeClick = { resumeRecording() },
                    onStopClick = { stopRecording() },
                    checkPermission = { checkAndRequestPermission() }
                )
            }
        }
    }

    private fun checkAndRequestPermission(): Boolean {
        return when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> true

            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                false
            }
        }
    }

    private fun startRecording() {
        if (!checkAndRequestPermission()) {
            showToast("Audio permission required")
            return
        }

        try {
            errorMessage.value = ""
            resultInfo.value = ""
            val sessionConfig = SessionConfig(
                languages = listOf("en-IN"),
                mode = "dictation",
                modelType = "pro"
            )
            val sessionInfo = EkaScribe.startSession(sessionConfig)
            currentSessionId.value = sessionInfo.sessionId
            currentState.value = sessionInfo.state
            Log.d(TAG, "Session starting: ${sessionInfo.sessionId}")
            showToast("Session starting")

            // Collect voice activity updates
            CoroutineScope(Dispatchers.Main).launch {
                EkaScribe.getVoiceActivity().collect { data ->
                    voiceActivity.value = if (data.isSpeech) {
                        "Speaking (${String.format("%.2f", data.amplitude)})"
                    } else {
                        "Silent (${String.format("%.2f", data.amplitude)})"
                    }
                }
            }

            // Collect session state updates
            CoroutineScope(Dispatchers.Main).launch {
                EkaScribe.getSessionState().collect { state ->
                    currentState.value = state
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            showToast("Error: ${e.message}")
        }
    }

    private fun pauseRecording() {
        try {
            EkaScribe.pauseSession()
            showToast("Session paused")
        } catch (e: Exception) {
            showToast("Error: ${e.message}")
        }
    }

    private fun resumeRecording() {
        try {
            EkaScribe.resumeSession()
            showToast("Session resumed")
        } catch (e: Exception) {
            showToast("Error: ${e.message}")
        }
    }

    private fun stopRecording() {
        try {
            EkaScribe.stopSession()
            showToast("Session stopping")
        } catch (e: Exception) {
            showToast("Error: ${e.message}")
        }
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        EkaScribe.destroy()
    }

    // --- EkaScribe callback ---

    private inner class MyScribeCallback : EkaScribeCallback {
        override fun onSessionStarted(sessionId: String) {
            Log.d(TAG, "Session started: $sessionId")
            showToast("Recording started")
        }

        override fun onSessionPaused(sessionId: String) {
            Log.d(TAG, "Session paused: $sessionId")
        }

        override fun onSessionResumed(sessionId: String) {
            Log.d(TAG, "Session resumed: $sessionId")
        }

        override fun onSessionStopped(sessionId: String, chunkCount: Int) {
            Log.d(TAG, "Session stopped: $sessionId, chunks=$chunkCount")
            this@TestActivity.chunkCount.intValue = chunkCount
            showToast("Stopped. Chunks: $chunkCount")
        }

        override fun onError(error: ScribeError) {
            Log.e(TAG, "Error: ${error.code} - ${error.message}")
            errorMessage.value = error.message
            showToast("Error: ${error.message}")
        }

        override fun onSessionCompleted(sessionId: String, result: SessionResult) {
            Log.d(TAG, "Session completed: $sessionId")
            val outputCount = result.templates.size
            resultInfo.value = "Completed! Outputs: $outputCount"
            showToast("Session completed with $outputCount outputs")
        }

        override fun onSessionFailed(sessionId: String, error: ScribeError) {
            Log.e(TAG, "Session failed: $sessionId - ${error.code}: ${error.message}")
            errorMessage.value = "Failed: ${error.message}"
            showToast("Session failed: ${error.message}")
        }
    }
}

// --- Composable UI ---

@Composable
fun TestScreen(
    sessionId: String,
    sessionState: SessionState,
    errorMsg: String,
    chunks: Int,
    voiceActivity: String,
    resultInfo: String,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit,
    checkPermission: () -> Boolean
) {
    val isRecording = sessionState == SessionState.RECORDING
    val canStop = sessionState == SessionState.RECORDING || sessionState == SessionState.PAUSED
    val canStart =
        sessionState == SessionState.IDLE || sessionState == SessionState.COMPLETED || sessionState == SessionState.ERROR

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                text = "EkaScribe SDK Test",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (sessionState) {
                        SessionState.RECORDING -> Color(0xFFE8F5E9)
                        SessionState.PAUSED -> Color(0xFFFFF3E0)
                        SessionState.PROCESSING -> Color(0xFFE1F5FE)
                        SessionState.ERROR -> Color(0xFFFFEBEE)
                        SessionState.COMPLETED -> Color(0xFFE8F5E9)
                        else -> Color(0xFFF5F5F5)
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "State: $sessionState",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (sessionId.isNotEmpty()) {
                        Text(
                            text = "Session: $sessionId",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Text(
                        text = "Voice: $voiceActivity",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (chunks > 0) {
                        Text(
                            text = "Chunks uploaded: $chunks",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (resultInfo.isNotEmpty()) {
                        Text(
                            text = resultInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
            }

            // Error Card
            if (errorMsg.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEE)
                    )
                ) {
                    Text(
                        text = "Error: $errorMsg",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Red,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // START
            Button(
                onClick = {
                    if (checkPermission()) {
                        onStartClick()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                ),
                enabled = canStart
            ) {
                Text("START RECORDING", style = MaterialTheme.typography.titleMedium)
            }

            // PAUSE / RESUME
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPauseClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9800)
                    ),
                    enabled = isRecording
                ) {
                    Text("PAUSE")
                }

                Button(
                    onClick = onResumeClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    ),
                    enabled = sessionState == SessionState.PAUSED
                ) {
                    Text("RESUME")
                }
            }

            // STOP
            Button(
                onClick = onStopClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF44336)
                ),
                enabled = canStop
            ) {
                Text("STOP RECORDING", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "SDK Configuration:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "- Sample Rate: 16000 Hz",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "- Chunk Duration: 10-25s (VAD)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "- Audio Analyser: Enabled",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "- Upload: Direct (coroutine + TransferUtility)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

class MyTokenStorage : TokenStorage {
    override fun getAccessToken(): String {
        return TestActivity.TEST_ACCESS_TOKEN
    }

    override fun getRefreshToken(): String {
        return TestActivity.TEST_REFRESH_TOKEN
    }

    override fun saveTokens(accessToken: String, refreshToken: String) {
        TestActivity.TEST_ACCESS_TOKEN = accessToken
    }

    override fun onSessionExpired() {
        // Handle session expiry
    }
}
