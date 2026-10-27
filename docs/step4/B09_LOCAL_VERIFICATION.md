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

The first local API 35 results below are from code/test head `0f425cdc`; the later current-code/test-head repeat after run #490 is recorded under `API 35 p99 distribution recheck` below.

| Module/test | Result | Evidence |
|---|---|---|
| `:common:connectedDebugAndroidTest` | `PASS_EMULATOR` at `0f425cdc` | 1/1 test, zero failures/errors/skips; `common/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_35(AVD) - 15-_common-.xml`. Current-code Common 1/1 is also confirmed by run #490. |
| `ANDROID_HOME=/Users/choko/Library/Android/sdk ./gradlew :shared:connectedDebugAndroidTest` | `PASS_EMULATOR` at `0f425cdc` | 19 tests, zero failures/errors/skips. XML: `shared/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_35(AVD) - 15-_shared-.xml`. The earlier run below remains recorded as a separate failed attempt. |

The earlier failed Shared rerun's three 48 kHz Gain runs were:

| Format | Per-run pipeline p99 (µs) | Median-run p99 (µs) | Budget | Result |
|---|---|---:|---:|---|
| 48 kHz stereo | 151,772 / 196,528 / 44,250 | 151,772 | 21,332 | `FAIL` |
| 48 kHz mono | 44,053 / 91,259 / 16,980 | 44,053 | 21,332 | `FAIL` |

The `benchmarks44100Mono` test also failed before it emitted a usable sample line, and the instrumentation process crashed. That run did not reach the remaining benchmark methods. The failure and its XML are summarized here because the later run overwrote the XML. No threshold was changed.

After that failure, a focused `benchmarks48000Stereo` run passed, then the full Shared instrumentation suite passed 19/19 on the same API 35 arm64 emulator and code/test head. Current Gain p99 samples were 240/320/660 µs (48 kHz stereo), 140/120/140 µs (48 kHz mono), 120/120/120 µs (44.1 kHz mono), and 180/180/180 µs (44.1 kHz stereo); each median is below its unchanged per-format ceiling. The combined filtered logcat capture at `shared/build/outputs/dsp-benchmark/logcat.txt` contains the four `HARMONIC_ALIAS` records and the latest benchmark samples. `bash scripts/check_android_instrumentation_evidence.sh shared/build/outputs/dsp-benchmark/logcat.txt` exits 0.

The specific 151,772/44,053 µs failures and process crash did not repeat in the later full-suite runs. Run #490 and the current local repeat did show single-run p99 tails above 21,332 µs while their median-of-three gates passed. Emulator/host scheduling variation is possible, but the cause of the original failure and later tails is unconfirmed. Preserve every distribution; a passing median does not prove that each measured run stayed under the ceiling.

The local helper `scripts/run_android_instrumentation.sh` cannot complete its post-run capture on this macOS host because neither `timeout` nor `gtimeout` is installed. The Gradle tasks were run directly, and filtered `adb logcat` capture plus the evidence validator were performed separately.

## Hosted run scope

[Run #476](https://github.com/choko510/DiPlay/actions/runs/40358035639) on code/test HEAD `0f425cdc` passed `changes`, static `check`, Draft unit, quick-baseline and Draft scope. The later docs-only [run #477](https://github.com/choko510/DiPlay/actions/runs/40361161687) on `94d2ba35` passed `changes`, static `check` and Draft scope; quick-baseline was skipped. Both runs explicitly skipped host/native/sanitizer/full lint/build/JNI/R8 and Android instrumentation because PR #45 remains Draft. Neither is a full current-head B09 pass.

## Current-head verification on 2026-10-27

- Product-feature baseline: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`. Current product source head remains `6392b56f8ac3093b8d5cd29de0dfeec0f73e9af8`; current code/test head is `78f43526d69b5fe97aae150d0a222ae590fb3b53`, which adds only a Robolectric ComposeView measurement regression. The earlier test-only per-block thread-CPU metric remains in the benchmark; product DSP processing is unchanged. Workflow-classifier policy HEAD: `22ada3a051d16f07c742771bc979e6b900642218`.
- Workflow-sensitive [run #495](https://github.com/choko510/DiPlay/actions/runs/40473871836) passed `changes`, host native DSP, ASan/UBSan, shared/common unit tests, both app lint tasks, Debug/Benchmark assemblies, JNI/R8, API 35 instrumentation, `host-baseline`, and Draft scope. The x86_64 API 35 benchmark passed all 56 median-of-three wall-p99 gates; maximum median was 15,440 µs and maximum single-run p99 was 17,720 µs against the 21,332 µs ceiling. The artifact also recorded a maximum thread-CPU p99 of 4,760 µs. `quick-baseline` was skipped because the full host lane ran instead.
- Run #495 artifacts contain 865 unit tests (Shared 659 + Common 206) and 20 connected instrumentation tests (Shared 19 + Common 1), all with zero failures/errors/skips. EX-08 remains 19/19 with 1,400 inventory rows; classifier fixtures pass CI-01..CI-10.
- After this result-package update, docs-only [run #497](https://github.com/choko510/DiPlay/actions/runs/40475514137) passed `changes`, static `check`, and Draft scope. Host/native/sanitizer/unit/lint/build/JNI/R8 and Android instrumentation were skipped; it adds no runtime evidence.
- The ComposeView measurement regression also passes locally: `:common:testDebugUnitTest --tests CarPlayHostActivityLocalScaleTest --tests DspSplitMeasureDiagnosticsTest` ran 9 tests with zero failures/errors/skips. This focused test confirms the production pane hook passes and records the real ComposeView specs; it does not establish SH-M27 rendering or replace the full common suite.
- Workflow-sensitive [run #498](https://github.com/choko510/DiPlay/actions/runs/40479649989) on test-only head `78f43526` passed `changes`, Draft unit tests, quick-baseline, and Draft scope. Host/native/sanitizer/lint/build/JNI/R8, `host-baseline`, and API 35 instrumentation were skipped; the test reports artifact was uploaded.
- The first local arm64-v8a API 35 focused DSP benchmark class failed four wall-p99 assertions. A further rerun failed three of five methods; two cases also exceeded thread-CPU p99 limits. The Debug native build is `-O0` and Release is `-O2`, a confirmed variant difference but not a proven cause. Preserve all results and thresholds; local Debug emulator failures do not establish target audio behavior. Local macOS LeakSanitizer remains unavailable; hosted Linux sanitizer passed.
- [Run #490](https://github.com/choko510/DiPlay/actions/runs/40377747090) passed the full workflow-sensitive lane before the thread-CPU diagnostic was added. PR #45 remains Draft and reports `DRAFT_ONLY_NOT_RELEASE_READY`; TS10/K706+iPhone, signed update/rollback, and independent review gates remain open.

## CI test-report artifact verification

[Workflow-sensitive run #495](https://github.com/choko510/DiPlay/actions/runs/40473871836) on code/test HEAD `6392b56f8ac3093b8d5cd29de0dfeec0f73e9af8` passed `changes`, host/native/sanitizer/unit/lint/build/JNI/R8, API 35 x86_64 instrumentation, `host-baseline`, and Draft scope. `quick-baseline` was skipped because the full host lane ran.

- `test-reports-check` contains 157 JUnit XML files: Shared 659 tests across 112 suites and Common 206 tests across 45 suites (865 total; zero failures, errors, or skipped tests). The artifact also contains the unit-test HTML reports.
- `test-reports-android-instrumentation` contains the Shared 19/19 and Common 1/1 connected-test XML files (20 total; zero failures, errors, or skipped tests), both modules' AndroidTest HTML reports, and the DSP benchmark logcat capture. Its benchmark log has 168 rows (56 configurations × three measured runs) with wall and thread-CPU p99 metrics. All wall-p99 median gates passed; maximum median was 15,440 µs (`Convolver64k`, 48 kHz stereo), and maximum single-run p99 was 17,720 µs against the unchanged 21,332 µs ceiling. Maximum thread-CPU p99 was 4,760 µs. Four harmonic alias samples were present; maximum ratio was 6.1357×10⁻⁵.
- The upload steps use `if: always()` so failed test runs should archive reports too; this successful run confirms the configured XML/report files are generated and included. It does not add TS10/K706+iPhone evidence or resolve the earlier p99 outlier.

## API 35 p99 distribution recheck

The prior local full suite at product/test head `52799604` passed Shared 19/19 with a maximum median of 20,020 µs and an 84,009 µs single-run tail. After adding the per-block thread-CPU diagnostic, the focused benchmark class on the same API 35 arm64-v8a AVD failed four wall-p99 assertions: PEQ+compressor/limiter at 48 kHz stereo (62,543 µs wall vs 17,960 µs thread CPU), PEQ15 at 48 kHz mono (45,855 vs 18,060 µs), PEQ+compressor/limiter at 44.1 kHz mono (27,509 vs 16,120 µs), and PEQ15 at 44.1 kHz stereo (147,633 vs 10,900 µs). The respective ceilings are 21,332 µs at 48 kHz and 23,218 µs at 44.1 kHz. The thread-CPU values indicate that much of the observed wall time was outside the measured thread, but do not identify the source or prove head-unit behavior. CI #495 passed all wall-p99 gates on x86_64; keep the threshold unchanged and the arm64 emulator failure recorded separately.

A further focused rerun on the attached `Medium_Phone_API_35` arm64-v8a AVD completed all five test methods with three failures, zero errors, and zero skips; the failing methods produced 81 benchmark rows before stopping at each format's first failing configuration. Median wall/thread-CPU p99 pairs were 48 kHz stereo `Gain` 83,234/59,994 µs, 48 kHz mono `Cabin3Notches` 30,720/13,300 µs, and 44.1 kHz stereo `PEQ_Compressor_Limiter` 70,328/67,262 µs, against 21,332/21,332/23,218 µs ceilings. The 44.1 kHz mono matrix passed. The two high thread-CPU cases show that the earlier scheduling-only hypothesis is insufficient. This local instrumentation uses the Debug shared variant (`NDK_DEBUG=1`, native `-O0`); the inspected Release native build uses `NDK_DEBUG=0`, `-O2`. Gradle task inspection confirms `:shared` exposes `connectedDebugAndroidTest` but no `connectedReleaseAndroidTest`, so the current instrumented p99 harness cannot measure the optimized Release JNI library on this host. The variant difference is confirmed, but is not a proven cause of the local failures. CI #495's API 35 x86_64 Debug instrumentation passed; no threshold was changed.

## Acceptance boundary

These host/emulator results do not prove TS10/K706 USB endpoint behavior, supported-iPhone RTSP compatibility, DSP acoustic quality, target CPU/memory/soak, release signing, update/rollback, or independent review. Keep those gates `BLOCKED_DEVICE`, `BLOCKED_ENV` or `NOT_RUN` as recorded in `REMAINING_GATES.md`.
