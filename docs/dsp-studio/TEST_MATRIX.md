# DSP Studio test matrix

This matrix distinguishes automated evidence from Android instrumentation and target-device acceptance. The referenced detailed specification `DiPlay_PR45_Codex_Final_DSP_Studio_Macro_Loudness_DynamicBass_2026-10-26.md` was not available in the workspace or attachments; test-ID meanings below follow the accessible goal and initial matrix rather than an independently verified copy of that file.

## Final local execution ledger

| Command | Result | Evidence and limits |
|---|---|---|
| `bash scripts/test_dsp_native.sh` | PASS | Host DSP test suite completed successfully on the integration worktree. |
| `ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 bash scripts/test_dsp_native.sh --sanitize` | PASS | ASan/UBSan passed. LeakSanitizer is disabled because it is unsupported by this macOS host. |
| `ANDROID_HOME=/Users/choko/Library/Android/sdk ANDROID_SDK_ROOT=/Users/choko/Library/Android/sdk ./gradlew --parallel --no-daemon --console=plain :shared:testDebugUnitTest :common:testDebugUnitTest :shared:externalNativeBuildDebug :shared:compileDebugAndroidTestKotlin :common:compileDebugAndroidTestKotlin` | PASS on the final source working tree | 860 tests: shared 634, common 226; 0 failures, 0 errors, 0 skips. Both Android-test Kotlin sources compiled. Shared Native build completed for arm64-v8a, armeabi-v7a, and x86_64. Final source SHA is recorded in [FINAL_IMPLEMENTATION_REPORT.md](FINAL_IMPLEMENTATION_REPORT.md). |
| `ANDROID_HOME=... ANDROID_SDK_ROOT=... ./gradlew --no-daemon --max-workers=1 --console=plain :common:testDebugUnitTest --tests com.shilapi.xcertplay.dsp.DspProfileRuntimeTest` | PASS | Focused runtime suite passed 7/7 after removing only the stale generated Gradle binary test-result files. The final full-suite rerun then passed. |
| Initial `:common:testDebugUnitTest` attempt before clearing host space | BLOCKED_ENV | Gradle reported `No space left on device` while creating test temp directories and writing result records. The partial generated result binary then caused a follow-up task to exhaust heap while deserializing previous failure records. After deleting those two generated result files and freeing the generated Android-test APK, the focused test and full command above passed. |
| `:mobile:assembleDebug :mobile:lintDebug`, `:mobile:assembleBenchmark`, `:automotive:assembleDebug :automotive:lintDebug`, `:automotive:assembleBenchmark` | PASS on the earlier integrated source gate | These gates passed before the final test-isolation-only change. They were not rerun after that change because host storage was constrained. |
| JNI mapping check with mobile and automotive benchmark mapping/seeds | PASS on the earlier integrated source gate | Used both authentic benchmark mapping outputs; not rerun after the final test-only change. |
| `python3 scripts/check_public_tree.py` | PASS | No credential containers or private-key blocks. |
| `python3 scripts/generate_step4_ex08_inventory.py --check` | PASS | 1,289 unique rows. |
| `bash scripts/test_android_ci_classifier.sh` | PASS | Classifier fixture checks passed. |
| `git diff --check` | PASS | No whitespace errors in the final working diff. |
| `:common:connectedDebugAndroidTest` | BLOCKED_ENV | Instrumentation APK built, but `adb devices -l` was empty. AVD start also failed with insufficient disk space. Screen test was compile-checked only. |

## Test coverage mapping

| ID range | Implemented coverage and evidence | Status |
|---|---|---|
| T-MAC-01..06 | `DspTuningResolverTest`, `DspMacro` native coverage, and Studio persistence tests cover neutral defaults, bounds, resolved gain allocation, and workspace round-trip. | AUTOMATED_PASS; acoustic acceptance NOT_RUN |
| T-PRE-01..09 | `DspStudioPersistenceTest`, `DspProfileRepositoryTest`, and `DspEditorSessionTest` cover versioned workspace/module stores, validation, scoped preset application, preview/save/cancel, A/B, and history. | AUTOMATED_PASS |
| T-LOUD-01..10 | `DspVolumeSourceTest`, `DspTuningResolverTest`, and Native loudness tests cover route-bound AUTO availability, manual/fixed modes, bounds, mapping, and no-reading behavior. | AUTOMATED_PASS; TS10 route calibration NOT_RUN |
| T-DB-01..12 | Native `test_dsp_engine.c`, `DspTuningResolverTest`, and renderer tests cover disabled output, gating, envelope behavior, stereo linking, bounds, and hot updates. | AUTOMATED_PASS; target acoustics/performance NOT_RUN |
| T-SAFE-01..09 | Resolver and Native tests cover shared additional-bass budget, parameter validation, bounded stage gains, and finite-output safety. | AUTOMATED_PASS; listening/THD and target true-peak evidence NOT_RUN |
| T-LIVE-01..19 | `DspLiveUpdateControllerTest`, `DspUpdatePlannerTest`, `DspRendererProductionPathTest`, `DspPcmPipelineTest`, JNI/native tests cover latest-wins, owner/format fencing, close/failure/context-rebind races, ACK status, role policy, and deferral. | AUTOMATED_PASS |
| T-UI-01..11 | `DspSettingsUiContractTest` and `DspEditorPanesTest` cover UI contracts and editor panes. `DspSettingsScreenTest` compiles and covers isolated standalone FULL/COMPACT_SPLIT draft, preview, and cancel flow; it does not construct the CarPlay host. | PARTIAL; connected test and actual host geometry/session/Surface/touch NOT_RUN |
| T-REG-01..12 | Existing stream-policy, OFF legacy PCM, latency-preserving bypass, four-format conversion, PEQ-capacity, Native/JNI, and renderer suites cover software regressions. | AUTOMATED_PASS; device A/V, Surface, Siri/call/navigation NOT_RUN |
| T-MIG-01..10 | `DspProfileRepositoryTest`, `DspProfileRuntimeTest`, `DspStudioPersistenceTest`, and `DspImpulseResponseRepositoryTest` cover legacy schema migration, future-schema preservation, no implicit workspace write, runtime restore, IR references, and preview cancel. | AUTOMATED_PASS |
| T-PERF-01..12 | Sanitizers, ABI/native build, renderer tests, and APK/benchmark gates provide software evidence. | PARTIAL; target CPU/RSS/p99, allocation telemetry, long soak, A/V, and physical 20-cycle results NOT_RUN |

## Android instrumentation and target evidence

| Gate | Status | Required next evidence |
|---|---|---|
| Standalone DSP Studio instrumentation test | BLOCKED_ENV | Run `DspSettingsScreenTest` on a connected emulator/device after restoring host disk space. |
| CarPlay 55:45 geometry, Surface/session/touch continuity | NOT_RUN | Run the 20-cycle procedure in [RUNBOOK.md](RUNBOOK.md) on TS10/K706 with an iPhone; assert the same host session and Surface remain active. |
| Route-volume calibration and AUTO loudness | NOT_RUN | Record route identity and trusted volume mapping on target hardware; AUTO must remain unavailable without a matching calibration. |
| A/V, latency, CPU/RSS, listening/THD, Siri/call/navigation, soak | NOT_RUN | Capture measurements and logs described in [RUNBOOK.md](RUNBOOK.md). |

Test totals include failures, errors, and skips from the successful Gradle XML snapshot. Failed, skipped, unavailable, and unexecuted work is never counted as PASS. Emulator or host results do not substitute for TS10/K706 acceptance.
