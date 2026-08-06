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

/**
 * Bytes at the end of [s] that are the start of a UTF-8 sequence but not all of it.
 *
 * A token boundary is not a character boundary — "ö" or "→" arrive split across two tokens.
 * Handing such a fragment to NewStringUTF produces garbage (and the coach answers in German),
 * so a partial tail is held back until the next token completes it.
 */
size_t incomplete_utf8_tail(const std::string& s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;                 // continuation byte, keep looking
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return need > back ? back : 0;
    }
    return 0;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_chessanalysis_engine_LlamaRunner_nativeLoad(
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
Java_com_example_chessanalysis_engine_LlamaRunner_nativeGenerate(
        JNIEnv* env, jobject, jlong handle, jstring jprompt, jint maxTokens,
        jint timeoutMs, jobject sink) {
    auto* h = reinterpret_cast<LlamaCtx*>(handle);
    if (!h) return env->NewStringUTF("");

    // Optional token sink: the caller sees the answer grow instead of waiting for the whole thing.
    jmethodID onToken = nullptr;
    if (sink != nullptr) {
        jclass sinkCls = env->GetObjectClass(sink);
        onToken = env->GetMethodID(sinkCls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(sinkCls);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

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

    // J5: time budget + t/s measurement. The budget only STOPS the generation — whatever was
    // produced until then is returned and used; nothing is thrown away (see LlamaRunner docs).
    auto start = std::chrono::steady_clock::now();
    int generated = 0;
    bool timed_out = false;
    const long timeout_ms = timeoutMs > 0 ? (long) timeoutMs : 10000L;

    // Prefill (reading the prompt) and decode (writing the answer) are separate costs and must be
    // reported separately — a long prompt otherwise drags the reported speed towards zero and looks
    // like a hung model.
    long prefill_ms = 0;

    std::string out;
    std::string pending;   // bytes not yet handed to the sink (incomplete UTF-8 tail)
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    for (int i = 0; i < maxTokens; ++i) {
        if (llama_decode(h->ctx, batch) != 0) { LOGE("decode failed"); break; }
        if (i == 0) {
            prefill_ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - start).count();
        }
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        // J5: EOG stop — Gemma emits <end_of_turn> as an EOG token; llama_vocab_is_eog covers it.
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        char buf[256];
        int m = llama_token_to_piece(h->vocab, tok, buf, sizeof(buf), 0, true);
        if (m > 0) {
            out.append(buf, m);
            if (onToken != nullptr) {
                pending.append(buf, m);
                const size_t tail = incomplete_utf8_tail(pending);
                if (pending.size() > tail) {
                    const std::string chunk = pending.substr(0, pending.size() - tail);
                    pending.erase(0, pending.size() - tail);
                    jstring js = env->NewStringUTF(chunk.c_str());
                    env->CallVoidMethod(sink, onToken, js);
                    env->DeleteLocalRef(js);
                    // A throwing callback must not abort generation — the text is still valid.
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
            }
        }
        generated++;
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start).count();
        if (elapsed > timeout_ms) { timed_out = true; break; }
        batch = llama_batch_get_one(&tok, 1);
    }

    auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
    // Decode speed = what the user watches being written. Prefill is a one-off before the first word.
    const long decode_ms = (long) elapsed_ms - prefill_ms;
    double tps = (generated > 1 && decode_ms > 0) ? (generated - 1) * 1000.0 / decode_ms : 0.0;
    const double prefill_tps = (prefill_ms > 0) ? nPrompt * 1000.0 / prefill_ms : 0.0;
    LOGI("prompt %d tokens · prefill %ldms (%.1f tok/s) · decode %d tokens in %ldms (%.1f t/s)%s",
         nPrompt, prefill_ms, prefill_tps, generated, decode_ms, tps,
         timed_out ? " [TIMEOUT]" : "");

    if (timed_out) out += "\n[TIMEOUT]";
    char tps_buf[32];
    snprintf(tps_buf, sizeof(tps_buf), "\n[TPS:%.1f]", tps);
    out += tps_buf;

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_engine_LlamaRunner_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<LlamaCtx*>(handle);
    if (!h) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}
