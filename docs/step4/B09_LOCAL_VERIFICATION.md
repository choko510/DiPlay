# B09 — Local Verification Record

## Revisions and environment

- Product source: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`.
- Product source HEAD remains `6392b56f8ac3093b8d5cd29de0dfeec0f73e9af8`; current code/test/config HEAD is `df3977d4a0860970e15a42d5f65a473cfe60e8ee`. Test-only commits add wall/thread-CPU diagnostics and actual ComposeView measurement coverage; `df3977d4` selects the optimized Release JNI library for Shared instrumentation while leaving the default AndroidTest build type as Debug. No DSP processing code changed.
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

- Product-feature baseline: `b767ed8f847a34b7cff72ddbf6a3279b8ad4184f`. Current product source head remains `6392b56f8ac3093b8d5cd29de0dfeec0f73e9af8`; current code/test/config head is `df3977d4a0860970e15a42d5f65a473cfe60e8ee`. Test-only changes add the per-block thread-CPU metric and ComposeView measurement regression; `df3977d4` selects Release Shared instrumentation for p99 and runs Common Debug separately. Product DSP processing is unchanged. Workflow-classifier policy HEAD: `22ada3a051d16f07c742771bc979e6b900642218`.
- Workflow-sensitive [run #495](https://github.com/choko510/DiPlay/actions/runs/40473871836) passed `changes`, host native DSP, ASan/UBSan, shared/common unit tests, both app lint tasks, Debug/Benchmark assemblies, JNI/R8, API 35 instrumentation, `host-baseline`, and Draft scope. The x86_64 API 35 benchmark passed all 56 median-of-three wall-p99 gates; maximum median was 15,440 µs and maximum single-run p99 was 17,720 µs against the 21,332 µs ceiling. The artifact also recorded a maximum thread-CPU p99 of 4,760 µs. `quick-baseline` was skipped because the full host lane ran instead.
- Run #495 artifacts contain 865 unit tests (Shared 659 + Common 206) and 20 connected instrumentation tests (Shared 19 + Common 1), all with zero failures/errors/skips. EX-08 remains 19/19 with 1,400 inventory rows; classifier fixtures pass CI-01..CI-10.
- After this result-package update, docs-only [run #497](https://github.com/choko510/DiPlay/actions/runs/40475514137) passed `changes`, static `check`, and Draft scope. Host/native/sanitizer/unit/lint/build/JNI/R8 and Android instrumentation were skipped; it adds no runtime evidence.
- The ComposeView measurement regression also passes locally: `:common:testDebugUnitTest --tests CarPlayHostActivityLocalScaleTest --tests DspSplitMeasureDiagnosticsTest` ran 9 tests with zero failures/errors/skips. This focused test confirms the production pane hook passes and records the real ComposeView specs; it does not establish SH-M27 rendering or replace the full common suite.
- Workflow-sensitive [run #498](https://github.com/choko510/DiPlay/actions/runs/40479649989) on test-only head `78f43526` passed `changes`, Draft unit tests, quick-baseline, and Draft scope. Host/native/sanitizer/lint/build/JNI/R8, `host-baseline`, and API 35 instrumentation were skipped; the test reports artifact was uploaded.
- Workflow-sensitive [run #502](https://github.com/choko510/DiPlay/actions/runs/40487578349) on test/config head `df3977d4` passed `changes`, host native DSP, ASan/UBSan, full unit/lint/build/JNI/R8, API 35 instrumentation, `host-baseline`, and Draft scope. The updated instrumentation script ran Shared Release and Common Debug sequentially; the API 35 instrumentation job and evidence upload succeeded. `quick-baseline` and the separate Draft unit job were skipped because full-host scope ran. Report artifacts: check `12727040240` (`sha256:6f843337ad7e55c5929dc8fbf8d7c6e2af52631c9b4702915a627f1990dfc47d`) and instrumentation `12726236011` (`sha256:74cf76293cefecbd344b4434eacc1e1020b631a2badf7f5df2ac189474f5f297`).
- The first local arm64-v8a API 35 focused DSP benchmark class failed four wall-p99 assertions. A further rerun failed three of five methods; two cases also exceeded thread-CPU p99 limits. The Debug native build is `-O0` and Release is `-O2`, a confirmed variant difference but not a proven cause. Preserve all results and thresholds; local Debug emulator failures do not establish target audio behavior. Local macOS LeakSanitizer remains unavailable; hosted Linux sanitizer passed.
- [Run #490](https://github.com/choko510/DiPlay/actions/runs/40377747090) passed the full workflow-sensitive lane before the thread-CPU diagnostic was added. PR #45 remains Draft and reports `DRAFT_ONLY_NOT_RELEASE_READY`; TS10/K706+iPhone, signed update/rollback, and independent review gates remain open.

## CI test-report artifact verification

[Workflow-sensitive run #495](https://github.com/choko510/DiPlay/actions/runs/40473871836) on code/test HEAD `6392b56f8ac3093b8d5cd29de0dfeec0f73e9af8` passed `changes`, host/native/sanitizer/unit/lint/build/JNI/R8, API 35 x86_64 instrumentation, `host-baseline`, and Draft scope. `quick-baseline` was skipped because the full host lane ran.

- `test-reports-check` contains 157 JUnit XML files: Shared 659 tests across 112 suites and Common 206 tests across 45 suites (865 total; zero failures, errors, or skipped tests). The artifact also contains the unit-test HTML reports.
- `test-reports-android-instrumentation` contains the Shared 19/19 and Common 1/1 connected-test XML files (20 total; zero failures, errors, or skipped tests), both modules' AndroidTest HTML reports, and the DSP benchmark logcat capture. Its benchmark log has 168 rows (56 configurations × three measured runs) with wall and thread-CPU p99 metrics. All wall-p99 median gates passed; maximum median was 15,440 µs (`Convolver64k`, 48 kHz stereo), and maximum single-run p99 was 17,720 µs against the unchanged 21,332 µs ceiling. Maximum thread-CPU p99 was 4,760 µs. Four harmonic alias samples were present; maximum ratio was 6.1357×10⁻⁵.
- The upload steps use `if: always()` so failed test runs should archive reports too; this successful run confirms the configured XML/report files are generated and included. It does not add TS10/K706+iPhone evidence or resolve the earlier p99 outlier.

## API 35 p99 distribution recheck

The prior local full suite at product/test head `52799604` passed Shared 19/19 with a maximum median of 20,020 µs and an 84,009 µs single-run tail. After adding the per-block thread-CPU diagnostic, the focused benchmark class on the same API 35 arm64-v8a AVD failed four wall-p99 assertions: PEQ+compressor/limiter at 48 kHz stereo (62,543 µs wall vs 17,960 µs thread CPU), PEQ15 at 48 kHz mono (45,855 vs 18,060 µs), PEQ+compressor/limiter at 44.1 kHz mono (27,509 vs 16,120 µs), and PEQ15 at 44.1 kHz stereo (147,633 vs 10,900 µs). The respective ceilings are 21,332 µs at 48 kHz and 23,218 µs at 44.1 kHz. The thread-CPU values indicate that much of the observed wall time was outside the measured thread, but do not identify the source or prove head-unit behavior. CI #495 passed all wall-p99 gates on x86_64; keep the threshold unchanged and the arm64 emulator failure recorded separately.

An additional focused rerun on the attached `Medium_Phone_API_35` arm64-v8a AVD completed all five Debug test methods with three failures, zero errors, and zero skips; 81 benchmark rows were emitted before the matrices stopped. Median wall/thread-CPU p99 pairs were 48 kHz stereo `Gain` 83,234/59,994 µs, 48 kHz mono `Cabin3Notches` 30,720/13,300 µs, and 44.1 kHz stereo `PEQ_Compressor_Limiter` 70,328/67,262 µs, against 21,332/21,332/23,218 µs ceilings. The 44.1 kHz mono matrix passed. Debug native uses `NDK_DEBUG=1`/`-O0`; Release uses `NDK_DEBUG=0`/`-O2`. The variant difference is confirmed, but did not alone explain the variable Debug failures.

## Release-targeted instrumentation follow-up

The Shared library now accepts `-PsharedAndroidTestBuildType=release` for its instrumentation target; without that property, the default remains `connectedDebugAndroidTest`. The generated Release native build for arm64 records `NDK_DEBUG=0`. `scripts/run_android_instrumentation.sh` runs Shared Release and Common Debug in separate Gradle invocations, collects the filtered logs on macOS even when `timeout` is unavailable, then runs the evidence validator.

The updated script passed locally on `Medium_Phone_API_35` (API 35, arm64-v8a): Shared Release 19/19, Common Debug 1/1, zero failures/errors/skips, and the instrumentation evidence validator passed. Its Release DSP log contains 168 rows (56 configurations × three runs); every wall-p99 median gate passed, with maximum median 1,560 µs (`FullHeavyChain`, 48 kHz mono) and maximum single-run p99 2,140 µs, against the unchanged 21,332/23,218 µs ceilings. Hosted run #502 then passed the same Release Shared/Common Debug instrumentation script on the x86_64 API 35 emulator.

Preserve one earlier combined Gradle invocation that ran Shared Release and Common Debug together: it failed three p99 assertions (48 kHz stereo `Convolver64k` 85,825/56,526 µs wall/thread CPU; 44.1 kHz mono `Convolver16k` 38,006/12,720 µs; 44.1 kHz stereo `FullHeavyChain` 41,185/12,700 µs). The cause of this one-run failure is unconfirmed; subsequent separate Shared Release runs passed 19/19. No DSP threshold was changed, and emulator evidence does not establish TS10/K706 performance.

## Acceptance boundary

These host/emulator results do not prove TS10/K706 USB endpoint behavior, supported-iPhone RTSP compatibility, DSP acoustic quality, target CPU/memory/soak, release signing, update/rollback, or independent review. Keep those gates `BLOCKED_DEVICE`, `BLOCKED_ENV` or `NOT_RUN` as recorded in `REMAINING_GATES.md`.
