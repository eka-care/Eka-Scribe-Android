package com.eka.voice2rx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eka.networking.client.NetworkConfig
import com.eka.networking.token.TokenStorage
import com.eka.voice2rx.TestActivity.Companion.TAG
import com.eka.voice2rx_sdk.common.models.EkaScribeError
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType
import com.eka.voice2rx_sdk.sdkinit.AudioQualityConfig
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx
import com.eka.voice2rx_sdk.sdkinit.Voice2RxInitConfig
import com.eka.voice2rx_sdk.sdkinit.Voice2RxLifecycleCallbacks
import com.eka.voice2rx_sdk.sdkinit.models.Template
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TestActivity : ComponentActivity() {
    companion object {
        const val TAG = "TestActivity"
        var TEST_ACCESS_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJkb2Mtd2ViIiwiYi1pZCI6IjcxNzU2MjAyNDU0ODM3NzciLCJjYyI6eyJlc2MiOjEsInBleCI6MTgwMTUyNjQwMCwicHNuIjoiRCIsInBzdCI6InRydWUiLCJzdHkiOiJwIn0sImRvYiI6IjIwMjUtMDgtMjUiLCJleHAiOjE3NzAzODA1MDUsImZuIjoiSW10aXlheiIsImdlbiI6Ik0iLCJpYXQiOjE3NzAzNzg3MDUsImlkcCI6Im1vYiIsImlzcyI6ImVtci5la2EuY2FyZSIsImp0aSI6ImM3ZDczYWJiLTM0N2UtNGU4Ny05OWJjLWViZDkzNGVhMjJiNSIsImxuIjoibSwiLCJvaWQiOiIxNzU2MjAyNDU1MDI4NDIiLCJwcmkiOnRydWUsInBzIjoiRCIsInIiOiJJTiIsInMiOiJEciIsInV1aWQiOiI2NDIyYmRjMi0yMTgyLTRhMTMtYjYyMC0wNDdmNTBjZDQyZTgiLCJ3LWlkIjoiNzE3NTYyMDI0NTQ4Mzc3NyIsInctbiI6IkltdGl5YXoifQ.HYuhhwxrJ0SoINr4p3BSM2O3dekh95teR22gOyiaxDg"
        const val TEST_REFRESH_TOKEN = "c2b0c66ad1b645a28de76548bd88ba01"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showToast("Permission granted")
        } else {
            showToast("Permission denied")
        }
    }

    fun getBasicHeaders(): HashMap<String, String> {
        val headers = HashMap<String, String>()
        headers["client-id"] = "doc-web"
        headers["os-version"] = Build.VERSION.SDK_INT.toString()
        return headers
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize SDK
        Voice2Rx.init(
            config = Voice2RxInitConfig(
                voice2RxLifecycle = MyLifecycleCallbacks(),
                audioQuality = AudioQualityConfig.DISABLED,
                networkConfig = NetworkConfig(
                    tokenStorage = MyTokenStorage(),
                    appId = "scribe-android",
                    appVersionCode = 1,
                    appVersionName = "1.0.0",
                    apiCallTimeOutInSec = 30,
                    isDebugApp = true,
                    baseUrl = "https://api.eka.care/",
                    headers = getBasicHeaders()
                ),
                debugMode = true
            ),
            context = this
        )

        setContent {
            MaterialTheme {
                TestScreen(
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

        val outputFormats = listOf(
            Template(
                templateId = "19288d2f-81a9-46a6-b804-9651242a9b3e",
                templateName = "SOAP Note"
            )
        )

        val languages = listOf("en-IN")

        Voice2Rx.startVoice2Rx(
            mode = Voice2RxType.DICTATION.value,
            outputFormats = outputFormats,
            languages = languages,
            modelType = "pro",
            onError = { error ->
                val message = error.errorDetails?.message ?: "Unknown error"
                val displayMessage = error.errorDetails?.displayMessage ?: "An error occurred"
                Log.e("TestActivity", "Error: $message")
                showToast("Error: $displayMessage")
            },
            onStart = { sessionId ->
                Log.d("TestActivity", "Recording started: $sessionId")
                showToast("Recording started: $sessionId")
            }
        )
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pauseRecording() {
        try {
            Voice2Rx.pauseVoice2Rx()
            showToast("Recording paused")
        } catch (e: Exception) {
            showToast("Error: ${e.message}")
        }
    }

    private fun resumeRecording() {
        try {
            Voice2Rx.resumeVoice2Rx()
            showToast("Recording resumed")
        } catch (e: Exception) {
            showToast("Error: ${e.message}")
        }
    }

    private fun stopRecording() {
        try {
            Voice2Rx.stopVoice2Rx()
            showToast("Recording stopped")
        } catch (e: Exception) {
            showToast("Error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Voice2Rx.releaseResources()
    }
}

@Composable
fun TestScreen(
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit,
    checkPermission: () -> Boolean
) {
    var sessionId by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var recordedFiles by remember { mutableStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var voiceActivity by remember { mutableStateOf("No activity") }
    var transcript by remember { mutableStateOf("") }

    // Monitor recording state
    LaunchedEffect(Unit) {
        while (true) {
            isRecording = Voice2Rx.isCurrentlyRecording()
            kotlinx.coroutines.delay(500)
        }
    }

    // Monitor voice activity
    LaunchedEffect(Unit) {
        Voice2Rx.getVoiceActivityFlow()?.collect { activity ->
            voiceActivity = if (activity.isSpeech) {
                "Speaking (${String.format("%.2f", activity.amplitude)})"
            } else {
                "Silent (${String.format("%.2f", activity.amplitude)})"
            }
        }
    }

    // Monitor real-time transcript
    LaunchedEffect(Unit) {
        Voice2Rx.getTranscriptFlow()?.collect { text ->
            transcript = text
        }
    }

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
                    containerColor = if (isRecording) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Status: ${if (isRecording) "Recording" else "Not Recording"}",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (sessionId.isNotEmpty()) {
                        Text(
                            text = "Session ID: $sessionId",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Text(
                        text = "Voice Activity: $voiceActivity",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (recordedFiles > 0) {
                        Text(
                            text = "Recorded Files: $recordedFiles",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Real-time Transcript Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF8E1)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Real-time Transcript:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (transcript.isNotEmpty()) transcript else "(No transcript yet)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (transcript.isNotEmpty()) Color.Black else Color.Gray
                    )
                }
            }

            // Error Card (only shown when there's an error)
            if (errorMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEE)
                    )
                ) {
                    Text(
                        text = "Error: $errorMessage",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Red,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control Buttons
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
                enabled = !isRecording
            ) {
                Text("START RECORDING", style = MaterialTheme.typography.titleMedium)
            }

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
                    enabled = isRecording
                ) {
                    Text("RESUME")
                }
            }

            Button(
                onClick = onStopClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF44336)
                ),
                enabled = isRecording
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
                        text = "Test Configuration:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "• Mode: Consultation",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Language: en-IN",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Model: Pro",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Output: Transcript",
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
        // SessionExpired refresh token expired
    }
}

class MyLifecycleCallbacks : Voice2RxLifecycleCallbacks {
    override fun onStartSession(sessionId: String) {
        Log.d("MyLifecycleCallbacks", "Session started: $sessionId")
    }

    override fun onStopSession(sessionId: String, recordedFiles: Int) {
        Log.d("MyLifecycleCallbacks", "Session stopped: $sessionId, Files: $recordedFiles")

        CoroutineScope(Dispatchers.IO).launch {
            Voice2Rx.pollEkaScribeResult(sessionId = sessionId).onSuccess {
                Log.d(TAG, it.templates.toString())
            }.onFailure {
                Log.d(TAG, "error : ${it.message.toString()}")
            }
        }
    }

    override fun onPauseSession(sessionId: String) {
        Log.d("MyLifecycleCallbacks", "Session paused: $sessionId")
    }

    override fun onResumeSession(sessionId: String) {
        Log.d("MyLifecycleCallbacks", "Session resumed: $sessionId")
    }

    override fun onError(error: EkaScribeError) {
        Log.e("MyLifecycleCallbacks", "Error: ${error.errorDetails?.message ?: "Unknown error"}")
    }
}