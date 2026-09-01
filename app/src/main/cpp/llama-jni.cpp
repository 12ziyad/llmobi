// LLMobi <-> llama.cpp bridge.
//
// Deliberately small. The Kotlin side owns all policy (which model, what context
// size, when to stop); this file only knows how to load a GGUF, apply the model's
// own chat template, and hand back one token at a time.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <chrono>

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
        std::vector<char> big(-n + 1);
        n = llama_token_to_piece(vocab, tok, big.data(), (int32_t) big.size(), 0, true);
        if (n < 0) return {};
        return std::string(big.data(), n);
    }
    return std::string(buf, n);
}

/**
 * Formats the conversation using whatever template the GGUF itself carries.
 *
 * This matters far more than it looks. Without it the model is not being asked a
 * question at all - it is being handed a piece of text to continue, so it never
 * emits its end-of-turn token and rambles until it hits the token cap. Getting
 * this right is the difference between a two second answer and a thirty second one.
 */
std::string build_prompt(Session *s,
                         const std::vector<std::string> &roles,
                         const std::vector<std::string> &contents) {
    const char *tmpl = llama_model_chat_template(s->model, nullptr);

    if (tmpl) {
        std::vector<llama_chat_message> msgs;
        msgs.reserve(roles.size());
        for (size_t i = 0; i < roles.size(); i++) {
            msgs.push_back({roles[i].c_str(), contents[i].c_str()});
        }

        size_t needed = 512;
        for (const auto &c : contents) needed += c.size() * 2 + 64;

        std::vector<char> buf(needed);
        int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                              buf.data(), (int32_t) buf.size());
        if (n > (int32_t) buf.size()) {
            buf.resize(n + 1);
            n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                          buf.data(), (int32_t) buf.size());
        }
        if (n > 0) return std::string(buf.data(), n);
        LOGE("chat template failed (%d), falling back to plain format", n);
    } else {
        LOGI("model has no chat template, using plain format");
    }

    // Fallback for the rare GGUF with no template baked in.
    std::string out;
    for (size_t i = 0; i < roles.size(); i++) {
        if (roles[i] == "system")      out += contents[i] + "\n\n";
        else if (roles[i] == "user")   out += "User: " + contents[i] + "\n";
        else                            out += "Assistant: " + contents[i] + "\n";
    }
    out += "Assistant:";
    return out;
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

    LOGI("loaded %s (ctx=%d, threads=%d, template=%s)",
         path.c_str(), contextSize, threads,
         llama_model_chat_template(model, nullptr) ? "yes" : "none");
    return reinterpret_cast<jlong>(s);
}

/**
 * Forces the weights and compute buffers into memory with one throwaway decode.
 *
 * llama.cpp mmaps the file, so loading returns almost instantly and the real
 * cost lands on the first decode - which is whichever message the user happens
 * to send first. Paying it here instead means the wait happens under a screen
 * that says "waking up", not under one that looks frozen.
 */
JNIEXPORT void JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeWarmUp(JNIEnv *, jobject, jlong handle) {
    Session *s = as_session(handle);
    if (!s || !s->ctx) return;
    const llama_vocab *vocab = llama_model_get_vocab(s->model);
    llama_token bos = llama_vocab_bos(vocab);
    if (bos < 0) bos = 0;
    llama_batch b = llama_batch_get_one(&bos, 1);
    llama_decode(s->ctx, b);
    llama_memory_clear(llama_get_memory(s->ctx), true);
    LOGI("warm-up decode done");
}

JNIEXPORT void JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    Session *s = as_session(handle);
    if (!s) return;
    delete s;
    LOGI("model freed");
}

/**
 * Starts a completion from a structured conversation.
 *
 * roles[i] is one of "system" | "user" | "assistant"; contents[i] is that turn's
 * text. Passing the turns separately (rather than one pre-joined string) is what
 * lets the model's own template be applied correctly.
 */
JNIEXPORT jboolean JNICALL
Java_app_llmobi_engine_LlamaBridge_nativeStartChat(
        JNIEnv *env, jobject, jlong handle,
        jobjectArray jroles, jobjectArray jcontents,
        jint maxTokens, jfloat temperature) {

    Session *s = as_session(handle);
    if (!s || !s->ctx) return JNI_FALSE;

    const jsize n_msg = env->GetArrayLength(jroles);
    if (n_msg <= 0 || env->GetArrayLength(jcontents) != n_msg) return JNI_FALSE;

    std::vector<std::string> roles, contents;
    roles.reserve(n_msg);
    contents.reserve(n_msg);
    for (jsize i = 0; i < n_msg; i++) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles.push_back(jstr(env, r));
        contents.push_back(jstr(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }

    using clk = std::chrono::steady_clock;
    auto mark = clk::now();
    auto lap = [&mark]() {
        auto now = clk::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - mark).count();
        mark = now;
        return (long) ms;
    };

    const std::string prompt = build_prompt(s, roles, contents);
    const long t_template = lap();
    const llama_vocab *vocab = llama_model_get_vocab(s->model);

    // Each turn starts from a clean slate. Simple, and it keeps peak memory
    // predictable on a phone - which matters more here than prefix reuse.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    std::vector<llama_token> toks(prompt.size() + 64);
    // parse_special must stay true: the template's markers have to become real
    // special tokens, not literal angle brackets the model has never seen.
    int32_t n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                               toks.data(), (int32_t) toks.size(), true, true);
    if (n < 0) {
        toks.resize(-n);
        n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                           toks.data(), (int32_t) toks.size(), true, true);
    }
    if (n <= 0) {
        LOGE("tokenize failed (%d)", n);
        return JNI_FALSE;
    }
    toks.resize(n);
    const long t_tokenize = lap();

    // Never let the prompt fill the whole window - leave room to answer.
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    const int keep  = n_ctx - maxTokens - 8;
    if (keep > 0 && (int) toks.size() > keep) {
        toks.erase(toks.begin(), toks.end() - keep);
    }

    const int n_batch = 256;
    for (size_t i = 0; i < toks.size(); i += n_batch) {
        const int chunk = (int) std::min((size_t) n_batch, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, chunk);
        if (llama_decode(s->ctx, b) != 0) {
            LOGE("prompt decode failed at %zu", i);
            return JNI_FALSE;
        }
    }

    const long t_decode = lap();

    if (s->smpl) llama_sampler_free(s->smpl);
    auto sp = llama_sampler_chain_default_params();
    s->smpl = llama_sampler_chain_init(sp);
    // Order matters. Narrow the candidates first, then penalise repeats, then
    // pick - the penalty sampler warns against scanning a full vocabulary.
    llama_sampler_chain_add(s->smpl, llama_sampler_init_top_k(40));

    // Without this a small model falls into loops, restating the same sentence
    // until it hits the token cap. Small models are especially prone to it, and
    // it is the single most visible quality problem in a 0.5B.
    //   last_n 256  - how far back to look
    //   repeat 1.12 - mild; too high starts mangling ordinary grammar
    //   freq 0.35   - scales with how often a token already appeared
    //   present 0.4 - a flat cost for reusing anything at all
    llama_sampler_chain_add(
        s->smpl,
        llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 256, 1.12f, 0.35f, 0.4f));

    if (temperature <= 0.01f) {
        llama_sampler_chain_add(s->smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(s->smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(s->smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(s->smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(s->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    s->produced   = 0;
    s->max_tokens = maxTokens;
    s->running    = true;
    s->cancel.store(false);
    LOGI("chat started: %d prompt tokens, cap %d | template=%ldms tokenize=%ldms decode=%ldms (%.1f ms/tok)",
         (int) toks.size(), maxTokens, t_template, t_tokenize, t_decode,
         toks.empty() ? 0.0 : (double) t_decode / toks.size());
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
