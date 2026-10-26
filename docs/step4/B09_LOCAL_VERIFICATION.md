# B09 — Local Verification Record

## Revisions and environment

- Product source: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`.
- Code/test HEAD: `0f425cdc98fd78e3835350fc614881ecf911b729` (adds only a host measurement fixture and evidence; app/native product sources are unchanged from `b767ed8f`).
- Docs-only CI HEAD for run #477: `94d2ba35ebdd436ee0f679a606cd5b4a2cadd463` (no product/test source change).
- Host: macOS arm64, Eclipse Adoptium JDK 25.0.3+9; Android SDK `/Users/choko/Library/Android/sdk`.
- Emulator: `Medium_Phone_API_35`, Android API 35, ABI `arm64-v8a`. This is not TS10/K706 hardware.

## Host commands and result

| Command | Result | Notes |
|---|---|---|
| `./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest` | `PASS_LOCAL` | Shared 656 + common 206 = 862 tests; zero failures/errors/skips. XML is under each module's `build/test-results/testDebugUnitTest/`. |
| `python3 scripts/generate_step4_ex08_inventory.py --check` | `PASS_LOCAL` | 1,400 unique SHA rows; old 1,289-row prefix preserved. |
| `python3 scripts/test_step4_ex08_delta111.py` | `PASS_LOCAL` | 19/19 regression tests. |
| `bash scripts/test_android_ci_classifier.sh` | `PASS_LOCAL` | Change-scope fixtures pass. |
| `bash scripts/test_dsp_native.sh` | `PASS_LOCAL` | Host unsanitized C suite passes. |
| `bash scripts/test_dsp_native.sh --sanitize` | `BLOCKED_ENV` | Default LeakSanitizer aborts: `detect_leaks is not supported on this platform`. |
| `ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 bash scripts/test_dsp_native.sh --sanitize` | `PASS_LOCAL` with limitation | ASan/UBSan passes with LSan disabled; leak detection is unverified. |
| `./gradlew :mobile:lintDebug :automotive:lintDebug` | `PASS_LOCAL` | Both app Debug lint tasks pass. |
| `./gradlew :mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark` | `PASS_LOCAL` | Both apps' Debug and Benchmark variants build. The command first hit disk exhaustion during a combined test/lint/build invocation; the separated build run passed. |
| `python3 scripts/check_jni_mapping.py` | `PASS_LOCAL` | Both mobile and automotive Benchmark mappings preserve JNI entry points. Product source was unchanged by `0f425cdc`. |

## API 35 instrumentation

The latest local results are:

| Module/test | Result | Evidence |
|---|---|---|
| `:common:connectedDebugAndroidTest` | `PASS_EMULATOR` | 1/1 test, zero failures/errors/skips; `common/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_35(AVD) - 15-_common-.xml`. |
| `ANDROID_HOME=/Users/choko/Library/Android/sdk ./gradlew :shared:connectedDebugAndroidTest` | `PASS_EMULATOR` on the latest repeat | 19 tests, zero failures/errors/skips. XML: `shared/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_35(AVD) - 15-_shared-.xml`. The earlier run below remains recorded as a separate failed attempt. |

The earlier failed Shared rerun's three 48 kHz Gain runs were:

| Format | Per-run pipeline p99 (µs) | Median-run p99 (µs) | Budget | Result |
|---|---|---:|---:|---|
| 48 kHz stereo | 151,772 / 196,528 / 44,250 | 151,772 | 21,332 | `FAIL` |
| 48 kHz mono | 44,053 / 91,259 / 16,980 | 44,053 | 21,332 | `FAIL` |

The `benchmarks44100Mono` test also failed before it emitted a usable sample line, and the instrumentation process crashed. That run did not reach the remaining benchmark methods. The failure and its XML are summarized here because the later run overwrote the XML. No threshold was changed.

After that failure, a focused `benchmarks48000Stereo` run passed, then the full Shared instrumentation suite passed 19/19 on the same API 35 arm64 emulator and code/test head. Current Gain p99 samples were 240/320/660 µs (48 kHz stereo), 140/120/140 µs (48 kHz mono), 120/120/120 µs (44.1 kHz mono), and 180/180/180 µs (44.1 kHz stereo); each median is below its unchanged per-format ceiling. The combined filtered logcat capture at `shared/build/outputs/dsp-benchmark/logcat.txt` contains the four `HARMONIC_ALIAS` records and the latest benchmark samples. `bash scripts/check_android_instrumentation_evidence.sh shared/build/outputs/dsp-benchmark/logcat.txt` exits 0.

The initial p99 outlier was not reproduced in the focused or full-suite repeat. Emulator/host scheduling variation is a possible explanation, but its cause was not established. The passing rerun does not erase the earlier failure; both results remain part of the gate record.

The local helper `scripts/run_android_instrumentation.sh` cannot complete its post-run capture on this macOS host because neither `timeout` nor `gtimeout` is installed. The Gradle tasks were run directly, and filtered `adb logcat` capture plus the evidence validator were performed separately.

## Hosted run scope

[Run #476](https://github.com/choko510/DiPlay/actions/runs/40358035639) on code/test HEAD `0f425cdc` passed `changes`, static `check`, Draft unit, quick-baseline and Draft scope. The later docs-only [run #477](https://github.com/choko510/DiPlay/actions/runs/40361161687) on `94d2ba35` passed `changes`, static `check` and Draft scope; quick-baseline was skipped. Both runs explicitly skipped host/native/sanitizer/full lint/build/JNI/R8 and Android instrumentation because PR #45 remains Draft. Neither is a full current-head B09 pass.

## Current-head verification on 2026-10-27

- Product-feature baseline: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`. Current code/test HEAD: `52799604c5810ea97b2c7b5e685cf1c2155de773`; workflow-classifier policy HEAD: `22ada3a051d16f07c742771bc979e6b900642218`.
- Local `:shared:testDebugUnitTest :common:testDebugUnitTest` passes 865/865, including the VIDEO-02 release-failure regression. Both apps' lint, Debug/Benchmark assemblies, JNI/R8 mappings, native DSP and ASan/UBSan (with local LeakSanitizer disabled), and API 35 Shared 19/19 + Common 1/1 pass. EX-08 remains 19/19 with 1,400 inventory rows; classifier fixtures pass CI-01..CI-10.
- Workflow-sensitive [run #486](https://github.com/choko510/DiPlay/actions/runs/40372862000) passed `changes`, host native DSP, ASan/UBSan, shared/common unit tests, both app lint tasks, Debug/Benchmark assemblies, JNI/R8, API 35 instrumentation, `host-baseline`, and Draft scope. API 35 instrumentation passed Shared 19/19 and Common 1/1. `quick-baseline` was skipped because the full host lane ran instead.
- [Run #481](https://github.com/choko510/DiPlay/actions/runs/40366764979) passed the same full hosted gates on the preceding code/test head. The earlier API 35 Shared p99 failure/crash remains recorded separately from both passing repeats. No performance limit changed; the earlier outlier's cause is unconfirmed. Local macOS LeakSanitizer remains unavailable; hosted Linux sanitizer passed.
- PR #45 remains Draft and run #486 reports `DRAFT_ONLY_NOT_RELEASE_READY`; TS10/K706+iPhone, signed update/rollback, and independent review gates remain open.

## CI test-report artifact verification

[Workflow-sensitive run #490](https://github.com/choko510/DiPlay/actions/runs/40377747090) on workflow/artifact HEAD `06e4fcf472677d34ae7878d2378e15b47b309aa9` passed `changes`, host/native/sanitizer/unit/lint/build/JNI/R8, API 35 instrumentation, `host-baseline`, and Draft scope. `quick-baseline` was skipped because the full host lane ran. Product code/test HEAD remains `52799604c5810ea97b2c7b5e685cf1c2155de773`.

- `test-reports-check` contains 157 JUnit XML files: Shared 659 tests across 112 suites and Common 206 tests across 45 suites (865 total; zero failures, errors, or skipped tests). The artifact also contains the unit-test HTML reports.
- `test-reports-android-instrumentation` contains the Shared 19/19 and Common 1/1 connected-test XML files (20 total; zero failures, errors, or skipped tests), both modules' AndroidTest HTML reports, and the DSP benchmark logcat capture.
- The upload steps use `if: always()` so failed test runs should archive reports too; this successful run confirms the configured XML/report files are generated and included. It does not add TS10/K706+iPhone evidence or resolve the earlier p99 outlier.

## Acceptance boundary

These host/emulator results do not prove TS10/K706 USB endpoint behavior, supported-iPhone RTSP compatibility, DSP acoustic quality, target CPU/memory/soak, release signing, update/rollback, or independent review. Keep those gates `BLOCKED_DEVICE`, `BLOCKED_ENV` or `NOT_RUN` as recorded in `REMAINING_GATES.md`.
