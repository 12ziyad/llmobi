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

**Live:** [llmobi.pages.dev](https://llmobi.pages.dev) &middot; API at `llmobi-api.gpmai.workers.dev`

---

## Licence

LLMobi's own code is [Apache 2.0](LICENSE).

The inference engine is [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT),
compiled into the shipped APK. See [NOTICE](NOTICE) for the full third-party list.

**Model weights are not covered by any of that.** This project never contains,
hosts or redistributes them - the catalog holds metadata and a link, and the app
downloads each file straight from Hugging Face to the device. Every model carries
its own licence, agreed between the user and the publisher. If you fork this and
mirror weights instead of linking, that is redistribution and the obligations
become yours; NOTICE lists which licences apply to what.

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

Requires JDK 17, Android SDK 35, build-tools 35.0.0, CMake 3.22.1, and NDK 28.2.13676358. Restore the exact native revision before building:

```powershell
./tools/setup_native.ps1
```

The revision is pinned in `native/llama.cpp.ref`; the setup script refuses to overwrite a dirty native checkout.

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
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/28.2.13676358/build/cmake/android.toolchain.cmake \
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
| `GET /v1/release` | Latest signed APK version, immutable URL, size and SHA-256 |

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

The app checks 64-bit ARM, the engine's required CPU instructions (`asimddp` and
`asimdhp`), available RAM and free storage. Unsupported processors are explained
before the user downloads model weights.

```
usable = max(0, availableRam - max(Android low-memory threshold, 192 MB))
ratio  = usable / model.minRamMb

ratio >= 1.80  -> Excellent
ratio >= 1.25  -> Recommended
ratio >= 1.00  -> Heavy
otherwise     -> Too heavy right now
```

Recommendations sort by fit, then file size. Downloading leaves 1 GB free;
installed models do not need that space a second time. RAM estimates include
weights and runtime overhead, but are not a speed guarantee. Memory is checked
again before loading. A small shortfall reduces context; less than 85% of the
estimated requirement stops the load with a useful message. A 45-second watchdog
stops generation if no text arrives.

Model downloads resume in private partial files. Size, GGUF magic and the catalog
SHA-256 are checked before atomic installation. Bundled models all have SHA-256
checksums. Interrupted transfers retry with backoff; unavailable files and storage
problems show a recoverable error. Installed metadata is preserved separately so
a catalog removal does not break an already downloaded model.

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
- Online requests fetch catalog/release metadata and download selected models or app updates. The website counts APK downloads; chat content is never included.
- Automatic cloud backup is excluded. Android device-to-device transfer can carry chats and settings; large model weights must be downloaded again.
- Analytics are off by default and there is no telemetry in v1.

---

## Licences

We link to Hugging Face rather than redistributing weights. Apache-2.0 and MIT models (Qwen, Mistral, Phi, DeepSeek distills) can be mirrored to R2 freely. Llama and Gemma permit redistribution **with conditions** — get those reviewed before mirroring; until then the catalog points at the upstream URL.

## Offline updates and releases

Version 1.1.0 adds update checks on launch/return and a connected-network worker
scheduled every six hours. Android may delay background work to save battery.
When an update is found, the app shows it in Settings and can send a notification
if permission is enabled. The user chooses when to download and install; offline
chat remains independent of update checks. Android always owns the final install
confirmation. An offline or force-stopped app cannot receive an instant push.

Existing 1.0.0 installations need one manual upgrade to 1.1.0 to get this feature.
Install over the existing app using the same signing key; do not uninstall it.

For each later release:

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run the verification commands below and test on a supported physical phone.
3. Build `assembleRelease` with the original signing key.
4. Run `python tools/prepare_release.py --previous-apk PATH_TO_PUBLISHED_APK`.
   This verifies identity, signing-key continuity, increasing version, archive
   integrity and alignment. It stages the APK in `web/public/releases/`, updates
   the latest download, and generates `api/src/release.js` plus local evidence in
   `release-artifacts/`. It does not deploy anything.
5. Build and publish the website/APK first. Verify the immutable release URL,
   byte length and SHA-256 over HTTPS before publishing the API manifest.
6. Publish the API, then check `/v1/release` on an older installed app, download,
   install and confirm its chats/settings/models remain intact.

Do not replace the bytes at an existing versioned APK URL. If a release must be
withdrawn, remove its API offer (`RELEASE = null`) and restore the website's last
known good download. Already installed apps require a fix with a **higher**
versionCode; Android downgrades are not a safe rollback strategy. Preserve old
release files and signing-key backups. Catalog seeds upsert metadata without
resetting download counts, status or admin overrides.

## Verification

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleRelease --max-workers=2 --console=plain
cd api
npm ci
npm test
npx wrangler --config wrangler.toml deploy --dry-run
cd ../web
npm ci
npm run build
npm run test:e2e
```

Browser tests use installed Chrome and isolate upstream responses to test both
online and failed/empty API conditions. API integration tests use local D1/KV.
Android tests cover HTTP range recovery, truncation, corruption, cancellation,
low storage, phone fit, load safety, catalog persistence, SQLite history and UTF-8
streaming. CI runs these checks and builds an unsigned debug APK without secrets.

See `VALIDATION.md` for evidence from this change and the physical-device cases
that still need measurement. Earlier Galaxy F15 numbers above are historical
benchmarks, not new results for this release.
