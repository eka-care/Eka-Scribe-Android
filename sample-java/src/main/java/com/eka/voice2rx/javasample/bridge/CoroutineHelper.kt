package com.eka.voice2rx.javasample.bridge

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

/**
 * Thin Kotlin bridge for calling EkaScribe suspend functions and collecting Flows from Java.
 *
 * WHY THIS IS NEEDED:
 * Kotlin suspend functions compile to state machine classes that track suspension points.
 * Plain Java lambdas cannot replicate this — they only handle one suspend/resume cycle,
 * causing "This continuation is already complete" crashes when the SDK internally suspends
 * at multiple points (DB writes, network calls, dispatching).
 *
 * This single file is the only Kotlin source needed. All other app code remains pure Java.
 * For Flutter/Java-only projects: add `apply plugin: 'kotlin-android'` to your build.gradle.
 */
object CoroutineHelper {

    /**
     * Launch startSession() in a proper coroutine.
     */
    @JvmStatic
    fun startSession(
        scope: CoroutineScope,
        context: Context,
        sessionConfig: SessionConfig,
        onStart: (String) -> Unit,
        onError: (ScribeError) -> Unit
    ): Job {
        return scope.launch(Dispatchers.Main) {
            EkaScribe.startSession(
                context = context,
                sessionConfig = sessionConfig,
                onStart = onStart,
                onError = onError
            )
        }
    }

    /**
     * Collect session state Flow in a proper coroutine.
     */
    @JvmStatic
    fun collectSessionState(
        scope: CoroutineScope,
        callback: (SessionState) -> Unit
    ): Job {
        return scope.launch(Dispatchers.Main) {
            EkaScribe.getSessionState().collect { state ->
                callback(state)
            }
        }
    }

    /**
     * Collect voice activity Flow in a proper coroutine.
     */
    @JvmStatic
    fun collectVoiceActivity(
        scope: CoroutineScope,
        callback: (VoiceActivityData) -> Unit
    ): Job {
        return scope.launch(Dispatchers.Main) {
            EkaScribe.getVoiceActivity().collect { data ->
                callback(data)
            }
        }
    }
}
