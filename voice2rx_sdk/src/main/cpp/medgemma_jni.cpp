#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"
#include "common.h"

#define TAG "MedGemmaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static llama_sampler *g_sampler = nullptr;

static void log_callback(enum ggml_log_level level, const char *text, void * /*user_data*/) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
    } else {
        LOGI("%s", text);
    }
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_eka_voice2rx_1sdk_audio_llm_LlamaCppBridge_backendInit(JNIEnv * /*env*/,
                                                                jobject /*thiz*/) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    LOGI("llama backend initialized");
}

JNIEXPORT void JNICALL
Java_com_eka_voice2rx_1sdk_audio_llm_LlamaCppBridge_backendFree(JNIEnv * /*env*/,
                                                                jobject /*thiz*/) {
    llama_backend_free();
    LOGI("llama backend freed");
}

JNIEXPORT jboolean JNICALL
Java_com_eka_voice2rx_1sdk_audio_llm_LlamaCppBridge_loadModel(
        JNIEnv *env, jobject /*thiz*/,
        jstring jModelPath,
        jint nCtx,
        jint nThreads,
        jint nGpuLayers) {

    const char *model_path = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("Loading model from: %s", model_path);

    // Load model
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;

    g_model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jModelPath, model_path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }
    LOGI("Model loaded successfully");

    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = nThreads;

    g_context = llama_init_from_model(g_model, ctx_params);
    if (!g_context) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    LOGI("Context created successfully (n_ctx=%d, n_threads=%d)", nCtx, nThreads);

    // Create sampler chain: temp -> top_k -> top_p -> dist
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    LOGI("Sampler chain initialized");

    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_eka_voice2rx_1sdk_audio_llm_LlamaCppBridge_generateCompletion(
        JNIEnv *env, jobject /*thiz*/,
        jstring jPrompt,
        jint maxTokens) {

    if (!g_model || !g_context || !g_sampler) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    const char *prompt_cstr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(jPrompt, prompt_cstr);

    LOGI("Generating completion for prompt of length %zu, max_tokens=%d",
         prompt.size(), maxTokens);

    // Tokenize the prompt
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    const int n_prompt_max = prompt.size() + 128;
    std::vector<llama_token> tokens(n_prompt_max);
    const int n_prompt = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                        tokens.data(), n_prompt_max, true, true);
    if (n_prompt < 0) {
        LOGE("Tokenization failed: %d", n_prompt);
        return env->NewStringUTF("");
    }
    tokens.resize(n_prompt);
    LOGI("Tokenized prompt: %d tokens", n_prompt);

    // Clear KV cache
    llama_memory_clear(llama_get_memory(g_context), true);

    // Decode prompt in batches
    llama_batch batch = llama_batch_init(512, 0, 1);
    for (int i = 0; i < n_prompt; i++) {
        common_batch_add(batch, tokens[i], i, {0}, false);
        if (batch.n_tokens >= 512 || i == n_prompt - 1) {
            if (i == n_prompt - 1) {
                batch.logits[batch.n_tokens - 1] = true;
            }
            if (llama_decode(g_context, batch) != 0) {
                LOGE("Decode failed at token %d", i);
                llama_batch_free(batch);
                return env->NewStringUTF("");
            }
            common_batch_clear(batch);
        }
    }
    LOGI("Prompt decoded");

    // Generate tokens
    std::string result;
    int n_generated = 0;
    char piece_buf[128];

    while (n_generated < maxTokens) {
        llama_token new_token = llama_sampler_sample(g_sampler, g_context, -1);
        llama_sampler_accept(g_sampler, new_token);

        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("End of generation token reached");
            break;
        }

        // Convert token to text
        int n_chars = llama_token_to_piece(vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (n_chars > 0) {
            result.append(piece_buf, n_chars);
            std::string piece(piece_buf, n_chars);
            LOGI("Token %d: '%s'", n_generated, piece.c_str());
        }

        // Decode the new token
        common_batch_clear(batch);
        common_batch_add(batch, new_token, n_prompt + n_generated, {0}, true);
        if (llama_decode(g_context, batch) != 0) {
            LOGE("Decode failed at generated token %d", n_generated);
            break;
        }

        n_generated++;
    }

    llama_batch_free(batch);
    LOGI("Generated %d tokens, result length: %zu", n_generated, result.size());

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_eka_voice2rx_1sdk_audio_llm_LlamaCppBridge_unload(JNIEnv * /*env*/, jobject /*thiz*/) {
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    LOGI("Model unloaded");
}

} // extern "C"
