# DSP Studio test matrix

This matrix distinguishes automated evidence from Android instrumentation and target-device acceptance. The task-supplied PR #52 remediation specification is preserved in [REMEDIATION_SPEC.md](REMEDIATION_SPEC.md); its project acceptance requirements do not replace repository or agent instructions. Individual T-ID and PH-ID evidence is tracked in [TEST_MATRIX_DETAILED.md](TEST_MATRIX_DETAILED.md).

## Execution ledger

| Command | Result | Evidence and limits |
|---|---|---|
| `bash scripts/test_dsp_native.sh` | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` | Native DSP suite passed. |
| `ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 bash scripts/test_dsp_native.sh --sanitize` | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` | ASan/UBSan passed. Default LeakSanitizer (`detect_leaks=1`) aborts on Apple Clang because it is unsupported on this macOS host; Linux CI must run the default gate. |
| `./gradlew --parallel :shared:testDebugUnitTest :common:testDebugUnitTest :common:compileDebugAndroidTestKotlin :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark` | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` | Shared 668 + Common 267 = 935 tests, 0 failures/errors/skips; Android-test compile, debug APKs, benchmark APKs, and both lint tasks passed. |
| `python3 scripts/check_public_tree.py`; EX-08 `--check`; JNI mapping; CI fixture scripts | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` | Public-tree/credential audit passed; EX-08 inventory 1,400 unique rows; mobile/automotive JNI benchmark mappings verified; classifier, instrumentation evidence, and delta-111 fixtures passed (17/17). |
| `ANDROID_HOME=/Users/choko/Library/Android/sdk ANDROID_SDK_ROOT=/Users/choko/Library/Android/sdk ./gradlew --no-daemon --max-workers=1 --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest :shared:compileDebugAndroidTestKotlin :common:compileDebugAndroidTestKotlin` | PASS at `9e026e55d75f0c4f6d89cdaf66727bfbd023f40a` (historical) | 866 tests: Shared 639, Common 227; 0 failures, 0 errors, 0 skips. This predates PR #62/#63 integration, the base update, and pending source changes; not final-head evidence. |
| Earlier full-source native build and test gate | PASS on the earlier integration source gate | Shared Native build completed for arm64-v8a, armeabi-v7a, and x86_64; see the exact run and limitations in [FINAL_IMPLEMENTATION_REPORT.md](FINAL_IMPLEMENTATION_REPORT.md). |
| `ANDROID_HOME=... ANDROID_SDK_ROOT=... ./gradlew --no-daemon --max-workers=1 --console=plain :common:testDebugUnitTest --tests com.shilapi.xcertplay.dsp.DspProfileRuntimeTest` | PASS | Focused runtime suite passed 7/7 after removing only the stale generated Gradle binary test-result files. The final full-suite rerun then passed. |
| Initial `:common:testDebugUnitTest` attempt before clearing host space | BLOCKED_ENV | Gradle reported `No space left on device` while creating test temp directories and writing result records. The partial generated result binary then caused a follow-up task to exhaust heap while deserializing previous failure records. After deleting those two generated result files and freeing the generated Android-test APK, the focused test and full command above passed. |
| `:mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark :mobile:lintDebug :automotive:lintDebug` | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` | Mobile and Automotive debug/benchmark APKs packaged, with lint clean. |
| `python3 scripts/check_jni_mapping.py` | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` | Verified both authentic benchmark mapping outputs. |
| `python3 scripts/check_public_tree.py` | PASS | No credential containers or private-key blocks. |
| `python3 scripts/generate_step4_ex08_inventory.py --check` | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` | 1,400 unique rows. |
| `bash scripts/test_android_ci_classifier.sh` | PASS | Classifier fixture checks passed. |
| `git diff --check` | PASS for the docs-only update at implementation/test snapshot `da7f89ac1adf2ac94aa82a9549ca007ea7f65933` | Clean after refreshing the report, review matrix, and test mappings. |
| `./gradlew :common:compileDebugAndroidTestKotlin :common:packageDebugAndroidTest` | PASS at `da7f89ac1adf2ac94aa82a9549ca007ea7f65933` | Compiled and packaged the test-only Factory Reset and selected-delete fallback assertions. |
| `adb -s emulator-5556 shell am instrument -w -e class com.shilapi.xcertplay.dsp.DspModulePresetPaneTest com.shilapi.xcertplay.host.test/androidx.test.runner.AndroidJUnitRunner` | PASS at `da7f89ac1adf2ac94aa82a9549ca007ea7f65933` | API 35 emulator: 4/4. Log: `common/build/reports/dsp-studio/DspModulePresetPaneTest-api35.txt`. |
| `adb -s emulator-5556 shell am instrument -w -e class com.shilapi.xcertplay.DspSettingsScreenTest com.shilapi.xcertplay.host.test/androidx.test.runner.AndroidJUnitRunner` | PASS at `da7f89ac1adf2ac94aa82a9549ca007ea7f65933` | API 35 emulator: 3/3, including selected-module Factory Reset, selected-delete fallback, and macro preview/cancel. Log: `common/build/reports/dsp-studio/DspSettingsScreenTest-api35.txt`. |
| Focused screen methods | PASS at `da7f89ac1adf2ac94aa82a9549ca007ea7f65933` | Factory Reset 1/1 and user-preset delete fallback 1/1; logs: `DspSettingsScreenTest-factory-reset-api35.txt` and `DspSettingsScreenTest-delete-fallback-api35.txt` under `common/build/reports/dsp-studio/`. |
| `:shared:connectedDebugAndroidTest :common:connectedDebugAndroidTest` | Historical PASS in hosted CI #438; current hosted run PENDING | The final local API 35 tests above pass; hosted full instrumentation on the pushed PR #52 head is still required. |

## Test coverage mapping

| ID range | Implemented coverage and evidence | Status |
|---|---|---|
| T-MAC-01..06 | `DspTuningResolverTest`, `DspMacro` native coverage, and Studio persistence tests cover neutral defaults, bounds, resolved gain allocation, and workspace round-trip. | PARTIAL: suite PASS at #438; direct per-ID method mapping open; acoustic acceptance NOT_RUN |
| T-PRE-01..09 | `DspStudioPersistenceTest`, `DspProfileRepositoryTest`, `DspEditorSessionTest`, and `DspSettingsScreenTest` cover versioned stores, validation, scoped apply, user deletion with Factory fallback, selected-module Factory Reset, preview/save/cancel, A/B, and history. | PARTIAL: module-scoped A/B and direct per-ID mapping remain open |
| T-LOUD-01..10 | `DspVolumeSourceTest`, `DspTuningResolverTest`, `AndroidPlaybackVolumeMonitorTest`, and Native loudness tests cover route-bound AUTO availability, manual/fixed modes, bounds, mapping, and no-reading behavior. | PARTIAL: production monitor wired; direct per-ID mapping and TS10 route calibration remain open |
| T-DB-01..12 | Native `test_dsp_engine.c`, `DspTuningResolverTest`, and renderer tests cover disabled output, gating, envelope behavior, stereo linking, bounds, and hot updates. | PARTIAL: suite PASS at #438; direct per-ID mapping, target acoustics, and performance NOT_RUN |
| T-SAFE-01..09 | Resolver and Native tests cover shared additional-bass budget, parameter validation, bounded stage gains, and finite-output safety. | PARTIAL: suite PASS at #438; direct per-ID mapping, listening/THD, and target true-peak evidence NOT_RUN |
| T-LIVE-01..19 | `DspLiveUpdateControllerTest`, `DspUpdatePlannerTest`, `DspRendererProductionPathTest`, `DspPcmPipelineTest`, JNI/native tests cover latest-wins, owner/format fencing, close/failure/context-rebind races, ACK status, role policy, and deferral. | PARTIAL: suite PASS at #438; per-ID mapping and stage-local PEQ/Bass warm-up open |
| T-UI-01..11 | `DspSettingsUiContractTest`, `DspEditorPanesTest`, `DspModulePresetPaneTest` (4/4), and `DspSettingsScreenTest` (3/3) cover UI contracts, draft preview/cancel, selected-module Factory Reset, and selected-user-delete fallback. | PARTIAL: per-ID mapping and actual host geometry/session/Surface/touch remain open |
| T-REG-01..12 | Existing stream-policy, OFF legacy PCM, latency-preserving bypass, four-format conversion, PEQ-capacity, Native/JNI, and renderer suites cover software regressions. | PARTIAL: suite PASS at #438; per-ID mapping and device A/V, Surface, Siri/call/navigation NOT_RUN |
| T-MIG-01..10 | `DspProfileRepositoryTest`, `DspProfileRuntimeTest`, `DspStudioPersistenceTest`, and `DspImpulseResponseRepositoryTest` cover legacy schema migration, future-schema preservation, no implicit workspace write, runtime restore, IR references, and preview cancel. Runtime feature rollback now has additional tests. | PARTIAL: old-v7 startup with every flag OFF and direct per-ID mapping remain open |
| T-PERF-01..12 | Sanitizers, ABI/native build, renderer tests, and APK/benchmark gates provide software evidence. | PARTIAL: host/CI evidence recorded; direct per-ID mapping, target CPU/RSS/p99, long soak, A/V, and physical 20-cycle results NOT_RUN |

## Android instrumentation and target evidence

| Gate | Status | Required next evidence |
|---|---|---|
| API 35 `DspModulePresetPaneTest` and `DspSettingsScreenTest` | PASS 4/4 and 3/3 at `da7f89ac1adf2ac94aa82a9549ca007ea7f65933`; hosted full suite pending | Logs: `common/build/reports/dsp-studio/`. These do not replace the full connected instrumentation suite or CarPlay host validation. |
| CarPlay 55:45 geometry, Surface/session/touch continuity | NOT_RUN | Run the 20-cycle procedure in [RUNBOOK.md](RUNBOOK.md) on TS10/K706 with an iPhone; assert the same host session and Surface remain active. |
| Route-volume calibration and AUTO loudness | NOT_RUN | Record route identity and trusted volume mapping on target hardware; AUTO must remain unavailable without a matching calibration. |
| A/V, latency, CPU/RSS, listening/THD, Siri/call/navigation, soak | NOT_RUN | Capture measurements and logs described in [RUNBOOK.md](RUNBOOK.md). |

Test totals include failures, errors, and skips from the successful Gradle XML snapshot. Failed, skipped, unavailable, and unexecuted work is never counted as PASS. Emulator or host results do not substitute for TS10/K706 acceptance. Current local source head is `da7f89ac1adf2ac94aa82a9549ca007ea7f65933`; PR #52 hosted CI remains pending.
