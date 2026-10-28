# PR #52 remaining-work baseline

Captured for the 2026-10-27 completion pass. This records the starting PR snapshot and the first local verification results; the result table distinguishes the starting source from later commits.

## Repository state

| Item | Value |
|---|---|
| Repository | `choko510/DiPlay` |
| PR #52 | [Open, unmerged](https://github.com/choko510/DiPlay/pull/52) |
| PR #52 base | `codex/step4-pr-00-test-baseline` at `a99066f6133c2fe6f5258a2515a89986b75e042e` |
| PR #52 branch | `codex/dsp-studio-final` |
| PR #52 starting head | `2270087383041c80ca8eeb7bee1bdabb1d58186c` |
| Worktree | `C:\Users\eita2\.codex\worktrees\ab84\DiPlay` |
| Starting worktree | Clean; checked out on `codex/dsp-studio-final` after fetch |
| Protected refs | PR #45 and `main` left unchanged |

## Environment

| Component | Value |
|---|---|
| Host | Windows 11, amd64 |
| Java | Android Studio JBR 21.0.9 |
| Gradle wrapper | 9.5.0 |
| Android SDK | `C:\Users\eita2\AppData\Local\Android\Sdk` |
| SDK platforms | android-33, 34, 35, 36.1, 37.0, 37.1 |
| Build tools | 34.0.0, 36.0.0, 36.1.0 |
| NDK | 27.0.12077973 and 28.2.13676358 |
| Connected Android device | `emulator-5554`, API 36 / Android 16 x86_64 |
| Target hardware | No TS10/K706 head unit or iPhone detected; hardware-specific acceptance remains `NOT_RUN` |

Gradle required `ANDROID_HOME` and `ANDROID_SDK_ROOT` to point at the SDK above. No machine-specific `local.properties` was added. Use Android Studio's bundled JBR for Gradle. The `bash` command on Windows resolves to the WSL launcher and fails with `HCS_E_SERVICE_NOT_AVAILABLE`; invoke `C:\msys64\usr\bin\bash.exe` directly and put `/usr/bin:/bin:/ucrt64/bin` first in its `PATH` for the native host script.

## Verification observed

| Source / command | Result | Evidence or limitation |
|---|---|---|
| Starting PR #52 source `2270087383041c80ca8eeb7bee1bdabb1d58186c` | Verified | `git fetch --all --prune`, clean status, branch and PR metadata matched the supplied spec. |
| `bash scripts/test_dsp_native.sh` at the preamp-integration source | PASS | Native DSP host suite passed using MSYS2 Bash. |
| `python scripts/check_jni_mapping.py` before local benchmark builds | `BLOCKED_ENV` | Optimized mapping files were absent: `mobile/build/outputs/mapping/benchmark/mapping.txt` and automotive counterpart. Run after benchmark builds. |
| `python scripts/check_public_tree.py` | PASS | No credential containers or private-key blocks. |
| `bash scripts/test_android_instrumentation_evidence.sh` fixtures | PASS | Evidence fixture suite passed; the missing `44100 Hz / stereo` sample diagnostic is an expected fixture case. |
| `python scripts/generate_step4_ex08_inventory.py --check` | Local `BLOCKED_ENV`; hosted check passed | Windows `core.autocrlf=true` materializes the hash-pinned `upstream_delta_111.commits.jsonl` with CRLF, so its raw file SHA differs from the pinned LF digest even though Git reports the file clean. The hosted `changes` job passed this check at run #517. |
| `:shared:testDebugUnitTest :common:testDebugUnitTest` at `bb82d2ee84b3edacce3213dac09f23a135d28985` | Shared 698/698 PASS; Common 310/311 PASS, 1 failure | `DspProfileRuntimeTest.deletingSelectedModulePresetClearsSavedAndSessionReferences` failed when reloading the workspace after replacing the existing AtomicFile. This matches the known Windows Robolectric rename limitation; Android/hosted Linux verification is still required. XML reports are under each module's `build/test-results/testDebugUnitTest/`. |
| Focused Cancel/restore unit methods and `:common:compileDebugAndroidTestKotlin` at `bb82d2ee84b3edacce3213dac09f23a135d28985` | PASS | Seven targeted unit tests passed: 5 renderer-acknowledgement cases, Cancel restore failure/retry, and coordinator fail/retry. Android-test Kotlin sources compiled. |
| Hosted Android checks [run #514](https://github.com/choko510/DiPlay/actions/runs/40520152526) at `bb82d2ee84b3edacce3213dac09f23a135d28985` | Required jobs PASS | `changes`, `check`, `android-instrumentation`, `host-baseline`, and `Release verification` passed. `quick-baseline` was skipped and is not counted as a pass. |
| Hosted Android checks [run #517](https://github.com/choko510/DiPlay/actions/runs/40522121318) at `568ea897a1cfb804f330a782cf4a74a0cceb7526` | `check` PASS; Android instrumentation FAIL | The new 100-transition tab test found two identical `Auto Headroom` text nodes. Commit `9051100fb7a95c9ea43bf0163664b4b53c4a3956` changed the test to select one matching node. |
| Hosted Android checks [run #520](https://github.com/choko510/DiPlay/actions/runs/40525016845) at `9051100fb7a95c9ea43bf0163664b4b53c4a3956` | Required jobs PASS | `changes`, `check`, API 35 `android-instrumentation`, `host-baseline`, and `Release verification` passed. `quick-baseline` was skipped and is not counted as a pass. The instrumentation workflow ran Shared Release and Common Debug suites. |

The full merged-code result is tracked in [REMAINING_WORK_FINAL_REPORT.md](REMAINING_WORK_FINAL_REPORT.md). Do not treat the earlier source or a docs-only workflow as evidence for later code commits.

## 2026-10-28 final-source follow-up

| Source / command | Result | Evidence or limitation |
|---|---|---|
| Current source `fbfc3b6e02c8590d80352d88b5c3c7e9edfe0145` | Checked out on `codex/dsp-studio-final`; pushed to PR #52 after the source commits. | PR #52 is open and unmerged; base remains `codex/step4-pr-00-test-baseline`. PR #45 and `main` were not modified. |
| `./gradlew.bat --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest --stacktrace` | PASS | Shared XML 704/704 and Common XML 319/319; zero failures/errors/skips. Includes the Atomic owner/status concurrency test. Android SDK warning notes duplicated `android-37.1` folder metadata but required tasks succeeded. |
| `bash scripts/test_dsp_native.sh` using `C:\msys64\usr\bin\bash.exe` with UCRT compiler | PASS | Native DSP and Dynamic EQ HOT suites passed, including the new Preamp HOT state-preservation test with active Compressor/Limiter and Convolver. |
| `python scripts/check_jni_mapping.py` after current Mobile/Automotive Benchmark builds | PASS | Verified `mobile/build/outputs/mapping/benchmark/mapping.txt` and `automotive/build/outputs/mapping/benchmark/mapping.txt` at source `fbfc3b6e`. |
| Full local Gradle unit/lint/build/Benchmark command at `fbfc3b6e` | PASS | 359 actionable tasks; Mobile/Automotive Debug and Benchmark APKs, both Debug lint tasks, Shared 704 and Common 319 unit reports succeeded. The installed NDK warns that its `source.properties` file is absent and packages native libraries without stripping; no build failure occurred. |
| `ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 bash scripts/test_dsp_native.sh --sanitize` | BLOCKED_ENV locally | MSYS2 UCRT GCC is missing `libasan` and `libubsan`; a final hosted run at the current source/documentation head is required. Run #575 at parent source `7aa7ba41` previously passed hosted ASan/UBSan. |
| Evidence validator and fixtures | PASS | Current working tree reports 170 IDs, 25 packages, PASS 2 / PARTIAL 162 / NOT_RUN 6; 24/24 contract fixtures pass. |
| Interim hosted dispatch [#406973](https://github.com/choko510/DiPlay/actions/runs/40697339783) at source `fbfc3b6e` | `changes` and API 35 instrumentation PASS; `check` FAIL | The checkout still contained the pre-correction detailed matrix/tracker status snapshot, so the new parity guard correctly rejected PH01-T03/T04, PH05-T01, and PH07-T07. Host product verification did not start; the independent API 35 artifact `12820865994` succeeded at the exact source SHA. Corrected evidence docs are now in the working tree; this run is not counted as full CI. |
| Full hosted verification at current source `fbfc3b6e` | PENDING | Run #575 at `7aa7ba41` passed the full source workflow, but it predates the lock-free status state, new Native state test and strict evidence updates. |

No TS10/K706 or supported iPhone was connected. Physical route, acoustic, CarPlay host/session/Surface/touch, A/V, CPU/RSS, and soak gates remain `NOT_RUN` as listed in the runbook.
