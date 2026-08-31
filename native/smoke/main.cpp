// Standalone smoke test: proves llama.cpp can load a GGUF and generate on this
// device, independent of the Android app. Run it via adb shell.
#include "llama.h"
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

int main(int argc, char **argv) {
    if (argc < 2) { printf("usage: smoke <model.gguf> [prompt]\n"); return 1; }
    const char *path = argv[1];
    std::string prompt = argc > 2 ? argv[2] : "Hello! Who are you?";

    llama_backend_init();

    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mp);
    if (!model) { printf("FAIL: could not load model\n"); return 2; }
    printf("OK: model loaded\n");

    auto cp = llama_context_default_params();
    cp.n_ctx = 1024; cp.n_batch = 256; cp.n_threads = 4; cp.n_threads_batch = 4;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { printf("FAIL: no context\n"); return 3; }
    printf("OK: context created (n_ctx=%u)\n", llama_n_ctx(ctx));

    const llama_vocab *vocab = llama_model_get_vocab(model);

    std::vector<llama_token> toks(prompt.size() + 64);
    int n = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                           toks.data(), (int) toks.size(), true, true);
    if (n <= 0) { printf("FAIL: tokenize (%d)\n", n); return 4; }
    toks.resize(n);
    printf("OK: %d prompt tokens\n", n);

    llama_batch b = llama_batch_get_one(toks.data(), n);
    if (llama_decode(ctx, b) != 0) { printf("FAIL: prompt decode\n"); return 5; }
    printf("OK: prompt decoded\n");

    auto sp = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    printf("\n--- OUTPUT ---\n");
    for (int i = 0; i < 48; i++) {
        llama_token t = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) { printf("\n[eog]\n"); break; }
        llama_sampler_accept(smpl, t);
        char buf[256];
        int m = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
        if (m > 0) { fwrite(buf, 1, m, stdout); fflush(stdout); }
        llama_batch nb = llama_batch_get_one(&t, 1);
        if (llama_decode(ctx, nb) != 0) { printf("\n[decode stopped]\n"); break; }
    }
    printf("\n--- END ---\nSMOKE TEST PASSED\n");

    llama_sampler_free(smpl);
    llama_free(ctx);
    llama_model_free(model);
    return 0;
}
