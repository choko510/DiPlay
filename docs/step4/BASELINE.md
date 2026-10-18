# STEP4 baseline

測定日: 2026-10-18 (JST)
計画書: `DiPlay_STEP4_v3_2026-10-18.md` / SHA-256 `7E8F60A216FF769434F09BA922B0BD218859A8AAF1C8554CED9AB1127B92CA73`
ZIP内の同名計画書は添付Markdownと同一SHA-256。

## Source lock

| 項目 | 値 |
|---|---|
| Repository | `https://github.com/choko510/DiPlay` |
| Base | `main` `95ba398f95ee104527812cd2b095977d71c51f98` |
| `origin/main` | 同じSHA。2026-10-18に `git fetch origin main` 後に照合 |
| Work branch | `codex/step4-20261018-main-95ba398` |
| Initial status | clean。既存PRのbranch・差分・結果は参照していない |
| Modules | `shared`, `common`, `mobile`, `automotive` |
| Git submodules | なし |
| SDK constraints | minSdk 28 / targetSdk 37 / compile SDK 37.1 |

## Environment

| 項目 | 値 |
|---|---|
| OS | Windows 11 Pro Insider Preview, build 26220 |
| PowerShell | 7.6.6 |
| Java | 25.0.2 |
| Gradle Wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.3 |
| Kotlin | 2.4.20 |
| Android SDK | `C:\Users\eita2\AppData\Local\Android\Sdk` |
| Installed compile platform | Android 37.1 (also duplicate `37.1-2`) |
| Build tools | 36.0.0 (installed locally; also 34.0.0 and 36.1.0) |
| NDK | 27.0.12077973; also 28.2.13676358 installed |
| Native ABIs | arm64-v8a, armeabi-v7a, x86_64 |

`gradle/gradle-daemon-jvm.properties` pins Java 25. Local Gradle commands set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the installed SDK. Gradle reports the existing `ndk.dir` deprecation warning (`CXX5106`); it is non-fatal and was not suppressed.

## Baseline verification

| Command | Result |
|---|---|
| `./gradlew tasks --all` | PASS |
| `:shared:testDebugUnitTest :common:testDebugUnitTest` before fixture changes | PASS, shared 453 + common 136 = 589; 0 failures, 0 errors, 0 skipped |
| `:mobile:lintDebug :automotive:lintDebug` | PASS |
| `:mobile:assembleDebug :automotive:assembleDebug` | PASS |
| `:mobile:assembleBenchmark :automotive:assembleBenchmark` | PASS |
| `python scripts/check_jni_mapping.py` | PASS for mobile and automotive benchmark mappings |
| `python scripts/check_public_tree.py` | PASS; no credential containers or private-key blocks |
| `scripts/test_dsp_native.sh` and `--sanitize` | NOT_RUN: host has no `cc`/`gcc`; Windows `bash` reports that no WSL distribution is installed |
| Android instrumentation | NOT_RUN: `adb devices -l` returned no devices; no emulator was running |
| TS10/K706 + iPhone | NOT_RUN: no target device or connection log available in this session |
| CPU/PSS/GC/audio/video timing | NOT_RUN: no target hardware or profiling run |

The workflow in `.github/workflows/android.yml` also runs native DSP tests, API 35 x86_64 instrumentation, and the checks above on Ubuntu. No CI run for this branch exists; local results are not CI evidence.

## Main behavior frozen before changes

- `NcmUsbBridge` has one persistent asynchronous `UsbRequest` state and a synchronous diagnostic mode. A timeout retains the queued request; null, queue failure, and unexpected completion are terminal for that connection.
- `NcmUsbBridge.drainFrames()` currently treats every block length divisible by 512 as requiring one additional zero byte. A complete 512-byte block without that byte therefore waits for more data. This is a source-confirmed gap assigned to PR-01; this baseline does not claim a device reproduction.
- `NcmSendResult` distinguishes `Sent`, `NotReady`, and `Failed`; startup NDP retries are separate from normal TCP writes.
- Wired startup uses the existing 8-second soft diagnostic and 15-second hard watchdog. Host reconnect, controller recovery, and background ownership remain separate mechanisms.
- `common` already contains the in-app 55:45 GeckoView split and per-device browser profile code. PR-06 must preserve it.
- `shared` already contains the stereo DSP pipeline, JNI/native processing, and profile/runtime code. New DSP nodes are separate work and remain disabled until their gates pass.

## PR-00 fixture increment

`Step4FixtureFactoryTest` adds eleven JVM cases for exact-boundary and multi-datagram NTB fixtures, split/coalesced reads, explicit USB outcomes, send result classification, queued-request reuse after timeout, null/unexpected completion handling, virtual scheduling, fake TUN behavior, wired scenario inputs, deterministic PCM data, and AVC/HEVC NAL marker inputs. Video markers exercise queue/key-frame classification; they are not full decoder bitstreams. The constructor's optional USB request factory defaults to the same `UsbRequest()` construction path; it exists only so these tests can exercise the existing bridge with a fake request.

Post-fixture regression: `:shared:testDebugUnitTest :common:testDebugUnitTest` PASS, shared 464 + common 136 = 600; 0 failures, 0 errors, 0 skipped.

The full CI-style Gradle command also passed after fixture implementation: shared/common unit tests, mobile/automotive debug lint, both debug APK assemblies, and both benchmark assemblies. `python scripts/check_jni_mapping.py`, `python scripts/check_public_tree.py`, and `git diff --check` passed.

## PR-00 review record

- Correctness/protocol review: checked that the production default still constructs `UsbRequest()`; the fake defaults to USB OUT failure, request initialization failure, queue failure, and wait timeout; explicit scripts are required for success. Send `NotReady`, partial, and full-byte results are tested separately. The packet builder uses aligned NDP datagrams and adds a short pad only for a 512-byte boundary.
- Android/media static audit: the only production diff is the optional request factory used at first request creation. No USB retry policy, AudioRecord, codec, Surface, GeckoView, profile, or background-owner behavior changed. The Java fake is test-source-only; it uses package-private Android USB constructors to create endpoint/connection doubles.
- Review correction: replaced the initial four-byte video marker with AVC/HEVC NAL marker fixtures used only for queue/key-frame classification; full codec decode remains untested. Added checks for initialized request use and aligned multi-datagram fixture offsets.
- Review evidence: 600 unit tests, Android lint, debug/benchmark builds, JNI mapping, public-tree scan, and whitespace check pass. Native host DSP and device tests are `NOT_RUN` as listed above.
- Reviewer scope: both static perspectives were performed by this agent; no independent second reviewer was available. This is not reported as a completed dual review. No production rollout is claimed.
