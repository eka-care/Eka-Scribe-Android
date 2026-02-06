package com.eka.voice2rx_sdk.audio.whisper

import android.content.Context
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads and manages Whisper TFLite model files.
 *
 * Supports both bundled assets and remote download.
 * Uses ETag caching for efficient remote updates.
 */
class WhisperModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "WhisperModelDownloader"

        // HuggingFace base URL for TFLite models
        private const val HF_BASE_URL = "https://huggingface.co/cik009/whisper/resolve/main/"

        // TFLite model variants
        const val MODEL_TINY = "whisper-tiny.tflite"           // ~40MB, multilingual
        const val MODEL_TINY_EN = "whisper-tiny.en.tflite"     // ~40MB, English only
        const val MODEL_BASE = "whisper-base.tflite"           // ~78MB, multilingual
        const val MODEL_BASE_EN = "whisper-base.en.tflite"     // ~78MB, English only
        const val MODEL_SMALL = "whisper-small.tflite"         // ~244MB, multilingual
        const val MODEL_SMALL_EN = "whisper-small.en.tflite"   // ~244MB, English only

        // Vocabulary files
        const val VOCAB_MULTILINGUAL = "filters_vocab_multilingual.bin"
        const val VOCAB_ENGLISH = "filters_vocab_en.bin"

        // Default model and vocab (use small for better accuracy)
        const val DEFAULT_MODEL = MODEL_SMALL
        const val DEFAULT_VOCAB = VOCAB_MULTILINGUAL

        private const val ETAG_FILE_SUFFIX = ".etag"
        private const val MODELS_DIR = "whisper_tflite_models"
    }

    private val httpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            VoiceLogger.d(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Get the models directory
     */
    private fun getModelsDir(): File {
        val modelsDir = File(context.filesDir, MODELS_DIR)
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return modelsDir
    }

    /**
     * Get local model file path
     */
    fun getLocalModelFile(modelName: String = DEFAULT_MODEL): File {
        return File(getModelsDir(), modelName)
    }

    /**
     * Get local vocab file path
     */
    fun getLocalVocabFile(vocabName: String = DEFAULT_VOCAB): File {
        return File(getModelsDir(), vocabName)
    }

    /**
     * Check if model exists locally
     */
    fun isModelDownloaded(modelName: String = DEFAULT_MODEL): Boolean {
        val modelFile = getLocalModelFile(modelName)
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * Check if vocab exists locally
     */
    fun isVocabDownloaded(vocabName: String = DEFAULT_VOCAB): Boolean {
        val vocabFile = getLocalVocabFile(vocabName)
        return vocabFile.exists() && vocabFile.length() > 0
    }

    /**
     * Ensure model and vocab are available locally.
     * First tries to copy from assets, then falls back to HuggingFace download.
     *
     * @param modelName Name of the model file
     * @param vocabName Name of the vocabulary file
     * @param onProgress Progress callback (0-100)
     * @return Pair of model path and vocab path
     */
    suspend fun ensureModelFromAssets(
        modelName: String = DEFAULT_MODEL,
        vocabName: String = DEFAULT_VOCAB,
        onProgress: ((Int) -> Unit)? = null
    ): Pair<String, String> {
        val modelFile = getLocalModelFile(modelName)
        val vocabFile = getLocalVocabFile(vocabName)

        // Get model if needed
        if (!modelFile.exists() || modelFile.length() == 0L) {
            if (isAssetAvailable(modelName)) {
                VoiceLogger.d(TAG, "Copying model from assets: $modelName")
                copyAssetToFile(modelName, modelFile) { progress ->
                    onProgress?.invoke(progress / 2)
                }
            } else {
                VoiceLogger.d(TAG, "Downloading model from HuggingFace: $modelName")
                val modelUrl = HF_BASE_URL + modelName
                downloadFile(modelUrl, modelFile) { progress ->
                    onProgress?.invoke(progress / 2)
                }
            }
            VoiceLogger.d(TAG, "Model ready: ${modelFile.absolutePath}")
        } else {
            VoiceLogger.d(TAG, "Model already exists: ${modelFile.absolutePath}")
            onProgress?.invoke(50)
        }

        // Get vocab if needed
        if (!vocabFile.exists() || vocabFile.length() == 0L) {
            if (isAssetAvailable(vocabName)) {
                VoiceLogger.d(TAG, "Copying vocab from assets: $vocabName")
                copyAssetToFile(vocabName, vocabFile) { progress ->
                    onProgress?.invoke(50 + progress / 2)
                }
            } else {
                VoiceLogger.d(TAG, "Downloading vocab from HuggingFace: $vocabName")
                val vocabUrl = HF_BASE_URL + vocabName
                downloadFile(vocabUrl, vocabFile) { progress ->
                    onProgress?.invoke(50 + progress / 2)
                }
            }
            VoiceLogger.d(TAG, "Vocab ready: ${vocabFile.absolutePath}")
        } else {
            VoiceLogger.d(TAG, "Vocab already exists: ${vocabFile.absolutePath}")
            onProgress?.invoke(100)
        }

        return Pair(modelFile.absolutePath, vocabFile.absolutePath)
    }

    /**
     * Check if an asset exists
     */
    private fun isAssetAvailable(assetName: String): Boolean {
        return try {
            context.assets.open(assetName).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Copy asset file to local storage
     */
    private fun copyAssetToFile(
        assetName: String,
        targetFile: File,
        onProgress: ((Int) -> Unit)? = null
    ) {
        try {
            context.assets.open(assetName).use { input ->
                val totalSize = input.available().toLong()
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (totalSize > 0) {
                            val progress = (totalBytesRead * 100 / totalSize).toInt()
                            onProgress?.invoke(progress)
                        }
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error copying asset: $assetName", e)
            throw RuntimeException("Failed to copy asset: $assetName", e)
        }
    }

    /**
     * Download model from remote URL.
     *
     * @param modelUrl Full URL to the model file
     * @param vocabUrl Full URL to the vocabulary file
     * @param onProgress Progress callback (0-100)
     * @return Pair of model path and vocab path
     */
    suspend fun downloadModel(
        modelUrl: String,
        vocabUrl: String,
        modelName: String = DEFAULT_MODEL,
        vocabName: String = DEFAULT_VOCAB,
        onProgress: ((Int) -> Unit)? = null
    ): Pair<String, String> {
        val modelFile = getLocalModelFile(modelName)
        val vocabFile = getLocalVocabFile(vocabName)

        // Download model
        downloadFile(modelUrl, modelFile) { progress ->
            onProgress?.invoke(progress / 2)
        }

        // Download vocab
        downloadFile(vocabUrl, vocabFile) { progress ->
            onProgress?.invoke(50 + progress / 2)
        }

        return Pair(modelFile.absolutePath, vocabFile.absolutePath)
    }

    private fun downloadFile(
        url: String,
        targetFile: File,
        onProgress: ((Int) -> Unit)? = null
    ) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RuntimeException("Download failed: ${response.code}")
        }

        val responseBody = response.body ?: throw RuntimeException("Empty response")
        val contentLength = responseBody.contentLength()

        responseBody.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (contentLength > 0) {
                        onProgress?.invoke((totalBytesRead * 100 / contentLength).toInt())
                    }
                }
                output.flush()
            }
        }
        response.close()
    }

    /**
     * Clear cached models
     */
    fun clearCache() {
        try {
            getModelsDir().listFiles()?.forEach { it.delete() }
            VoiceLogger.d(TAG, "Model cache cleared")
        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error clearing cache", e)
        }
    }

    /**
     * Get model info
     */
    fun getModelInfo(modelName: String = DEFAULT_MODEL): WhisperModelInfo {
        val modelFile = getLocalModelFile(modelName)
        val vocabFile = getLocalVocabFile(
            if (modelName.contains(".en.")) VOCAB_ENGLISH else VOCAB_MULTILINGUAL
        )

        return WhisperModelInfo(
            modelName = modelName,
            modelExists = modelFile.exists(),
            modelSizeBytes = if (modelFile.exists()) modelFile.length() else 0L,
            modelPath = modelFile.absolutePath,
            vocabExists = vocabFile.exists(),
            vocabPath = vocabFile.absolutePath
        )
    }
}

/**
 * Whisper TFLite model information
 */
data class WhisperModelInfo(
    val modelName: String,
    val modelExists: Boolean,
    val modelSizeBytes: Long,
    val modelPath: String,
    val vocabExists: Boolean,
    val vocabPath: String
) {
    val modelSizeMB: Float get() = modelSizeBytes / (1024f * 1024f)
    val isReady: Boolean get() = modelExists && vocabExists
}
