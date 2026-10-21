# PR-00 main baseline

## Source lock

- Repository: `choko510/DiPlay`
- Base and measured source SHA: `95ba398f95ee104527812cd2b095977d71c51f98`
- Work branch: `codex/step4-main-v3`
- Initial worktree status: clean; no submodules are initialized.
- Modules: `shared`, `common`, `mobile`, `automotive`.
- No implementation or test result from another branch or open PR is used as evidence in this baseline.

## Build environment

| Item | Measured value |
|---|---|
| Host | Windows PowerShell worktree |
| JDK | 25.0.2 |
| Gradle Wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.3 |
| Kotlin | 2.4.20 |
| Compile SDK | Android 37.1 |
| Mobile / automotive minSdk | 28 |
| Mobile / automotive targetSdk | 37 |
| NDK pinned by `shared` | 27.0.12077973 |
| Native ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| Emulator | `Medium_Phone_API_36.1`, API 36 / Android 16, `sdk_gphone64_x86_64` |
| Physical TS10/K706/iPhone/USB target | `NOT_RUN`; no physical target was attached |

The installed SDK also contains an incomplete NDK 28.2 folder. AGP emitted `CXX1101` warnings while scanning it; the project-pinned NDK 27 builds completed successfully. MSYS2 GCC does not have `libasan` or `libubsan`.

No TS10/K706 failure trace or personal app preferences were captured or modified for PR-00. `FEATURE_FLAGS.md` records source-level defaults only; current installed-release audio/DSP/YouTube settings and physical-device logs remain `NOT_RUN`.

## Baseline commands

All commands below ran from the clean main-based worktree. Unit and build tasks used `ANDROID_HOME` and `ANDROID_SDK_ROOT` pointing to the installed SDK.

| Command | Result | Evidence |
|---|---|---|
| `./gradlew tasks --all` | `PASS`, 6s | Registered shared/common unit and instrumentation tasks, both app lint tasks, Debug/Benchmark assembly and connected Android tests. |
| `./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest` | `PASS`, 1m24s | Shared 453 + common 136 = 589 tests; 0 failures, 0 errors, 0 skipped. JUnit XML reports were read from both module build directories. |
| `bash scripts/test_dsp_native.sh` | `PASS` | MSYS2 UCRT GCC; native DSP executable reported `native DSP tests passed`. |
| `bash scripts/test_dsp_native.sh --sanitize` | `BLOCKED_ENV` | Linker reports `cannot find -lasan` and `cannot find -lubsan`; hosted sanitizer verification remains necessary. |
| Mobile/automotive Debug lint and Debug/Benchmark assemblies | `PASS`, 1m49s | `:mobile:lintDebug`, `:automotive:lintDebug`, `:mobile:assembleDebug`, `:automotive:assembleDebug`, `:mobile:assembleBenchmark`, and `:automotive:assembleBenchmark`; 340 Gradle tasks, 304 executed, 36 up-to-date. |
| `python scripts/check_jni_mapping.py` | `PASS` | Benchmark R8 mappings preserved the JNI class and constructor in both app variants. |
| `python scripts/check_public_tree.py` | `PASS` | No credential containers or private-key blocks found. |
| `scripts/run_android_instrumentation.sh` | `PASS` on emulator | `:shared:connectedDebugAndroidTest`: 16 tests, 0 failures, 0 errors, 0 skipped. Three marker-scoped reruns took 36–51s and each captured 120 fresh `DSP_BENCHMARK` records. |

The first instrumentation invocation also passed 16/16 in 48s. A later retry initially found no connected device after the emulator had exited; the API 36.1 AVD was restarted and the marker-scoped run then passed. The first pre-marker logcat file included records from before that invocation, so its performance lines are excluded from the baseline below.

## Emulator DSP measurement

The existing instrumentation produced 120 records per run, with three runs for each measured case/configuration. These are emulator pipeline timings, not TS10/K706 measurements. Median-run production p99 maxima from three fresh full matrices were:

| Configuration / run maximum | Run 1 | Run 2 | Run 3 |
|---|---:|---:|---:|
| `Convolver64k`, 48 kHz stereo | 8.78 ms | 3.04 ms | 7.24 ms |
| `FullHeavyChain`, highest configuration | 8.04 ms (48 kHz stereo) | 2.34 ms (44.1/48 kHz stereo) | 5.26 ms (44.1 kHz mono) |

The repeated fresh runs show substantial emulator timing variation; do not use the lowest run as a target-performance pass. CPU/PSS/GC, A/V sync, audio quality, and physical head-unit performance remain `NOT_RUN`.

## Existing CI path on main

`.github/workflows/android.yml` currently runs public-tree validation, Java/Android SDK and NDK setup, native DSP plus ASan/UBSan, shared/common unit suites, both app Debug lint tasks, both Debug/Benchmark builds, and JNI mapping on every pull request. A separate job builds and boots an API 35 emulator for connected instrumentation. The workflow has no draft fast lane, changed-path gate, or pull-request cancellation group. The local timings above are task timings; no hosted main-baseline workflow run was used to claim an end-to-end CI duration.

The existing lint baseline is four findings per app: two `TrustAllX509TrustManager` dependency findings, one `UnusedAttribute` for `localeConfig` below API 33, and one `UnusedResources` for `config_advanced_audio_channel_mapping`. These are recorded as baseline findings and are not suppressed in PR-00.

## PR-00 fixture regression on `codex/step4-main-v3`

After adding only test fixtures, documentation, and CI scripts (no production source changes), the incremental full task invocation completed in 13 seconds with 464 shared + 136 common = 600 tests, 0 failures/errors/skips. It reran the full shared suite; unchanged common results were reused from the preceding uncached 589-test run. The eleven added fixture tests are `Step4TestFixturesTest` (5) and `NcmUsbBridgeFixtureTest` (6). The latter drives the actual main `NcmUsbBridge` constructor through scripted Robolectric USB shadows, verifies the 32 KiB read buffer, timeout/request reuse, queue rejection, null/unexpected completion, close-during-wait cancellation, typed OUT outcomes, and the READ_QUEUED → FIRST_USB_COMPLETION → FIRST_ETHERNET_RX event order.

The eleven `scripts/test_classify_android_ci.py` unit cases pass, including static-root/source mixes, same-repository and fork-head compare URL construction, and API-failure fallback. `bash -n` passes for both `scripts/run_android_instrumentation.sh` and `scripts/test_dsp_native.sh`. The marker-scoped instrumentation script passes 16/16 on the API 36.1 emulator and captures 120 benchmark records from the current run only. The Android host workflow optimization has not yet been measured on a hosted Actions run; its CI result is pending the PR-00 draft run.

## PR-00 review record

- Review A (protocol/security/test ownership): the fixture refuses unconfigured endpoints and queue/wait/OUT/TUN outcomes; timeout keeps one persistent request; null, unexpected completion, partial OUT and full OUT remain distinct. No production source or transport policy changed.
- Review B (Android/product/CI): build/lint/R8 and emulator evidence are recorded at their actual scope; static/draft/full CI lanes retain the full fallback for unknown paths, compare API errors, or large diffs. Instrumentation benchmark output is limited to the current run without clearing prior logcat history.
- Review fixes: missing endpoint configuration now fails; missing USB/TUN outcomes cannot imply success; invalid/unrecognized changed paths run full verification; cross-fork compare refs qualify both owners; stale benchmark logcat records are filtered out.
- Independent reviewer B: `NOT_RUN` per the user's earlier direction; no reviewer requests were sent. The two local review passes are not represented as independent external reviews. PR-00 remains draft and is not a release approval.
