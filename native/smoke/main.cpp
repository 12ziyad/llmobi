// Standalone smoke test / benchmark for LLMobi.
//
// Proves llama.cpp can load a GGUF and generate on this device, independent of
// the Android app, and reports tokens per second so thread counts can be chosen
// by measurement rather than guesswork.
//
//   usage: smoke <model.gguf> [prompt] [threads] [n_predict]
#include "llama.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using clk = std::chrono::steady_clock;

static double ms_since(clk::time_point t) {
    return std::chrono::duration<double, std::milli>(clk::now() - t).count();
}

int main(int argc, char **argv) {
    if (argc < 2) { printf("usage: smoke <model.gguf> [prompt] [threads] [n_predict]\n"); return 1; }
    const char *path   = argv[1];
    std::string prompt = argc > 2 ? argv[2] : "Explain gravity in one sentence.";
    const int threads  = argc > 3 ? atoi(argv[3]) : 4;
    const int npredict = argc > 4 ? atoi(argv[4]) : 32;

    llama_backend_init();

    auto t0 = clk::now();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mp);
    if (!model) { printf("FAIL: could not load model\n"); return 2; }
    const double load_ms = ms_since(t0);

    auto cp = llama_context_default_params();
    cp.n_ctx = 2048; cp.n_batch = 256;
    cp.n_threads = threads; cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { printf("FAIL: no context\n"); return 3; }

    const llama_vocab *vocab = llama_model_get_vocab(model);

    std::vector<llama_token> toks(prompt.size() + 64);
    int n = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                           toks.data(), (int) toks.size(), true, true);
    if (n <= 0) { printf("FAIL: tokenize (%d)\n", n); return 4; }
    toks.resize(n);

    auto t1 = clk::now();
    llama_batch b = llama_batch_get_one(toks.data(), n);
    if (llama_decode(ctx, b) != 0) { printf("FAIL: prompt decode\n"); return 5; }
    const double prompt_ms = ms_since(t1);

    auto sp = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const bool quiet = getenv("QUIET") != nullptr;
    if (!quiet) printf("\n--- OUTPUT ---\n");

    auto t2 = clk::now();
    int produced = 0;
    for (int i = 0; i < npredict; i++) {
        llama_token t = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        llama_sampler_accept(smpl, t);
        char buf[256];
        int m = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
        if (m > 0 && !quiet) { fwrite(buf, 1, m, stdout); fflush(stdout); }
        llama_batch nb = llama_batch_get_one(&t, 1);
        if (llama_decode(ctx, nb) != 0) break;
        produced++;
    }
    const double gen_ms = ms_since(t2);
    if (!quiet) printf("\n--- END ---\n");

    printf("RESULT threads=%d load_ms=%.0f prompt_ms=%.0f gen_tokens=%d gen_ms=%.0f tok_per_s=%.2f\n",
           threads, load_ms, prompt_ms, produced, gen_ms,
           produced > 0 ? produced * 1000.0 / gen_ms : 0.0);

    llama_sampler_free(smpl);
    llama_free(ctx);
    llama_model_free(model);
    return 0;
}
