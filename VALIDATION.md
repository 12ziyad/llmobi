# LLMobi 1.1.1 validation

Validation date: 2026-10-31. This patch replaces estimated-RAM rejection with an
explicit Try anyway option. A confirmed attempt uses a 512-token context,
128-token reply limit, at most two threads, 64-token processing batches, and no
whole-model page-cache warm-up or throwaway decode. Previous failed loads ask
for consent instead of permanently blocking the model. Stop can cancel native
weight loading through llama.cpp's progress callback.

Android's actual low-memory signal and severe-or-higher thermal status stop
active work and release the engine. Thermal reporting requires Android 10+;
older phones retain OS protections and memory-pressure handling. This cannot
guarantee that a model fits, that Android will never kill the app, or that every
phone reports heat promptly. There is no claimed percentage chance of success.

- Android: 59 tests pass, including real AppState send/confirmation/cancel flows
  with an explicitly substituted test engine, previous failures, retained drafts,
  stale confirmation, simulated heat during loading, Android service readings,
  cooling/retry and Android 9 compatibility. No native inference is simulated as
  a successful hardware test.
- Signed release build and lint pass (zero errors, 38 existing warnings).
- Android 15 x86_64 emulator: installed 1.1.1 over 1.1.0 without uninstalling;
  launch, navigation, retained Light/Red appearance and version display checked.
  The unsupported-CPU guard remains active. This emulator cannot measure ARM
  inference speed, peak RAM or actual heat on the reported Galaxy F15.
- Release preparation verifies signing-key continuity, package/version,
  non-debuggable status, ZIP integrity and 16 KB ZIP/ELF alignment.
- APK: 7,548,946 bytes; SHA-256
  `d628d493593cec39a2450a6104ce65da1acb7c7251345aba05bc2696e5e9b960`.
- Published at https://llmobi.pages.dev, Pages deployment `366bde67`.
- API deployment `dd21a1cf-ea22-4715-9905-d36aa1045eaf` advertises code 3 with
  the exact signed APK digest. Both public APK URLs and the live website's
  Download button were downloaded and matched. The APK was published and
  verified before the API manifest. The emulator's online update check passed.
- Public release: https://github.com/12ziyad/llmobi/releases/tag/v1.1.1.
- Independent GitHub verification passed:
  https://github.com/12ziyad/llmobi/actions/runs/41196059696 (59 Android tests,
  Android lint/debug APK, 10 API tests and 5 browser tests). PR #2 is merged.
- Existing 1.1.0 immutable download remains available. Local publication,
  screenshots, digest checks and CI evidence are in `release-artifacts/1.1.1`.
- The signed APK exports the new native load-cancellation methods and the
  shrinker preserves their bridge names. No physical ARM inference claim is made.

The detailed 1.1.0 baseline below remains historical evidence.

---

# LLMobi 1.1.0 validation

Validation date: 2026-10-31. Version 1.1.0 is published as a public beta. This is
not a claim that every phone or model has been tested.

## Production publication

- Website/APK: https://llmobi.pages.dev — Pages deployment `a6f63b3b`.
- Release: https://github.com/12ziyad/llmobi/releases/tag/v1.1.0.
- API version: `70ab8b67-11ec-476a-a0dc-4f5325ed6173`.
- `/v1/release` returns versionCode 2 with the exact signed APK size and digest.
- Both public APK paths returned HTTP 200 and matched the digest below.
- A live Chrome test at 390 px and 1365 px loaded all 20 models without overflow
  or JavaScript errors; clicking the main Download button produced the exact APK.
- All 20 live models now have checksums. The existing 383 download records were
  preserved; the browser download test increased the count to 384.
- A database export was saved locally before metadata changes. The previous API
  version was `4fe85521-8a31-46a9-bcb8-b1b8129ad3de`; the original APK remains in
  the local audit directory. Do not use an Android downgrade as a rollback.

## Observed baseline

- The public website and `/dl` redirect served the signed 1.0.0 APK.
- All 20 catalog URLs returned HTTP 200 with the advertised lengths; a sample
  range request worked. This checks availability, not each model's answers.
- The original APK SHA-256 was
  `5c0f42c42f91ec14a6e8b09ef4b58bdd6da2b39843f4e1ecfe1ce7ce398a42e8`.
- No physical Android device was connected during the initial audit.

## Automated checks

| Area | Coverage |
|---|---|
| Android, 45 tests | HTTP range/If-Range, ignored/invalid ranges, 416, truncated and oversized responses, changed catalog identity, low storage, cancellation, hashes, GGUF validation, RAM/CPU/storage fit, memory load guard, UTF-8 at every byte boundary, remote/delisted catalog entries, atomic-cache recovery, SQLite history/settings preservation, APK integrity/signer/package/version checks and verification markers |
| API integration, 10 tests | Real local D1 and KV; conditional catalog requests, dead-link detection and recovery, transient upstream errors, hidden entries, download counts, release endpoint, idempotent schema/seed, checksum/size refresh and safe database errors |
| Browser, 5 tests | 390 px mobile and 1365 px desktop layouts, all 20 catalog rows, download links, instructions accordion, no horizontal overflow or JavaScript errors, failed/empty API fallback, release metadata |
| Release build | Signed, non-debuggable `app.llmobi`, versionCode 2, Android 8+; same certificate as 1.0.0; ZIP integrity; ZIP and every ELF LOAD segment aligned for 16 KB pages |
| Cloudflare | Explicit LLMobi config passes Wrangler dry-run with its D1/KV bindings; production release, catalog and download checks passed after publication |

The final signed candidate is 7,516,494 bytes. Its SHA-256 is
`29dfa31e030f3a39229b731f55ba5028347620b4dc5d3d65e98f98358cdd1a35`.
Both website build paths (`/llmobi.apk` and `/releases/llmobi-1.1.0.apk`)
contain those same bytes. The release manifest advertises that digest.

Android lint is configured to fail on errors and passes with zero errors. Its
warnings include newer available SDK/dependency versions, style/unused resources,
the deliberate ARM-only build and synchronous preference commits on an IO thread.
The Android dependency stack was not wholesale upgraded during this repair.
Web and API npm audits report zero known vulnerabilities. API tests use the same
exact Miniflare version as pinned Wrangler, including its official v4-options
conversion helper.

Test reports are generated under `app/build/reports/` and browser screenshots
under `web/test-results/`. Those generated files are ignored by Git. CI uploads
reports and a debug APK for each push/PR. A clean hosted run passed all build,
lint, API and browser steps on commit `049fc66`:
[GitHub Actions validation](https://github.com/12ziyad/llmobi/actions/runs/41188688361).

## Android emulator checks

An isolated Android 15/API 35 emulator was used. The public signed 1.0.0 APK was
installed first, Light appearance was selected, and the signed 1.1.0 APK was
installed over it with `adb install -r`. Android accepted the upgrade without an
uninstall and preserved the appearance setting. Settings shows 1.1.0 and the new
update controls. Disabling both Wi-Fi and mobile data produced a recoverable
update-check message while the app stayed usable.

This emulator is x86_64 with ARM translation. The app correctly treats it as
unsupported for model inference; it is not evidence of ARM speed, temperature,
real low-memory behavior or successful model generation. No generated answer was
presented as a real inference result on this emulator. SQLite/history retention
and signing-policy tests use Robolectric; its Android package parser is simulated.

## Before a broad rollout

The user chose to finish this implementation with emulator testing for now.
The physical-device and hosted-update checks below remain explicitly unverified.

- On supported 4 GB, 6–8 GB and 12 GB+ ARM phones: install a fitting model, measure
  cold/warm first-token time and reply rate, and repeat after backgrounding,
  screen-off, rotation, rapid stop/model-switch and OS memory pressure.
- Cut the connection midway through a real multi-GB model download, restart the
  app/phone, resume and verify the hash; test nearly-full storage and denied
  notifications. Automated transfer tests cover these failure classes, but
  manufacturer background restrictions need physical testing.
- Send English, Malayalam and emoji prompts with airplane mode on; ensure saved
  chats reopen after process death. Repeat with representative small and large
  model families rather than assuming all 20 behave identically.
- Once an APK and its manifest are staged online, use a prior app version with
  the updater to test discovery, notification, resumed APK download, Android's
  permission/installer flow, and retention of real chats and model weights.
  Version 1.0.0 cannot discover updates; its first upgrade is manual.
- Verify on an actual 16 KB-page ARM device. Binary alignment is checked locally,
  but hardware execution has not been measured here.

The release procedure and withdrawal/forward-fix rules are in `README.md`.
Background checks are scheduled every six hours when connected; Android can
delay them, and an offline or force-stopped app cannot receive an instant update.
