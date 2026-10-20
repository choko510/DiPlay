# PR-00 — Fixed-Main Baseline

Recorded 2026-10-20 (JST) on `codex/step4-pr-00-baseline-v3`, created from fork `origin/main` at `95ba398f95ee104527812cd2b095977d71c51f98`. The v3 target SHA matches. No pre-existing open PR branch, implementation, test result, or review was used as evidence. PR-00 adds test fixtures, baseline documents, and CI-only cache/path tuning; it changes no application production source, runtime behavior, or default.

## Build and host environment

| Item | Observed fixed-main value |
|---|---|
| Host | Windows 11 Pro Insider Preview, build 26220, AMD64 |
| Java | Oracle JDK 25.0.2; Gradle daemon toolchain version 25 |
| Gradle Wrapper | 9.5.0 |
| Android Gradle Plugin / Kotlin | AGP 9.3.3 / Kotlin 2.4.20 |
| Unit test libraries | JUnit 4.13.2 / Robolectric 4.17 |
| Modules | `shared`, `common`, `mobile`, `automotive` |
| SDK targets | shared compileSdk 37; common/app compileSdk 37.1; app minSdk 28, targetSdk 37 |
| Native build | shared pins NDK 27.0.12077973; ABIs arm64-v8a, armeabi-v7a, x86_64 |
| Unit-test sources | `shared` and `common`; `mobile` and `automotive` have no `src/test` trees |
| Instrumentation sources | Two `shared/src/androidTest` classes: native DSP bridge and DSP pipeline benchmark |

The SDK resolves from local `local.properties`. Installed SDK platforms include API 33, 34, 35, 36.1, 37.0, and 37.1. Local system image: API 36.1 Google Play x86_64; AVD: `Medium_Phone_API_36.1`. No physical ADB device was connected.

## Executed results

| Command / gate | Result | Exact scope |
|---|---|---|
| `./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest --rerun-tasks --stacktrace` | `PASS_LOCAL` | 470 shared + 136 common tests; 606 total, 0 failures, 0 errors, 0 skips; 1m19s |
| PR-00 `NcmUsbBridgeFixtureTest` | `PASS_LOCAL` | 10 tests for explicit bulk-IN/OUT, queue/wait outcomes, sync/async parity, pad/no-pad boundary, and typed OUT results |
| PR-00 `Step4FixtureCatalogTest` | `PASS_LOCAL` | 7 tests for malformed/split/coalesced NTB inputs, startup traces, TUN, virtual time/scheduler, PCM/video, local Split, and input-request fixtures |
| `scripts/test_dsp_native.sh` | `PASS_LOCAL` | Host DSP C suite using MSYS2 UCRT64 GCC 15.2.0 |
| `scripts/test_dsp_native.sh --sanitize` | `BLOCKED_ENV` | Local linker lacks `-lasan` and `-lubsan`; main GitHub workflow retains the hosted ASan/UBSan gate |
| Mobile/automotive Debug lint and Debug/Benchmark assemblies | `PASS_LOCAL` | Main workflow host task set; 1m53s, 340 actionable tasks (148 executed, 192 up-to-date) |
| `python scripts/check_jni_mapping.py` | `PASS_LOCAL` | Mobile and automotive Benchmark R8 mapping files verified |
| `python scripts/check_public_tree.py` | `PASS_LOCAL` | No credential containers or private-key blocks found |
| `scripts/run_android_instrumentation.sh` | `PASS_EMULATOR` | Medium_Phone_API_36.1 AVD: 16/16 tests, 0 skipped/failed; 120 DSP benchmark records = 40 configurations × 3 runs; benchmark-log assertion passed |
| GitHub CI for PR #47 | `PASS_CI` | Run #188 on head `0a5c886d` passed host checks and API 35 instrumentation. Run #190 on final head `fbd166e` passed host tests/lint/Debug builds; optimized APK/R8 and emulator jobs were correctly skipped because no relevant native paths changed. |
| TS10/K706 + iPhone physical acceptance | `NOT_RUN` | AVD evidence is not head-unit evidence |

## Review status

Review A / author static review: `PASS_SELF_REVIEW` for scope, ownership, explicit scripted outcomes, no application production changes, and honest characterization of the current no-pad defect. Review B / independent reviewer: `NOT_RUN` per user direction; no dual-review claim is made. Supplementary evidence is the full unit/host/emulator run #188 and final-head host CI run #190. Keep this work in draft; it is not approved for production rollout.

## Environment warnings and limits

- SDK has both `platforms/android-37.1` and `platforms/android-37.1-2`; AGP reports a duplicate location, but builds/tests pass.
- Local `ndk.dir` selects 27.0.12077973; mobile/automotive emit CXX1104 against AGP's default 28.2.13676358. Debug/Benchmark builds still pass.
- AGP cannot strip `libxcertplay_dsp.so` locally and packages it unstripped; assemblies succeed.
- Robolectric/Conscrypt on JDK 25 emits restricted-native-access warnings; the complete unit suites pass.
- The local emulator binary/API 36.1 image are installed; API 35 image is absent. Local instrumentation used API 36.1. GitHub CI is configured for API 35.
- Test-only MSYS2 calls must invoke `C:\msys64\usr\bin\bash.exe` with `/usr/bin:/ucrt64/bin` before inherited Windows PATH; the default `bash` resolution selects a WSL shim without a default distribution.

## Main-source behavior frozen by PR-00

- `NcmUsbBridge.send()` distinguishes typed `Sent`, `NotReady`, and `Failed`; the async read path owns one persistent `UsbRequest` and leaves it queued after timeout.
- `NcmUsbBridge.drainFrames()` waits for a zero pad whenever an NTB16 length is exactly divisible by 512. PR-00 records a complete no-pad 512-byte block as not delivered; PR-01 owns changing that expected behavior.
- The watchdog policy uses an 8-second soft snapshot and 15-second hard expiry. PR-00 changes no timeout/retry behavior.
- `NcmDiagnosticProfile.AUTO` polls an available status endpoint; PR-00 preserves the existing default and completion owner.
- Main retains the in-app 55:45 YouTube/CarPlay layout, existing audio/DSP behavior, and process-local background session ownership.
- A complete OEM-only source audit is not part of PR-00. Main still contains references such as the BYD car-settings package fallback and a few driver-workaround notes; they are recorded for later PR-08 review, not treated as a clean-tree proof or expanded by PR-00.

Target USB timing, TUN/TCP causality, Siri/Phone/voice-message routing, remote ViewArea acceptance, same-session 20-cycle Split, target DSP acoustics/CPU/memory, long soak, APK update/signature, and physical rollback remain `NOT_RUN`.
