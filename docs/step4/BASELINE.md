# STEP4 Baseline — PR-00

## Source lock

- Implementation baseline: `choko510/DiPlay` fork `main`, SHA `95ba398f95ee104527812cd2b095977d71c51f98`.
- Work branch: `codex/step4-pr-00-test-baseline`, created directly from `origin/main`. The worktree was clean at stage start.
- The separate v3 plan and the ZIP's formal plan have identical SHA-256 `7e8f60a216ff769434f09ba922b0bd218859a8aaf1c8554ced9ab1127b92ca73`; the ZIP manifest agrees.
- Existing pull requests and their branches were excluded from source, test, and completion evidence. No upstream code was merged.

## Environment

| Item | Observed value |
|---|---|
| Host | Windows 11 x64, PowerShell |
| Gradle wrapper | 9.5.0 |
| Gradle daemon JDK | Oracle JDK 25.0.2 |
| Android Gradle Plugin / project Kotlin plugin | 9.3.3 / 2.4.20 |
| Gradle modules | `shared`, `common`, `mobile`, `automotive`; no Git submodules |
| minSdk / targetSdk | 28 / 37 |
| Shared compile SDK / NDK pin | Android 37 / NDK 27.0.12077973 |
| Locally installed Android SDK platforms | 33, 34, 35, 36.1, 37.0, 37.1, duplicate 37.1-2 |
| Connected Android target | API 36.1 x86_64 AVD (`sdk_gphone64_x86_64`, Android 16) |
| Physical TS10/K706/iPhone | Not connected; all hardware gates are `NOT_RUN` |

The shared module pins NDK 27.0.12077973. The local `ndk.dir` also selects NDK 27, while app modules resolve the locally installed 28.2.13676358 as their declared/default NDK. Baseline app builds emit CXX1104 and report that `libxcertplay_dsp.so` cannot be stripped, then package it unstripped. This environment warning is recorded for PR-08; PR-00 does not alter build configuration.

## Base-main baseline runs

| Command/check | Result | Evidence scope |
|---|---|---|
| `:shared:testDebugUnitTest :common:testDebugUnitTest --rerun-tasks` | `PASS_LOCAL`, shared 453 / common 136, zero failures/errors/skips | Fixed main SHA, forced Gradle rerun |
| `:shared:connectedDebugAndroidTest` | `PASS_EMULATOR`, 16/16 | API 36.1 x86_64 AVD only |
| Mobile/automotive `lintDebug`, `assembleDebug`, `assembleBenchmark` | `PASS_LOCAL` | No release signing or physical install exercise |
| `python scripts/check_jni_mapping.py` | `PASS_LOCAL` | Both optimized Benchmark mappings |
| `python scripts/check_public_tree.py` | `PASS_LOCAL` | No tracked credential containers/private-key blocks |
| MSYS2 UCRT GCC 15.2 `bash scripts/test_dsp_native.sh` | `PASS_LOCAL` | Unsanitized host C suite |
| MSYS2 UCRT GCC `bash scripts/test_dsp_native.sh --sanitize` | `BLOCKED_LOCAL` | Linker reports missing `libasan` and `libubsan`; not a pass |
| GitHub combined status for main SHA | No status records returned | Absence of status is not a CI pass |
| Connected physical hardware | Not available | `NOT_RUN`, not a pass |

The instrumented `FullHeavyChain` case provides an emulator baseline (three 256-block runs at 128 frames). Percentiles are medians across runs; `max` is the largest individual block. Production timing includes latency-preserving bypass.

| Rate/channel | Native p50 / p95 / p99 / max (µs) | Production p50 / p95 / p99 / max (µs) |
|---|---:|---:|
| 44.1 kHz mono | 800 / 880 / 1,040 / 1,207 | 1,040 / 1,140 / 1,340 / 1,508 |
| 44.1 kHz stereo | 1,400 / 1,500 / 1,760 / 1,845 | 1,860 / 2,000 / 2,300 / 2,466 |
| 48 kHz mono | 800 / 840 / 1,000 / 1,285 | 1,040 / 1,120 / 1,340 / 1,568 |
| 48 kHz stereo | 1,400 / 1,540 / 1,720 / 1,898 | 1,860 / 2,060 / 2,280 / 2,695 |

These are emulator timing samples, not vehicle or sound-quality evidence.

## Main behavior and initial risk record

- `NcmUsbBridge` owns the USB connection after its open path succeeds. Its asynchronous bulk-IN route uses one persistent `UsbRequest`; a timeout keeps it queued. A separate status thread uses `bulkTransfer` when the selected profile and a status endpoint permit it.
- `NcmDiagnosticProfile.AUTO` is the production default: 100 ms pre-ready OUT timeout, asynchronous bulk IN, and status polling when a status endpoint is present. Saved NCM profiles are read only in debuggable builds. `NO_STATUS_POLLING` is the existing debug A/B comparator.
- `NcmLinkReadiness` separates RX proof, TX proof, and control-plane readiness. TCP accept, RTSP active, MAIN setup, and first frame are distinct events.
- `CarPlayHostActivity.restartAfterReconnectDelay` validates the stored UI `restartGeneration`; recovery/loss epoch ownership is not separately represented at that timer boundary. EX-02 owns the regression test before any behavior change.
- `NcmUsbBridge.drainFrames()` currently waits for a one-byte short-packet pad when an NTB block length is exactly divisible by 512. PR-00's Robolectric fixture characterizes this as a known defect; PR-01 owns changing the test expectation and implementation.
- DSP master enable defaults to false. The main profile schema is v3 and the existing two-channel Float32/legacy-PCM path is the baseline.
- Local Split defaults to full CarPlay with a 55% CarPlay fraction clamped to 50–60%. `/info` describes one view area per configured display; an optional cluster display is a separate display entry, not a dynamic MAIN view-area switch.
- No `WIRED_PATH_V1` per-attempt packet provenance record exists in main. Existing NCM aggregate diagnostics and generic connection trace stages do not provide the same causal timeline.

## PR-00 harness additions

PR-00 adds tests and documentation only; production behavior is unchanged. `NcmUsbBridgeFixtureTest` uses test-only Robolectric shadows for USB connection, endpoint, request and `requestWait` outcomes. `Step4TestFixtures` provides reusable NTB, IP/TCP, TUN, virtual-time, microphone-input, local Split, PCM16, and video fixtures for subsequent dependent stages. After those additions the full forced unit rerun passed at shared 464 / common 136 (zero failures/errors/skips); the 11 new fixture/bridge tests also pass when selected directly.
