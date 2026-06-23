// AI Coach on-device runner — thin JNI bridge over llama.cpp (GGUF).
// Only compiled when -DLLAMA_ENABLED=ON (see CMakeLists.txt). Targets the pinned llama.cpp tag
// fetched by the Gradle `fetchLlama` task. Synchronous; call from a background thread in Kotlin.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <chrono>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// J1: Q3_K_S quantisation testing — skip; model file is chosen by the user at runtime.

namespace {
struct LlamaCtx {
    llama_model*        model = nullptr;
    llama_context*      ctx   = nullptr;
    const llama_vocab*  vocab = nullptr;
    // J2: KV-cache prefix reuse — skipped.
    // llama_kv_cache_seq_cp / llama_kv_cache_seq_rm require careful seq-id management
    // and differ across llama.cpp versions. With nCtx=512 and maxTokens=32 the prompt
    // re-encode cost is negligible; implement if benchmarks show otherwise.
};

bool g_backend_ready = false;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_chessanalysis_LlamaRunner_nativeLoad(
        JNIEnv* env, jobject, jstring jpath, jint nCtx, jint nThreads) {
    if (!g_backend_ready) { llama_backend_init(); g_backend_ready = true; }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU-only on device
    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("model load failed"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx          = (uint32_t) nCtx;
    cp.n_threads      = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }

    auto* h = new LlamaCtx{ model, ctx, llama_model_get_vocab(model) };
    LOGI("model loaded (n_ctx=%d, threads=%d)", nCtx, nThreads);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_chessanalysis_LlamaRunner_nativeGenerate(
        JNIEnv* env, jobject, jlong handle, jstring jprompt, jint maxTokens) {
    auto* h = reinterpret_cast<LlamaCtx*>(handle);
    if (!h) return env->NewStringUTF("");

    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    const int promptLen = (int) strlen(prompt);

    // tokenize (negative return = required size)
    int nPrompt = -llama_tokenize(h->vocab, prompt, promptLen, nullptr, 0, true, true);
    std::vector<llama_token> tokens(nPrompt);
    llama_tokenize(h->vocab, prompt, promptLen, tokens.data(), (int32_t) tokens.size(), true, true);
    env->ReleaseStringUTFChars(jprompt, prompt);

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // J5: timeout + t/s measurement
    auto start = std::chrono::steady_clock::now();
    int generated = 0;
    bool timed_out = false;
    static constexpr long TIMEOUT_MS = 6000;

    std::string out;
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    for (int i = 0; i < maxTokens; ++i) {
        if (llama_decode(h->ctx, batch) != 0) { LOGE("decode failed"); break; }
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        // J5: EOG stop — Gemma emits <end_of_turn> as an EOG token; llama_vocab_is_eog covers it.
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        char buf[256];
        int m = llama_token_to_piece(h->vocab, tok, buf, sizeof(buf), 0, true);
        if (m > 0) out.append(buf, m);
        generated++;
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start).count();
        if (elapsed > TIMEOUT_MS) { timed_out = true; break; }
        batch = llama_batch_get_one(&tok, 1);
    }

    auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
    double tps = (generated > 0 && elapsed_ms > 0) ? generated * 1000.0 / elapsed_ms : 0.0;
    LOGI("generated %d tokens in %ldms (%.1f t/s)%s", generated, (long)elapsed_ms, tps,
         timed_out ? " [TIMEOUT]" : "");

    if (timed_out) out += "\n[TIMEOUT]";
    char tps_buf[32];
    snprintf(tps_buf, sizeof(tps_buf), "\n[TPS:%.1f]", tps);
    out += tps_buf;

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_LlamaRunner_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<LlamaCtx*>(handle);
    if (!h) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}
