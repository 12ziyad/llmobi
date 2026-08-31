// LLMobi <-> llama.cpp bridge.
//
// Deliberately small. The Kotlin side owns all policy (which model, what context
// size, when to stop); this file only knows how to load a GGUF and hand back one
// token at a time.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>

#include "llama.h"

#define TAG "llmobi-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, [] {
        llama_backend_init();
        LOGI("llama backend initialised");
    });
}

struct Session {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    llama_sampler *smpl  = nullptr;

    // completion state
    std::vector<llama_token> pending;   // prompt tokens not yet fed
    int   produced   = 0;
    int   max_tokens = 0;
    bool  running    = false;
    std::atomic<bool> cancel{false};

    ~Session() {
        if (smpl)  llama_sampler_free(smpl);
        if (ctx)   llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

inline Session *as_session(jlong h) { return reinterpret_cast<Session *>(h); }

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string piece(const llama_vocab *vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) {
        // Rare: the piece is longer than the stack buffer.
        std::vector<char> big(-n + 1);
        n = llama_token_to_piece(vocab, tok, big.data(), (int32_t) big.size(), 0, true);
        if (n < 0) return {};
        return std::string(big.data(), n);
    }
    return std::string(buf, n);
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeInit(JNIEnv *, jobject) {
    ensure_backend();
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    ensure_backend();
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject, jstring jpath, jint contextSize, jint threads) {
    ensure_backend();

    const std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    // No GPU offload for v1. Mobile Vulkan drivers vary far too much to trust
    // silently, and a wrong guess here is a hard crash rather than a slow reply.
    mp.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("failed to load model: %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx     = (uint32_t) contextSize;
    cp.n_batch   = 256;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *s = new Session();
    s->model = model;
    s->ctx   = ctx;

    LOGI("loaded %s (ctx=%d, threads=%d)", path.c_str(), contextSize, threads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    Session *s = as_session(handle);
    if (!s) return;
    delete s;
    LOGI("model freed");
}

JNIEXPORT jboolean JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeStartCompletion(
        JNIEnv *env, jobject, jlong handle, jstring jprompt, jint maxTokens, jfloat temperature) {
    Session *s = as_session(handle);
    if (!s || !s->ctx) return JNI_FALSE;

    const std::string prompt = jstr(env, jprompt);
    const llama_vocab *vocab = llama_model_get_vocab(s->model);

    // Each turn starts from a clean slate. Simple, and it keeps peak memory
    // predictable on a phone - which matters more here than prefix reuse.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    int32_t n_max = (int32_t) prompt.size() + 64;
    std::vector<llama_token> toks(n_max);
    int32_t n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                               toks.data(), n_max, true, true);
    if (n < 0) {
        toks.resize(-n);
        n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                           toks.data(), (int32_t) toks.size(), true, true);
    }
    if (n <= 0) {
        LOGE("tokenize failed");
        return JNI_FALSE;
    }
    toks.resize(n);

    // Never let the prompt fill the whole window - leave room to answer.
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    const int keep  = n_ctx - maxTokens - 8;
    if (keep > 0 && (int) toks.size() > keep) {
        toks.erase(toks.begin(), toks.end() - keep);
    }

    // Feed the prompt in batches the context can actually take.
    const int n_batch = 256;
    for (size_t i = 0; i < toks.size(); i += n_batch) {
        const int chunk = (int) std::min((size_t) n_batch, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, chunk);
        if (llama_decode(s->ctx, b) != 0) {
            LOGE("prompt decode failed at %zu", i);
            return JNI_FALSE;
        }
    }

    if (s->smpl) llama_sampler_free(s->smpl);
    auto sp = llama_sampler_chain_default_params();
    s->smpl = llama_sampler_chain_init(sp);
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(s->smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(s->smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(s->smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(s->smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(s->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    s->produced   = 0;
    s->max_tokens = maxTokens;
    s->running    = true;
    s->cancel.store(false);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeNextToken(JNIEnv *env, jobject, jlong handle) {
    Session *s = as_session(handle);
    if (!s || !s->running || s->cancel.load()) return nullptr;
    if (s->produced >= s->max_tokens) { s->running = false; return nullptr; }

    const llama_vocab *vocab = llama_model_get_vocab(s->model);

    llama_token tok = llama_sampler_sample(s->smpl, s->ctx, -1);
    if (llama_vocab_is_eog(vocab, tok)) {
        s->running = false;
        return nullptr;
    }
    llama_sampler_accept(s->smpl, tok);

    const std::string text = piece(vocab, tok);

    llama_batch b = llama_batch_get_one(&tok, 1);
    if (llama_decode(s->ctx, b) != 0) {
        // Usually means the context filled up. Stop cleanly rather than crash.
        s->running = false;
        return nullptr;
    }

    s->produced++;
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeStop(JNIEnv *, jobject, jlong handle) {
    Session *s = as_session(handle);
    if (!s) return;
    s->cancel.store(true);
    s->running = false;
}

} // extern "C"
