# STEP4 main baseline

## Source lock

- Repository: `choko510/DiPlay`
- Fixed base: `main` / `origin/main` at `95ba398f95ee104527812cd2b095977d71c51f98`
- Working branch: `codex/step4-main-restart`, created from that exact SHA in a clean worktree.
- Plan: `DiPlay_STEP4_v3_2026-10-18.md`, SHA-256 `7e8f60a216ff769434f09ba922b0bd218859a8aaf1c8554ced9ab1127b92ca73`.
- No open PR implementation or test result is used as source evidence. PR-00 has no production-source changes.

## Build environment

| Item | Observed value |
|---|---|
| Date | 2026-10-21 (Asia/Tokyo) |
| OS | Windows 10, build 26220 |
| JDK | 25.0.2 |
| Gradle wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.3 |
| Kotlin | 2.4.20 |
| SDK | platform 37.1; Build Tools 36.1.0 installed |
| NDK | 27.0.12077973 (shared module pin); 28.2.13676358 is incomplete locally |
| App min/target SDK | 28 / 37 |

This managed worktree does not contain the ignored `local.properties`, and this shell starts without `ANDROID_HOME`. For local Gradle commands set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to `%LOCALAPPDATA%\Android\Sdk`. The `tasks --all` inventory succeeded and confirmed shared/common/mobile/automotive unit, instrumentation, lint, Debug and Benchmark tasks.

## Existing main verification

| Check | Result | Evidence |
|---|---|---|
| Full unit task set | PASS: shared 453 + common 136 = 589 tests; 0 failures, errors or skips. Mobile/automotive unit tasks were `NO-SOURCE`. | `:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:testDebugUnitTest :automotive:testDebugUnitTest`; uncached rerun, 1m37s. |
| Mobile/automotive lint and APKs | PASS: both Debug lint tasks and both Debug/Benchmark assemblies. 340 tasks: 274 executed, 66 up-to-date, 2m26s. | `:mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark` |
| Existing lint findings | Four warnings per app: two `TrustAllX509TrustManager`, one `UnusedAttribute` (`localeConfig`, min SDK 28), and one `UnusedResources` (`config_advanced_audio_channel_mapping`). No lint error. | `mobile/build/reports/lint-results-debug.xml`; `automotive/build/reports/lint-results-debug.xml` |
| Public source and JNI/R8 checks | PASS. | `python scripts/check_public_tree.py`; `python scripts/check_jni_mapping.py` |
| Native DSP, unsanitized | PASS. | `bash scripts/test_dsp_native.sh` under MSYS2 UCRT GCC 15.2 |
| Native DSP, ASan/UBSan | BLOCKED_ENV: the local MSYS2 toolchain cannot link `libasan` or `libubsan`. | `bash scripts/test_dsp_native.sh --sanitize`; hosted sanitizer validation remains required. |
| Main Android instrumentation baseline | PASS: 16/16 methods, 0 failures/errors/skips. | `scripts/run_android_instrumentation.sh` on the connected API 36.1 x86_64 `Medium_Phone_API_36.1` AVD; logcat was cleared immediately before the run. |
| PR-00 PFD fixture instrumentation | PASS: 1/1 bidirectional ParcelFileDescriptor socket-pair test; the full post-fixture run passed 17/17. This validates Android PFD socket-pair I/O only, not VPN/TUN networking. | `TunSocketPairInstrumentedTest` on the same emulator. |
| Existing DSP benchmark | Each run produced 120 fresh records: 40 cases × 3 runs. The largest median-run production-path p99 across two emulator runs was 3.32 ms (`Convolver64k`, 48 kHz, stereo) in run A and 3.38 ms (`Convolver64k`, 44.1 kHz, stereo) in run B. A single-run pipeline p99 reached 3.76 ms in run B. | `shared/build/outputs/dsp-benchmark/logcat.txt` is the latest run; emulator results only. |

The only connected Android device was `emulator-5554` (`sdk_gphone64_x86_64`, Android 16/API 36). No physical TS10, K706 or iPhone USB device was found. USB/NCM, CarPlay, Siri/call, YouTube account isolation, CPU/PSS/GC/thermal, audio quality, A/V synchronization and 30–60 minute soak measurements remain `NOT_RUN`. Emulator results do not satisfy hardware acceptance.

## Main state/configuration snapshot

- NCM `AUTO`: status polling enabled when an endpoint exists, 100 ms pre-ready OUT timeout, asynchronous persistent bulk-IN request. Debug comparators include `NO_STATUS_POLLING` and `SYNC_BULK_IN`; other timeout profiles are recorded in `NcmDiagnosticProfile.kt`.
- Wired startup watchdog: 8 s soft / 15 s hard, armed after the current StartSession path and cancelled by screen-stream open.
- Split UI: `CARPLAY_FULL` default, 55% CarPlay pane default, requested fraction normalized to 50–60%. Display-size callbacks can reach the existing full `restartCarPlay()` fallback.
- AirPlay `/info`: one initial ViewArea by default; no verified dynamic-area command/ack path in this baseline. AirPlay config microphone, HEVC and wireless-audio booleans default false.
- DSP profile repository schema: v3. `DspRuntimeConfig.disabled()` is the default runtime config; full DSP processing remains subject to `DspStreamPolicy`.
- These values are source baselines, not vehicle measurements or evidence that optional debug modes have been compared on hardware.

## Main source anchors rechecked for PR-00

| Contract | Fixed-main source anchor |
|---|---|
| NCM send result and attempt counters | `shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt:197-275` |
| NCM receive pad and block draining | `shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt:486-537`; async/sync reads at `:544-699` |
| NTB16 output pad and bounded parser | `shared/src/main/java/com/shilapi/xcertplay/transport/Ntb16Codec.kt:20-90` |
| Persistent request lifecycle | `shared/src/main/java/com/shilapi/xcertplay/transport/PersistentUsbReadRequestState.kt:4-104` |
| USBMUX/iAP2 read owner | `shared/src/main/java/com/shilapi/xcertplay/transport/IphoneUsbHost.kt:437-566` |
| Wired path opening / watchdog | `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt:1839-1995`, `:2381-2430`; state model `WiredStartupWatchdog.kt` |
| VPN listener generation | `shared/src/main/java/com/shilapi/xcertplay/network/CarPlayVpnService.kt:62-283` |
| ViewArea defaults and command path | `AirPlayConfig.kt:12-33`; `AirPlayInfoPlist.kt:27-150`; `AirPlaySession.kt:154-168, 568-602` |
| Split resize/reconnect/touch | `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt:4175-4235, 4316-4360, 4496-4530`; retained owner at `:4777` |
| Surface/decoder and DSP-off baseline | `AndroidMediaSink.kt:62-120, 416-520, 2300-2335`; `DspProfileRepository.kt:321`; `DspRuntimeConfig.kt:48`; `dsp_graph.c:80`; `DspOffLegacyPcmTest.kt` |

## PR-00 fixture baseline

`Step4TestFixturesTest` passes 15 tests with 0 failures/errors/skips. The final shared/common rerun after adding fixtures passes 468 + 136 = 604 tests with 0 failures/errors/skips. The fixtures exercise explicit USB outcomes, ordinary split/coalesced NTB reads, IPv4/IPv6 TCP SYN/SYN-ACK inputs, 512-byte boundary cases, NCM full/partial/NotReady sends, a scripted request-wait dispatcher, deterministic TUN streams, virtual time, startup MAIN/ALT records, microphone request values, Split geometry, and PCM/video fixture construction. A separate AndroidTest verifies bidirectional ParcelFileDescriptor socket-pair I/O; this is not a production NCM↔VPN bridge test.

Review status: test/data/docs self-review found no production-source diff and no implicit USB success outcomes. No external reviewer was identified or requested; independent Review A/B remain `NOT_RUN` and are not claimed as complete.

The main-only receive baseline has a known PR-01 failure: a 512-byte NTB without its optional pad is not delivered before timeout; if another NTB follows, its first `NTH16` byte is treated as a nonzero required pad and the bridge fails. The padded transfer and a following NTB are accepted. These tests record current-main behavior; they do not claim the unpadded-boundary requirement is already satisfied.
