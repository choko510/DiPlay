# STEP4 PR-00 main baseline

## Source lock

- Repository: `choko510/DiPlay`.
- Fixed implementation base: fork `main` at `95ba398f95ee104527812cd2b095977d71c51f98`.
- Comparison reference only: upstream `main` at `6d3b8a8ce927cb4b115b8c3881f17619433e93b1`.
- Work branch: `codex/step4-main-only-pr00`, created from the fixed fork main SHA. The pre-change worktree was clean. No existing open PR branch, code, test, or result is used as implementation evidence.
- PR-00 changes are test infrastructure, test-only fixtures, baseline records, and the instrumentation log-capture script. No production Kotlin/JNI source is changed.

## Project and build environment

| Item | Fixed-main baseline |
|---|---|
| Modules | `shared`, `common`, `mobile`, `automotive`; no git submodules |
| Gradle wrapper | 9.5.0 (`gradle-9.5.0-bin.zip`, SHA-256 pinned by wrapper properties) |
| Android Gradle Plugin | 9.3.3 |
| Kotlin | 2.4.20 |
| JDK | 25.0.2 LTS |
| Host | Windows 11 Pro Insider Preview, build 26220 |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`; this worktree has no ignored `local.properties` |
| Compile/target/min SDK | `shared`: compile 37, target 37, min 28; apps compile 37.1, target 37, min 28 |
| Native build | shared pins NDK 27.0.12077973; ABIs `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| App versions | mobile `0.2.0` / code 20; automotive `1.2.1` / code 1201 |
| CI | Ubuntu host job runs unit tests, native DSP, ASan/UBSan, lint, Debug/Benchmark builds and JNI mapping; a separate API 35 emulator job runs connected tests |

Only `emulator-5554` (Medium Phone, API 36.1, x86_64) was connected to ADB. No TS10/K706 head unit, iPhone/iOS device, physical USB endpoint descriptor, or vehicle audio route was available. Physical acceptance is `NOT_RUN`.

## Executed baseline and PR-00 verification

| Check | Result | Evidence scope |
|---|---|---|
| `./gradlew tasks --all` | `PASS` | Unit, connected-test, lint, Debug, Benchmark, and release tasks resolve under the fixed wrapper/plugin versions |
| Fixed-main JVM baseline | `PASS_LOCAL` — 453 shared + 136 common = 589 tests; 0 failures/errors/skips | Before adding PR-00 fixture tests |
| PR-00 full JVM suite | `PASS_LOCAL` — 464 shared + 136 common = 600 tests; 0 failures/errors/skips | `:shared:testDebugUnitTest :common:testDebugUnitTest --rerun-tasks --parallel`; current new shared fixture tests executed, common tests reran |
| `scripts/run_android_instrumentation.sh` | `PASS_EMULATOR` — 16/16, 0 failures/skips | Medium Phone API 36.1 x86_64; not a physical USB/VPN test |
| DSP benchmark capture | `PASS_EMULATOR` — 120 run-scoped records, 40 case/rate/channel combinations, 3 runs each, 256 blocks per run | Capture run ID `20261021T145002-1147-20079`; only `DSP_BENCHMARK` lines between unique start/end markers are retained. Most cases report `productionProcessUs=not-applicable`; this is not a head-unit performance result. |
| `scripts/test_dsp_native.sh` | `PASS_LOCAL` | MSYS2 UCRT GCC 15.2, unsanitized host DSP C test |
| `scripts/test_dsp_native.sh --sanitize` | `BLOCKED_ENV` | Local MSYS2 linker lacks `libasan` and `libubsan`; the exact fixed-main GitHub Actions run below passed its Ubuntu ASan/UBSan step |
| Mobile + automotive Debug lint | `PASS_LOCAL` | Four existing warnings each: two `TrustAllX509TrustManager`, one `UnusedAttribute`, one `UnusedResources`; no lint errors |
| Mobile + automotive Debug/Benchmark builds | `PASS_LOCAL` | Built sequentially after the combined local build hit C-drive space pressure; NDK 28.2 `source.properties` and native strip warnings were nonfatal |
| `python scripts/check_jni_mapping.py` | `PASS_LOCAL` | Both optimized Benchmark mappings preserve the native JNI entry points |
| `python scripts/check_public_tree.py` | `PASS_LOCAL` | No credential containers or private-key blocks in the public tree |
| GitHub Actions run #101 on exact main SHA | `PASS_CI` | Both `check` and `android-instrumentation` jobs succeeded on `95ba398`; the check job includes native, ASan/UBSan, unit, lint, Debug/Benchmark, and JNI steps |

On the API 36.1 emulator, the production-path DSP benchmark's median run p99 was 2.54 ms for `FullHeavyChain` at 44.1 kHz stereo and 2.62 ms at 48 kHz stereo. `Convolver64k` was 3.50 ms and 3.48 ms respectively. These values describe this emulator and current graph only; they are not TS10/K706 targets or acceptance results.

## Existing behavior frozen by PR-00

- `NcmUsbBridge` owns the NCM `UsbDeviceConnection`, serializes bulk OUT, and uses one persistent 32 KiB async bulk-IN `UsbRequest` by default. A request-wait timeout keeps the same request queued; null and unexpected completions fail the current transport.
- `NcmSendResult` distinguishes `Sent`, `NotReady`, and `Failed`. A bulk-OUT result of zero/negative is `NotReady`; a partial transfer is a typed failure. PR-00 adds tests around these contracts, not a retry policy.
- `NcmDiagnosticProfile.AUTO` keeps async bulk IN, 100 ms pre-ready OUT timeout, status polling when a status endpoint exists, and no forced function pair. Saved profile selection is debug-build-only.
- The existing wired startup watchdog remains 8-second soft / 15-second hard. PR-00 does not change its deadlines or completion event.
- DSP master defaults to off, and the existing full DSP policy applies only to `MEDIA`. The current profile schema and PCM16 path remain the golden baseline.
- `CarPlayUiScale.DEFAULT` is 100%; the existing presets and single-view-area AirPlay plist remain the compatibility baseline.

The main-code NCM receive loop requires a 513-byte wire unit when an NTB's declared block length is exactly 512 bytes. The PR-00 fixture reproduces that a complete 512-byte block without its optional zero pad is not delivered before timeout, while `Ntb16Codec.build` supplies the pad. This is recorded as `KNOWN_DEFECT_REPRODUCED`; the production fix belongs to PR-01, not PR-00.
