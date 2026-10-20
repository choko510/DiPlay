# STEP4 fixed-main baseline

Captured 2026-10-21 (Asia/Tokyo) before any product-runtime change. The source lock is `main` / `origin/main` commit `95ba398f95ee104527812cd2b095977d71c51f98`; implementation is on `codex/step4-v3-pr00-baseline`. The initial source worktree was clean and the repository has no submodules.

The controlling plan is `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`, SHA-256 `7e8f60a216ff769434f09ba922b0bd218859a8aaf1c8554ced9ab1127b92ca73`. The supplied evidence ZIP has SHA-256 `2d22fd9ebef17a05a91883a9519f271b919474265719d91ab9aa2473a31f9dba`; all 17 file digests named by its `SHA256_MANIFEST.txt` match the ZIP entries (the manifest stores basenames for files under `SOURCE_SPECS/` and `LOCAL_PASS17_22/`). This establishes file integrity, not semantic verification of all upstream patches.

## Build and test environment

| Item | Fixed-main value |
|---|---|
| Host OS | Windows 11 Pro Insider Preview, build 26220, x64 |
| Local JDK / Gradle daemon | Oracle JDK 25.0.2 / project-pinned Java toolchain 25 |
| Gradle Wrapper | 9.5.0; the wrapper distribution SHA-256 is pinned in `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin / Kotlin | AGP 9.3.3 / Kotlin 2.4.20 |
| Android modules | `shared`, `common`, `mobile`, `automotive` |
| SDK | `shared` compile SDK 37; `common`, `mobile`, and `automotive` compile SDK 37.1; application target SDK 37 |
| Minimum SDK | 28 (Android 9); API 26/27 USB compatibility is not reachable at the current minimum |
| Java source compatibility | `shared`: 11; `common`, `mobile`, `automotive`: 17 |
| NDK / native ABIs | NDK 27.0.12077973; `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| GeckoView | 156.0.20260921121718 |
| Local SDK | `C:\Users\eita2\AppData\Local\Android\Sdk`; this managed worktree has no `local.properties`, so `ANDROID_HOME` and `ANDROID_SDK_ROOT` were set in each Gradle process only |
| Connected Android target | `emulator-5554`, Medium Phone API 36.1, Android 16, x86_64 |
| Physical target | TS10/K706/iPhone not connected or tested; hardware gates are `NOT_RUN` |

`./gradlew tasks --all` succeeded in 15 seconds. It exposes `:shared:testDebugUnitTest`, `:common:testDebugUnitTest`, both app lint/build tasks, and `:shared:connectedDebugAndroidTest`. Before PR-00, `.github/workflows/android.yml` ran host native and ASan/UBSan, shared/common unit tests, app lint and Debug/Benchmark builds, JNI mapping, and a separate API 35 x86_64 instrumentation job in parallel; it applied the full path to every PR update.

PR-00 adds a fast draft loop: each draft PR update still runs public-tree, host native/sanitizer, and shared/common unit checks; app lint/build/JNI and API 35 instrumentation wait until the PR is marked ready for review. Ready PRs, `main` pushes, and manual workflow runs retain the full gate. Superseded PR runs are cancelled, and Gradle invocations use task output caching and parallel project execution. Actions runs #244 and #245 verified both draft and full paths.

## Required stage order and status at PR-00

| Original/added ID | Baseline status | Planned gate/order |
|---|---|---|
| PR-00 Test infrastructure and baseline | In progress | Freeze the fixed-main source, tests and artifacts before runtime changes |
| EX-01 Wired per-attempt packet provenance | Not started | Observation-only; precedes PR-01 and any network behavior change |
| PR-01 NCM padding and corrupt-data recovery | Not started | After EX-01; preserve USB request ownership |
| EX-02 Host stale retry/second-loss race | Not started | After PR-01; keep controller/host retry ownership single |
| EX-03 watchdog semantics observation | Not started | Instrument MAIN/ALT/control stages without changing 8s/15s behavior |
| EX-04 delayed USB re-enumeration probe | Conditional / not run | Only same node, attempt and generation if device evidence shows it is needed |
| PR-02 original Android 8 USB compatibility | Conditional / not applicable at minSdk 28 | Do not lower minSdk; API28+ queue-false fallback requires a reproduced failure |
| PR-03 original initial NCM OUT delay | Conditional / not run | Compare evidence after EX-01; do not widen timeout without reproducible benefit |
| EX-05 delayed bulk-OUT recovery | Conditional / no-go allowed | Requires the specified SYN→TUN→SYN-ACK→NotReady timing evidence |
| EX-06 status-endpoint/completion ownership | Conditional / not run | Separate AUTO/NO_STATUS A/B; no second `requestWait` owner |
| EX-07 IPv4/IPv6 scope, peer-MAC and listener generation | Conditional / not run | Only fix the source path proved by a concrete device or generation test |
| PR-04 microphone requests and audio regression | Not started | After wired P0 gates; input policy plus Siri/phone/voice-message acceptance |
| PR-06 LOCAL no-restart Split | Not started | Local 55:45 behavior and same-session 20-cycle gate |
| PR-05 ViewArea declaration/protocol | Not started | Keep legacy single-area output until iPhone acceptance is observed |
| PR-06 DYNAMIC ViewArea | Conditional / not run | Only after PR-05 is explicitly accepted by the phone |
| PR-07 Surface/codec recovery and latency | Conditional / not run | Preserve current vendor behavior until capability/failure evidence exists |
| DSP-00..05 | Not started | Isolated from transport/video; preserve DSP-OFF golden and test each module in sequence |
| EX-08 upstream risk register | Mandatory cross-cutting deliverable, not started | Complete the commit-level 1,289-row decisions before PR-08; never bulk-merge upstream |
| PR-08 integrated regression and staged release | Not started; final stage | Only after all unconditional and applicable conditional gates, rollback rehearsal and release evidence |

The table preserves the original PR-00 through PR-08 identifiers; added EX/DSP work does not replace them. Conditional rows stay `NOT_RUN`/`NO_GO_THIS_RUN` until their own evidence gate is satisfied.

## Executed baseline

| Check | Result | Scope and limits |
|---|---|---|
| `:shared:testDebugUnitTest :common:testDebugUnitTest` before PR-00 fixtures | PASS, 589 tests (453 shared + 136 common), 0 failures/errors/skips, 1m44s | Pre-change fixed-main baseline |
| `scripts/test_dsp_native.sh` | PASS, 6.9s | MSYS2 UCRT GCC 15.2.0; host native suite only |
| `scripts/test_dsp_native.sh --sanitize` | `BLOCKED_ENV` | MSYS2 linker lacks `libasan` and `libubsan`; this is not a product test failure. Hosted sanitizer CI is still required |
| `scripts/run_android_instrumentation.sh` | PASS, 16/16 methods, 0 failures/skips; 55s before and 41s after adding `--build-cache --parallel` | Local API 36.1 x86_64 emulator, not API 35 CI or a physical USB device. The two durations used different warmed states and do not isolate the flags' effect |
| DSP instrumentation matrix | PASS, 40 configurations × 3 repetitions | The latest 120 benchmark rows measured 44.1/48 kHz mono/stereo; highest median production p99 was 3.28 ms (`Convolver64k`, 44.1 kHz stereo). Emulator-only measurement |
| `:mobile:lintDebug`, `:automotive:lintDebug`, both Debug and Benchmark builds | PASS, 2m03s | Both Debug lint XML reports contain 0 issues |
| `python scripts/check_jni_mapping.py` | PASS | Both optimized application mappings retain required JNI names |
| `python scripts/check_public_tree.py` | PASS | No tracked credential containers/private-key blocks |
| `git diff --check` | PASS | Working diff at the time of the check |
| Draft PR CI run #244 | PASS, 4m08 | Public-tree, native, hosted ASan/UBSan and shared/common unit checks passed; full host build/lint/JNI and instrumentation were skipped |
| Ready/full PR CI run #245 | PASS, 10m24 overall | Host check passed in 10m20 (Gradle verification 9m26); parallel API 35 x86_64 instrumentation passed in 5m05 |

The PR-00 fixture addition contains eight new shared unit tests. After that addition, the current report totals are 461 shared + 136 common = 597 tests, all passing with 0 failures/errors/skips. The new Robolectric tests validate only scripted test doubles and the existing NCM `recv()` path; they do not establish Android USB-controller behavior.

## Existing diagnostic, settings, and behavior baseline

- `NcmDiagnosticProfile.AUTO` is the production default: asynchronous persistent bulk-IN, status polling when an interrupt endpoint exists, and the existing 100 ms pre-ready bulk-OUT timeout. Alternate NCM profiles can be saved only in debuggable builds.
- `WiredStartupWatchdog` already has an 8-second soft diagnostic and 15-second hard teardown. The controller currently completes it on `SCREEN_STREAM_OPENED`; PR-00 changes neither deadline nor completion condition.
- The app's debug-log preference defaults off. When enabled it is passed to the controller's connection-trace recorder. The bounded trace is stored in app-private `files/logs/connection-trace.log` with three rotated files; export also includes display diagnostics and session logs. Existing NCM events include `NCM_USB_READ`, `NCM_TX`, and `NCM_AB_RESULT`. They do not yet provide EX-01's per-packet NCM→TUN→TCP→USB-OUT provenance.
- The global DSP master defaults off and the selected profile defaults to `flat`. Full DSP processing is restricted by `DspStreamPolicy`; the existing schema and PCM/latency behavior remain part of the regression baseline.
- `HostLayoutState` starts in full-screen CarPlay; the current in-app split defaults to 55% CarPlay. `/info` currently emits one ViewArea and initial index 0; this does not prove dynamic ViewArea acceptance.
- Current diagnostics can be captured on an attached Android target with `adb logcat -b all -v monotonic`; `-b all` includes the circular log buffers and `monotonic` timestamps are relative to boot. Keep raw captures local and redact them before sharing because system logs may contain identifiers or other private data. See [Android Logcat documentation](https://developer.android.com/tools/logcat).

## PR-00 boundary

The PR-00 diff changes test fixtures, test documentation, and CI feedback configuration. Production behavior changes are zero. The emulator and host checks above are the reproducible baseline; wired TS10/K706/iPhone behavior, microphone/Siri/phone acceptance, multi-ViewArea protocol acceptance, target DSP acoustics, and release rollback/signing remain unmeasured.
