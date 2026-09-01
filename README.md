# LLMobi

**AI that works with no internet.** An app store for local LLMs on Android — browse models, tap install, and each one gets its own home-screen icon that opens straight into its chat. After the download, everything runs on the phone's own chip: no cloud, no account, no API key, no bill.

```
LLMobi.apk
 ├── llama.cpp  (one shared engine, arm64)
 ├── SQLite     (chats, installed models, per-model settings)
 └── models/    qwen25-05b.gguf, llama32-1b.gguf, …
        ↓ each gets a pinned Android shortcut
   llmobi://chat/model/gemma3-4b  →  opens Gemma's chat directly
```

One APK, many icons. To the person holding the phone it looks like several separate AI apps.

---

## Repository layout

| Path | What it is |
|---|---|
| `app/` | The Android app — Kotlin + Jetpack Compose |
| `app/src/main/cpp/` | JNI bridge to llama.cpp (`llama-jni.cpp`, `CMakeLists.txt`) |
| `native/llama.cpp/` | Upstream llama.cpp, built from source for arm64 |
| `native/smoke/` | Standalone native test binary — proves inference works on a device |
| `web/` | The download page — React + Vite, deploys to Cloudflare Pages |
| `api/` | Catalog API — Cloudflare Worker + D1 + KV |
| `tools/gen_seed.py` | Generates `api/seed.sql` from the app's bundled catalog |

---

## Build the app

Requires JDK 17, the Android SDK, and NDK 27.

```bash
./gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a signed release build, create `keystore.properties` beside `settings.gradle.kts`:

```properties
storeFile=llmobi-release.jks
storePassword=...
keyAlias=llmobi
keyPassword=...
```

Then `./gradlew.bat assembleRelease`. Without that file the release still
assembles, just unsigned, so a fresh clone and CI both work without secrets.
**The keystore is gitignored - back it up somewhere safe. Lose it and you can
never update the app on Play.**

The first build compiles llama.cpp from source and takes a few minutes. Later builds are incremental.

### Prove the engine works on a device

Before blaming the UI, check the engine in isolation:

```bash
cd native/smoke
cmake -S . -B build-arm64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/27.0.12077973/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
cmake --build build-arm64 --target smoke -j 8

adb push build-arm64/smoke /data/local/tmp/smoke
adb shell chmod 755 /data/local/tmp/smoke
adb shell "cd /data/local/tmp && ./smoke model.gguf 'Hello'"
```

It prints `SMOKE TEST PASSED` and the generated text.

---

## The website

```bash
cd web
npm install
npm run dev      # local
npm run build    # -> web/dist
```

Deploy to Cloudflare Pages:

```bash
npx wrangler login
npx wrangler pages deploy dist --project-name=llmobi
```

That gives you `llmobi.pages.dev`. Point `llmobi.app` at it once the domain is bought.

---

## The catalog API

```bash
cd api
npm install
npx wrangler login

npx wrangler d1 create llmobi            # copy database_id into wrangler.toml
npx wrangler kv namespace create CACHE    # copy id into wrangler.toml

npm run db:init    # create tables
npm run db:seed    # load the 20 launch models
npm run deploy
```

### Endpoints

| Route | Purpose |
|---|---|
| `GET /v1/catalog` | The whole model list, ~40 KB, ETag'd so repeats return `304` |
| `GET /v1/model/:id` | One model |
| `GET /v1/search?q=` | Proxied Hugging Face search, for Advanced mode |
| `GET /v1/health` | Live model count |

A weekly cron re-checks every listed file still exists and corrects sizes that drifted. It never adds models automatically — curation is the product.

### Regenerating the seed

The app ships a bundled catalog so the store works offline on first launch. Keep D1 in step with it:

```bash
python tools/gen_seed.py    # Catalog.kt -> api/seed.sql
```

---

## Performance, and the trap that cost a night

Measured on a Galaxy F15 (Dimensity 6100+, 2 fast cores at 2.2 GHz, 6 slow at 2.0):

| | cold tap to first token | per prompt token |
|---|---|---|
| before | 53.8 s | 975 ms |
| after | 6.4 s | 52 ms |
| warm | ~2 s | 25 ms |

Four separate causes, in order of how much they mattered:

**ggml was compiled at `-O0`.** AGP sets `CMAKE_BUILD_TYPE=Debug`, and CMake
appends `CMAKE_C_FLAGS_DEBUG` *after* anything you pass, so `-O0` wins. Worse,
overriding that cache variable silently does nothing if `project()` did not
enable C - enabling a language re-runs the toolchain's flag setup and resets it.
ggml is almost entirely C. `CMakeLists.txt` now declares `project(llmobi C CXX)`
and backs it up with `add_compile_options`. **This alone was 18x.**

**The engine thread ran at nice 10.** Java thread priorities map harshly onto
Linux nice values, and ggml spawns its workers from whichever thread calls
decode - so the whole fleet inherited it. Pinned to nice 0.

**The chat template was never applied.** The model was being handed text to
continue rather than a question to answer, so it never emitted an end-of-turn
token and ran to the 400-token cap on every reply. It now uses the template
inside the GGUF, and a short answer is a short answer.

**Inference ran on the UI thread.** `viewModelScope.launch` defaults to Main and
a flow builder inherits the collector's context.

Two smaller wins: one sequential read warms the page cache before mmap (~290
MB/s, versus faulting 408 MB in at random during the first message), and a
throwaway single-token decode after load moves the remaining cost into the
"waking up" screen.

### Choosing a quantisation

Benchmark on the device, do not assume. On ARM with `asimddp` and no `i8mm`,
llama.cpp repacks Q4_0 to use the dot-product instructions:

| quant | tokens/sec | size |
|---|---|---|
| Q4_0 | 17.2 | 429 MB |
| Q4_K_M | 15.0 | 491 MB |
| Q5_K_M | 14.4 | 522 MB |

Fastest *and* smallest, so the tiny tier ships Q4_0. Thread count is worth
measuring too: 6 threads beat 2, 3, 4 and 8 on this chip.

## Keeping the catalog honest

```bash
python tools/check_catalog.py       # every URL resolves, every size is right
python tools/fix_catalog_sizes.py   # rewrite sizes from what the CDN reports
python tools/gen_seed.py            # Catalog.kt -> api/seed.sql
```

The first one has already caught a dead link and twenty sizes that were
overstated by up to 11%. Run it before shipping a catalog change.

## How compatibility is decided

Never from parameter count. The app reads the device and compares one precomputed number.

```
usable  = availableRam + (totalRam - availableRam) / 5
ratio   = usable / model.minRamMb

ratio >= 1.80  →  Excellent
ratio >= 1.25  →  Recommended
ratio >= 1.00  →  Heavy
otherwise      →  Won't run
```

`minRamMb` is `fileMB * 1.25 + 500`, calibrated against the one case actually
measured: Qwen 0.5B (408 MB of weights) runs comfortably alongside Compose and
the Android runtime with ~1040 MB free. Storage is checked separately, with a 1 GB cushion so the phone is never filled completely.

The user sees a coloured word. They never see a number, and never see `GGUF`, `Q4_K_M`, `KV cache` or `tokens/sec`.

---

## The engine seam

`engine/Engine.kt` defines one interface with two implementations:

- **`LlamaEngine`** — the real JNI bridge, used whenever `libllmobi.so` loads
- **`StubEngine`** — streams canned text, used when it doesn't

This is not a product feature; it's a development seam. Every screen, the stop button, history saving and shortcuts can all be built and tested before the native library exists, and the UI cannot tell the difference. `Engines` keeps exactly one model resident — two 4 GB models in memory is an instant kill on a small phone.

---

## Privacy

- No account, anywhere in the app.
- Chats never leave the phone. There is no endpoint that could receive them.
- The only two network calls are: fetch the catalog, download a model file. Both live in one file each, so the claim is auditable.
- Analytics are off by default and there is no telemetry in v1.

---

## Licences

We link to Hugging Face rather than redistributing weights. Apache-2.0 and MIT models (Qwen, Mistral, Phi, DeepSeek distills) can be mirrored to R2 freely. Llama and Gemma permit redistribution **with conditions** — get those reviewed before mirroring; until then the catalog points at the upstream URL.
