# PR-00 baseline from fixed main

Recorded 2026-10-20 (Asia/Tokyo) on the dedicated PR-00 worktree.

## Source lock

- Implementation source: `choko510/DiPlay` fixed fork-main SHA `95ba398f95ee104527812cd2b095977d71c51f98`.
- Specification: `DiPlay_STEP4_v3_2026-10-18.md`, SHA-256 `7e8f60a216ff769434f09ba922b0bd218859a8aaf1c8554ced9ab1127b92ca73`.
- PR-00 worktree and branch start at that main SHA. Open PR implementations and test results were excluded from source and evidence.
- PR-00 changes only test fixtures and documentation. Production source, runtime flags, NCM behavior, DSP behavior, and host UI remain unchanged.

## Environment

| Item | Observed value |
|---|---|
| Host | Windows 11 Pro Insider Preview, build 26220, x64 |
| Launcher JVM | Oracle JDK 25.0.2; daemon criteria require Java 25 |
| Gradle / Android Gradle Plugin / Kotlin plugin | Gradle 9.5.0 / 9.3.3 / 2.4.20 |
| SDK | compileSdk 37; common/mobile/automotive use API 37.1 minor level 1; minSdk 28; targetSdk 37 |
| NDK / ABIs | NDK 27.0.12077973; arm64-v8a, armeabi-v7a, x86_64 |
| Unit-test framework | JUnit 4.13.2 and Robolectric 4.17 |
| Emulator | Medium Phone API 36.1 AVD, x86_64, Android reports API 36 / release 16 |
| Physical TS10/K706 + iPhone | `NOT_RUN`; ADB exposed only the emulator |

The worktree has no `local.properties` and inherits no Android SDK environment variables. Gradle commands used the installed SDK via `ANDROID_HOME`/`ANDROID_SDK_ROOT`. The SDK contains a duplicate API 37.1 folder and an unrelated NDK 28.2 folder without `source.properties`; AGP emitted duplicate-platform/NDK warnings, while builds succeeded using the project-pinned NDK 27.

## Fixed-main runtime defaults

| Area | Source-confirmed default |
|---|---|
| NCM | `NcmDiagnosticProfile.AUTO`; status polling when an endpoint is present; 100 ms pre-ready OUT timeout; asynchronous bulk IN; no forced interface pair. Saved profile selection is debug-build-only. |
| NCM diagnostic detail | Wired NCM diagnostic detail is enabled for debuggable builds and disabled for non-debuggable builds. PR-00 introduces no new recorder. |
| DSP | Profile repository schema 3; selected profile defaults to `flat`; the global `enabled` preference defaults to false, so effective runtime configuration starts disabled. New STEP4 DSP nodes are absent from main. |
| YouTube/Split | Host layout defaults to `CARPLAY_FULL`; entering Split uses a 0.55 CarPlay fraction. Split is action-triggered; this is not evidence of same-session device continuity. |
| Split tracing | `SplitPerformanceTracer` defaults to disabled and is debuggable-only. |

No real user preference files, identities, cookies, credentials, or physical-device diagnostic exports were read. These values are defaults from fixed-main source, not settings captured from a user's device.

## Baseline commands and results

Commands ran in PowerShell from the PR-00 worktree with the installed Android SDK environment set for each invocation.

| Check | Result |
|---|---|
| `gradlew tasks --all` | `PASS`; required unit, lint, Debug, Benchmark, and connected-test tasks exist. |
| `:shared:testDebugUnitTest :common:testDebugUnitTest` before PR-00 fixtures | `PASS`; shared 453 + common 136 = 589 tests; zero failures, errors, or skips. |
| Same unfiltered unit tasks after PR-00 fixtures | `PASS`; shared 464 + common 136 = 600 tests; zero failures, errors, or skips. |
| `:mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark` | `PASS`; both Debug lint and Debug/Benchmark assemblies succeeded. Each lint report has 4 warnings and 0 errors: `UnusedAttribute` (`localeConfig`), two `TrustAllX509TrustManager` entries, and `UnusedResources` (`config_advanced_audio_channel_mapping`). No warning was suppressed. |
| `python scripts/check_jni_mapping.py` | `PASS`; mobile and automotive Benchmark R8 mappings preserve expected JNI names. |
| `python scripts/check_public_tree.py` | `PASS`; no credential containers or private-key blocks found. |
| `scripts/test_dsp_native.sh` in the MSYS2 UCRT64 shell | `PASS`; unsanitized host native DSP suite. |
| `scripts/test_dsp_native.sh --sanitize` in the MSYS2 UCRT64 shell | `BLOCKED_ENV`; linker cannot find `-lasan` or `-lubsan`. |
| `scripts/run_android_instrumentation.sh` on the API 36.1 AVD | `EMULATOR_PASS`; 16/16 tests, 0 skipped/failed. Before capture, only the emulator's logcat buffer was cleared to exclude stale prior-run metrics. |
| DSP benchmark capture from that AVD run | 120 records: 10 cases × 44.1/48 kHz × mono/stereo × three rounds. Maximum median-run production p99 was 3.48 ms (`Convolver64k`, 48 kHz stereo). Full filtered records are in [DSP_BASELINE_LOGCAT.txt](DSP_BASELINE_LOGCAT.txt). Main does not include Harmonic Engine; no `HARMONIC_ALIAS` records are expected. |
| `git diff --check` | `PASS` before changes and after the PR-00 fixture additions. |

The connected tests are emulator-only. They do not exercise physical NCM, USB request behavior, iPhone ViewArea acceptance, car audio routing, or head-unit performance.

## Not run / environment limits

- TS10/K706+iPhone wired connection, reconnect, Siri/Phone/voice-message capture, physical ViewArea, same-session 20-cycle Split, target CPU/PSS/GC/thermal/soak, and rollback/update/signature rehearsal: `NOT_RUN`.
- Host ASan/UBSan is `BLOCKED_ENV`; hosted CI is the next sanitizer authority after the PR is opened.
- The main baseline contains no Harmonic Engine/alias-measurement instrumentation; those acceptance IDs belong to later DSP stages.
