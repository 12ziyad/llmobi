# LLMobi 1.1.0 validation

Validation date: 2026-10-31. This is a release candidate, not a claim that every
phone or model has been tested. Production publishing is separate from the
checks below.

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
| Cloudflare | Explicit LLMobi config passes Wrangler dry-run with its D1/KV bindings; no production mutation performed |

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
