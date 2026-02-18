package com.eka.voice2rx_sdk.audio.llm

import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger

/**
 * JNI bridge to llama.cpp for on-device GGUF model inference.
 * Provides model loading, text generation, and resource management.
 */
class LlamaCppBridge {

    companion object {
        private const val TAG = "LlamaCppBridge"
        private var isLibraryLoaded = false

        fun ensureLibraryLoaded() {
            if (!isLibraryLoaded) {
                try {
                    System.loadLibrary("medgemma_jni")
                    isLibraryLoaded = true
                    VoiceLogger.d(TAG, "Native library loaded successfully")
                } catch (e: UnsatisfiedLinkError) {
                    VoiceLogger.e(TAG, "Failed to load native library")
                    throw e
                }
            }
        }
    }

    init {
        ensureLibraryLoaded()
    }

    external fun backendInit()
    external fun backendFree()
    external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Boolean
    external fun generateCompletion(prompt: String, maxTokens: Int): String
    external fun unload()
}
