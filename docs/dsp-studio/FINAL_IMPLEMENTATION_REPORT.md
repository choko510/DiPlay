# DSP Studio final implementation report

**Current disposition (2026-10-28):** `SOURCE_IMPLEMENTED / LOCAL_UNIT_AND_NATIVE_GREEN / FINAL_HOSTED_CI_PENDING / HARDWARE_NOT_RUN / MERGE_NOT_READY`

**Integration PR:** [#52](https://github.com/choko510/DiPlay/pull/52), base `codex/step4-pr-00-test-baseline` at `a99066f6133c2fe6f5258a2515a89986b75e042e`, stacked on PR #45. PR #52 remains open and unmerged. PR #45 and `main` were not modified.

**Current source head:** `fbfc3b6e02c8590d80352d88b5c3c7e9edfe0145`. Local Native regular tests pass, including Preamp HOT with active Compressor/Limiter/Convolver state preservation. Shared/Common unit reports show 704/704 and 319/319 with zero failures/errors/skips. Evidence validation passes 170/170 IDs, 25/25 work packages and 24/24 contract fixtures, with statuses PASS 2 / PARTIAL 162 / NOT_RUN 6.

**Hosted evidence:** [Run #575](https://github.com/choko510/DiPlay/actions/runs/40686737568) passed Native, ASan/UBSan, Shared/Common unit tests, Mobile/Automotive Debug and Benchmark builds, lint, JNI mapping and API 35 Shared Release (23/23) + Common Debug (29/29) instrumentation at parent source `7aa7ba41a1c4f3b97c44d0f305a72276219e7847`. Its `quick-baseline` and Release verification jobs were skipped because it used `workflow_dispatch`. Run #575 predates the Runtime status atomic-state change and the Native state-preservation test; a full hosted run at `fbfc3b6e` and the final documentation head is pending. Local MSYS2 ASan/UBSan is blocked because the toolchain lacks `libasan`/`libubsan`.

An interim workflow dispatch, [run #406973](https://github.com/choko510/DiPlay/actions/runs/40697339783), was started before the corrected matrix/tracker docs were pushed. The `changes` and API 35 instrumentation jobs passed at `fbfc3b6e` (artifact [12820865994](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12820865994)); `check` failed on the then-stale evidence status rows before Native/unit/build/lint/JNI steps. The status rows are now synchronized, but this failed overall run is not counted as full CI.

**Independent review:** Four read-only reviews at `fbfc3b6e` found no open P0/P1. The review-requested renderer-status monitor lock was replaced by an atomic owner/status state and covered by a concurrent owner replacement test. The Preamp HOT state-preservation coverage gap is closed by the new Native test. Remaining partial evidence and hardware-only acceptance are itemized in [REMAINING_WORK_FINAL_REPORT.md](REMAINING_WORK_FINAL_REPORT.md).

**Hardware and release decision:** No TS10/K706 or supported iPhone is attached. Physical route calibration, CarPlay host/Surface/touch continuity, A/V, THD/True Peak, CPU/RSS/p99 and soak remain `NOT_RUN`. `HARDWARE_PASS=false`, `MERGE_READY=false`; keep PR #52 open/unmerged pending final current-head CI and user approval.

## Historical implementation snapshot (2026-10-27)

The sections below record the implementation and validation state at the earlier `fe083f4f` / run #506 checkpoint. Current source status and post-review changes are summarized above and in [REMAINING_WORK_FINAL_REPORT.md](REMAINING_WORK_FINAL_REPORT.md).

## Implementation summary

**Latest validation update:** At source head `fe083f4f524fa6ee1038860343e4f100aee8dad6`, Shared 698 and Common 304 unit tests passed with zero failures/errors/skips. API 35 instrumentation passed 20 Shared Debug, 17 Common Debug, and 20 optimized Shared Release tests, all with zero failures/errors/skips. Mobile/Automotive Debug and Benchmark builds, both Debug lint tasks, JNI mapping, public-tree checks, and instrumentation evidence fixtures passed. Lint reported four existing findings per app in the app manifests/config and a dependency JAR; none point to files changed by this remediation. Hosted run #506 passed all configured jobs on `1e4a8962`; `Draft unit tests` and the dedicated ABI-build steps were skipped, while the full unit/build step and Release verification passed.

- Added schema-v1 DSP Studio workspace and module-preset stores, independent of the v7 legacy profile store. Draft, Preview, Save, Cancel, A/B, and Undo/Redo retain separate states.
- Added four non-destructive Macro controls, route-bound calibrated Smart Loudness AUTO with MANUAL/FIXED alternatives, and bounded Dynamic Bass with a linked stereo envelope. `DspTuningResolver` allocates a shared 6 dB additional-bass budget.
- Added five Studio navigation tabs, a 15-band pointer-editable graphical PEQ editor, and advanced DSP panes for FULL and COMPACT_SPLIT presentations.
- Added persisted Workspace/Macro/Loudness/Dynamic Bass/HOT rollout flags, apply-failure rollback, and route-owned Android playback-volume monitoring.
- Added renderer-owned HOT updates with generation, editor-owner, process-wide renderer-owner, PCM-format, and hot-context validation. Kotlin pending ACK/failure/close/rebind completion uses one-shot CAS. Retry scheduling from the render callback only signals the control thread; preparation and lock-taking stay off the audio path.
- Added opt-in LOCAL_WARM for PEQ and Static Bass. Static Bass occupies one internal graph filter beyond the existing 15 user PEQ/Cabin capacity and shares the bounded pre-EQ history/crossfade path. Auto-headroom changes, unsupported roles, stale owners/formats, and insufficient history still defer safely. Cabin Correction and IR remain deferred.
- Hardened DSP storage paths against symlinked files/directories, protects future-schema AtomicFile backups, restores the previous active workspace when feature-flag persistence fails, and bounds Smart Loudness headroom for the high-pass transient response.
- Preserved schema-v7 profile handling, the shared PEQ/Cabin capacity, MEDIA-only full-DSP policy, and the existing new-track DSP Master OFF PCM16 bypass contract.

## REQ status

The task-supplied PR #52 implementation specification is preserved in [REMEDIATION_SPEC.md](REMEDIATION_SPEC.md). It is treated as the project's acceptance criteria and remains subordinate to repository and agent instructions.

Field traceability is summarized in [LEGACY_FIELD_COVERAGE.md](LEGACY_FIELD_COVERAGE.md) and [MODULE_HOT_WARM_MATRIX.md](MODULE_HOT_WARM_MATRIX.md); individual formal and added test IDs remain in [TEST_MATRIX_DETAILED.md](TEST_MATRIX_DETAILED.md).

| Requirement | Status | Evidence and remaining work |
|---|---|---|
| REQ-001 — Non-destructive sound macros | COMPLETE | 0–100 controls, 50 neutral defaults, resolver/native path, UI and persistence tests. |
| REQ-002 — DSP Studio, PEQ, module presets, A/B/history, preview/save separation | PARTIAL | Module-scoped A/B previews only the selected module and preserves the Studio draft; API 35 verifies the Bass A/B flow and unchanged Compressor/Macro values. Level matching is explicitly unavailable without a same-source measurement window. CarPlay host geometry/session behavior remains NOT_RUN. |
| REQ-003 — Smart Loudness | PARTIAL | Android playback-volume monitoring is wired into the runtime with route identity checks and MANUAL/FIXED alternatives. Target route calibration and OEM trust validation are NOT_RUN. |
| REQ-004 — Dynamic Bass 2.0 | PARTIAL | Native detector now uses a sanitized raw-input tap, with gate, smoothing, stereo link, and bounded gain coverage. Target listening, THD, and performance are NOT_RUN. |
| REQ-005 — Shared gain budget and signal safety | COMPLETE | Resolver and Native tests cover bounded additional bass boost, validation, and finite output. Hardware listening/true-peak evidence remains NOT_RUN. |
| REQ-006 — Hot/Warm/Deferred and generation ACK | PARTIAL | Renderer/JNI/native tests cover ownership, latest-wins, status, stale ACK, failure, close, and context-rebind races. Opt-in PEQ/Static Bass local warm-up now uses bounded history and same-latency crossfade; Cabin/IR and other unproven state transitions still defer. Field-level evidence mapping remains incomplete. |
| REQ-007 — Live/CarPlay continuity | PARTIAL | Source and renderer compatibility tests pass. TS10/K706 session, format-change, A/V, and long-run acceptance are NOT_RUN. |
| REQ-008 — Full and compact UI accessibility/behavior | PARTIAL | FULL/COMPACT_SPLIT and five-tab navigation are implemented; `DspSettingsScreenTest` passed 4/4 on API 35 at `ce3c87d7`. Actual CarPlay 55:45 geometry/touch evidence is NOT_RUN. |
| REQ-009 — Master OFF, MEDIA-only, PCM formats, shared PEQ capacity | COMPLETE | Software stream-policy, PCM, bypass, capacity, Native, and JNI suites pass. Device proof is NOT_RUN. |
| REQ-010 — v0–v7 profile compatibility and persistence | COMPLETE | Migration, future-schema preservation, workspace restore, preset, and IR-reference unit coverage passes. |
| REQ-011 — Final host and hardware acceptance | PARTIAL | API 35 emulator UI tests pass. TS10/K706/iPhone physical route, geometry, listening, latency, and continuity acceptance remain NOT_RUN; use [RUNBOOK.md](RUNBOOK.md). |
| REQ-012 — Reviewable evidence, rollback, and release decision | PARTIAL | Feature flags are now persisted and connected to runtime gates, with apply-failure rollback tests. The 170 T-ID/PH-ID catalog still lacks complete method/command/SHA/artifact mapping. |

## Phase and known-CR disposition

These dispositions use the locally tested source head `fe083f4f524fa6ee1038860343e4f100aee8dad6`; hosted run #506 passed on documentation-only head `1e4a8962` with the same source tree. `PARTIAL` means implementation exists but at least one acceptance assertion or evidence item remains open. Hardware-only work is `NOT_RUN`.

| ID | Disposition | Current evidence | Remaining gate |
|---|---|---|---|
| Phase 01 — Presets and persistence | PARTIAL | Workspace and module preset repositories, validation, scoped apply, migration, user-preset delete with Factory fallback, and selected-module Factory Reset are implemented and tested. | One-to-one evidence for all Phase 01 IDs and remaining save/restart/race assertions. |
| Phase 02 — Five-tab UI | PARTIAL | `DspStudioTab` defines Studio/EQ/Spatial/Dynamics/Advanced; standalone screen instrumentation. | Legacy-feature reachability, host geometry and interaction evidence. |
| Phase 03 — Graphical EQ | PARTIAL | Pointer-down selection and drag editing; API 35 EQ graph instrumentation previously passed 3/3. | Q/type/numeric and overlap-specific mapping, exact frequency tolerance, final-head run. |
| Phase 04 — Preview and persistence separation | PARTIAL | Macro drag updates runtime preview; commit/restore path and isolated screen tests. | Activity recreation/process-death and final-value ACK mapping. |
| Phase 05 — A/B, undo and status | PARTIAL | Whole-workspace and module-scoped A/B, frozen comparison snapshots, renderer apply status, and draft-preservation screen assertion pass locally. | Level-match measurement is unavailable; compressor-specific WARM/DEFER mapping and 100-drag evidence remain open. |
| Phase 07 — Live DSP | PARTIAL | HOT for supported overlay fields; stop-flag check before enqueue; opt-in PEQ/Static Bass local warm-up preserves downstream state; unsupported PREAMP, Cabin, IR, and latency-changing edits report DEFERRED. | Complete field-by-field ID/test mapping and target-device evidence. |
| CR-001 — Screen instrumentation failure | COMPLETE | API 35 `DspSettingsScreenTest` passed 4/4 locally; hosted run #484 passed its Android instrumentation job on `ce3c87d7`. Coverage includes module A/B draft preservation and restoring the renderer after deleting the selected preset during A/B. | None for the reported screen-test failure. Other evidence and hardware gates remain tracked separately. |
| CR-002 — Macro drag audio preview | PARTIAL | Macro changes trigger runtime preview; pointer-up commits one edit transaction and flushes latest workspace. | Final-value audio ACK assertion on final head. |
| CR-003 — Production loudness source | PARTIAL | `AndroidPlaybackVolumeMonitor` is installed as the runtime volume source; untrusted route readings remain unavailable. | TS10/K706 calibration and OEM behavior. |
| CR-004 — Volume changes after UI close | PARTIAL | Runtime monitor subscribes to Android volume broadcasts and route changes independently of the screen. | Hardware route behavior. |
| CR-005 — Session recreation | PARTIAL | `runtimeRecreationRestoresPersistedWorkspaceAndDiscardsPreviewDraft` verifies preview discard and restoration of the persisted workspace/effective gain after runtime reconstruction. | The test reconstructs the runtime in one process; Activity recreation/process-death E2E remains open. |
| CR-006 — Saved workspace/profile identity | PARTIAL | Runtime reconciles selected profile identity from a loaded workspace and validates source identity. | Exact migration/reselection method mapping. |
| CR-007 — Dynamic Bass detector tap | PARTIAL | PR #59 added a sanitized raw-input detector tap; native signal tests cover it. | Target listening/THD/performance. |
| CR-008 — Five-tab navigation | PARTIAL | Five navigation tabs are implemented. | Direct reachability/accessibility assertions and host interaction. |
| CR-009 — Interactive EQ editor | PARTIAL | Graph supports touch selection/edit and numeric controls; PR #62 fixed pointer-down selection. | Frequency, overlap, Q/type and final-head assertions. |
| CR-010 — Scoped A/B and level match | PARTIAL | Studio UI exposes module A/B; API 35 asserts Bass-only comparison, unchanged Compressor/Macro values, and unchanged draft. Level match reports unavailable because there is no measured same-source window. | Compressor-specific WARM/DEFER mapping and a valid measurement window remain open. |
| CR-011 — Preset delete/reset and IR references | PARTIAL | Runtime removes deleted preset references from saved workspace, committed/draft state, and undo/redo/transaction snapshots; tests cover future-schema preservation. API 35 screen tests verify module reset and delete fallback, including restoring the draft after deletion during A/B. | Full IR-reference race/missing-file coverage and exact per-ID evidence. |
| CR-012 — Safe live update fields | PARTIAL | Planner classifies overlay HOT fields and defers unsupported PREAMP/state changes. | Existing DSP HOT/local-warm requirements and per-field tests. |
| CR-013 — Stage-local warm-up | PARTIAL | PEQ/Static Bass local warm-up uses the same bounded pre-EQ history and same-latency crossfade; renderer and Native regressions pass at `fe083f4f`. | Full parameter-level acceptance mapping; Cabin/IR remain deferred, and device playback evidence is open. |
| CR-014 — Macro factory presets | PARTIAL | Six stable factory macro presets (`macro_neutral_v1`, `macro_vocal_v1`, `macro_punch_v1`, `macro_clearwide_v1`, `macro_warm_v1`, `macro_night_v1`) and macro save/load repository are present. | Full per-ID controls/persistence/UI assertions and exact artifacts remain open. |
| CR-015 — EQ preview response | PARTIAL | Graph receives active playback sample rate when available and labels fallback rate. | Native-equivalent combined Macro/Loudness/Bass response proof. |
| CR-016 — Apply status separation | PARTIAL | Save and renderer status are distinct; renderer reports generation-scoped ACTIVE/DEFERRED/FAILED. | NO_MEDIA and full owner/status matrix. |
| CR-017 — Runtime feature flags | PARTIAL | Persisted UI/runtime flags gate workspace/audio features and native HOT updates; failed apply restores prior flags. | Full rollback matrix on final head and verify behavior of already-enqueued Native commands. |
| CR-018 — Per-ID evidence | PARTIAL | Detailed matrix has 60 PH-ID and 110 T-ID rows, with stale FAIL labels corrected where source changed. | Complete method, command, SHA, result and artifact on every row. |
| CR-019 — Drag undo transaction | PARTIAL | Macro drag is one transaction and commits on release/Undo/Save/Cancel/A-B. | 100-operation undo-count and EQ-gesture coverage. |
| CR-020 — HOT ownership races | PARTIAL | Renderer/native tests cover stale ACK, close, context rebind, owner and format fencing. | Final native stress/TSan where supported and complete ID evidence. |
| CR-021 — HOT headroom agreement | PARTIAL | Bounded resolver and native parameter validation tests exist. | Kotlin-vs-C transfer/headroom equivalence and limiting proof. |
| CR-022 — UI/audio generation races | PARTIAL | Generation, owner and context tokens fence stale updates; one-shot completion tests exist. | Complete drag/Cancel/Save/A-B race matrix on final head. |
| CR-023 — Convolver performance gate | PARTIAL | PR #63 CI #468 passed heavy-chain instrumentation after an earlier same-commit run exceeded the unchanged threshold. | Current PR #52 head must pass the full performance job. |
| CR-024 — Physical acceptance | NOT_RUN | No TS10/K706/iPhone measurement evidence is available. | Follow [RUNBOOK.md](RUNBOOK.md) on target hardware. |

## Package status

| Package | Status | Note |
|---|---|---|
| PKG-00 baseline | COMPLETE | Parent and stacked-PR boundaries, environment, and historical baseline captured. |
| PKG-01 workspace/Macro/resolver | COMPLETE | Source, persistence, and resolver tests pass. |
| PKG-02 Native stages | COMPLETE | Native tests, sanitizers, and three-ABI debug link pass. |
| PKG-03 live update | COMPLETE | Renderer ownership and generation regressions pass. |
| PKG-04 stores/session | PARTIAL | Module-scoped A/B and deleted-preset reference cleanup are implemented and tested; IR-reference race coverage and per-ID evidence remain open. |
| PKG-05 Macro UI | PARTIAL | Five-tab navigation and module A/B controls are implemented; API 35 screen instrumentation passed 4/4. Final-head hosted and physical host acceptance remain open. |
| PKG-06 Smart Loudness | PARTIAL | Runtime playback-volume monitor is wired; target route calibration is NOT_RUN. |
| PKG-07 Dynamic Bass | PARTIAL | No target acoustic/performance result. |
| PKG-08 live compatibility | PARTIAL | No target CarPlay/A/V/soak result. |
| PKG-09 PEQ/advanced UI | PARTIAL | Actual head-unit layout/touch not exercised. |
| PKG-10 integration | PARTIAL | Child PRs #53–#63 and latest base update are integrated. Runtime feature flags are wired; final-head CI and target acceptance remain open. |

## Luna child PRs

The six feature PRs #53–#58 were dispatched to `gpt-6-luna`. PRs #59 and #60 are also integrated; their model attribution is not asserted. All child PRs target `codex/dsp-studio-final`; none targets PR #45 or `main`.

| Task | PR | Branch | Child head SHA | Integration result |
|---|---|---|---|---|
| smart-loudness | [#53](https://github.com/choko510/DiPlay/pull/53) | `codex/dsp-studio-smart-loudness` | `02d79ba2ccd56992b947fbab2e82b80bfe91769e` | merged to integration |
| macro-ui | [#54](https://github.com/choko510/DiPlay/pull/54) | `codex/dsp-studio-macro-ui` | `ac7aa0478d621f1e33b6a3acca663b952522e92f` | merged to integration |
| preset-workspace | [#55](https://github.com/choko510/DiPlay/pull/55) | `codex/dsp-studio-preset-workspace` | `48c90a09a5f0df95a12fb35e7a17058f1e04def9` | merged to integration |
| eq-advanced-ui | [#56](https://github.com/choko510/DiPlay/pull/56) | `codex/dsp-studio-eq-advanced-ui` | `9c278b8078b2055a61e57b8ebebf572e21c2cccc` | merged to integration |
| dynamic-bass | [#57](https://github.com/choko510/DiPlay/pull/57) | `codex/dsp-studio-dynamic-bass` | `92febd9ade3fc9d2d88de0d76689558a55a189e3` | merged to integration |
| live-compat | [#58](https://github.com/choko510/DiPlay/pull/58) | `codex/dsp-studio-live-compat` | `864eb36cc516720e6fe1f7bad3249550eddd1866` | merged to integration |
| native-db-raw-tap | [#59](https://github.com/choko510/DiPlay/pull/59) | `codex/dsp52-native-db-tap` | `fdebaa9ad543e07d0f673a7706af59e743ffee92` | merged to integration as `371591bf6ebc9ffa442c894bc2436a896c538ffb` |
| live-update-planner | [#60](https://github.com/choko510/DiPlay/pull/60) | `codex/dsp52-update-planner` | `7ff2924610adef5cbee8ae0a5648a60f76abdde1` | independently reviewed with no findings; merged to integration as `9e026e55d75f0c4f6d89cdaf66727bfbd023f40a` |

The [child PR ledger](SUBAGENT_PR_LEDGER.md) has the dispatch evidence, tests, file ownership, and integration commit SHAs.

## Verification

| Gate | Result |
|---|---|
| Shared + Common unit tests | PASS at source content committed as `f9c9f56e8022fa4808ec5023e688e65ede45f498`: 939 total (Shared 668, Common 271), 0 failures/errors/skips. |
| Shared Native debug link | PASS at source content committed as `f9c9f56e8022fa4808ec5023e688e65ede45f498` for arm64-v8a, armeabi-v7a, x86_64 via the mobile/automotive builds. |
| `bash scripts/test_dsp_native.sh` | PASS at source content committed as `f9c9f56e8022fa4808ec5023e688e65ede45f498`. |
| ASan/UBSan Native tests | PASS at source content committed as `f9c9f56e8022fa4808ec5023e688e65ede45f498` with `detect_leaks=0`; default LeakSanitizer is unsupported by Apple Clang on this host. Linux CI at prior head `d8637ca9` passed the default sanitizer. |
| Android-test Kotlin compilation | PASS at source content committed as `f9c9f56e8022fa4808ec5023e688e65ede45f498` for `:common:compileDebugAndroidTestKotlin`. |
| Public-tree, EX-08 `--check`, Android CI classifier/evidence, JNI mapping, and inventory fixtures | PASS at source content committed as `f9c9f56e8022fa4808ec5023e688e65ede45f498`; EX-08 inventory is 1,400 unique rows and delta-111 fixtures passed 17/17. |
| Mobile/Automotive Debug, lint, and Benchmark gates | PASS at source content committed as `f9c9f56e8022fa4808ec5023e688e65ede45f498` in the full CI-equivalent Gradle command (365 actionable tasks). |
| Common unit tests | PASS at `ce3c87d7f8520d0752a96f4fa50aa8b92b988641`: Common 271/271. Shared 668/668 passed at `f9c9f56e`; no Shared inputs changed afterward. Combined total: 939, 0 failures/errors/skips. |
| Common unit tests | PASS at `6439a5b94e21da3bb5e2b921a2ac035c8d969079`: Common 272/272, including `DspProfileRuntimeTest` 16/16. Shared inputs did not change; prior Shared 668/668 remains applicable. |
| Connected Android instrumentation | API 35 `DspSettingsScreenTest` PASS 4/4 at `ce3c87d7f8520d0752a96f4fa50aa8b92b988641`; report: `common/build/reports/androidTests/connected/debug/com.shilapi.xcertplay.DspSettingsScreenTest.html`. `DspModulePresetPaneTest` 4/4 remains verified at `da7f89ac1adf2ac94aa82a9549ca007ea7f65933`. |
| PR #52 hosted CI | PASS on source head `ce3c87d7f8520d0752a96f4fa50aa8b92b988641` | Run [#40370259506](https://github.com/choko510/DiPlay/actions/runs/40370259506): `changes`, `check`, `android-instrumentation`, and `host-baseline` succeeded. `quick-baseline` and PR-only `verification-status` were skipped because the run used `workflow_dispatch`; neither is counted as a pass. |
| PR #52 hosted CI | PASS on source head `6439a5b94e21da3bb5e2b921a2ac035c8d969079` | Run [#40374442067](https://github.com/choko510/DiPlay/actions/runs/40374442067): `changes`, `check`, `android-instrumentation`, and `host-baseline` passed. `quick-baseline` and PR-only `verification-status` were skipped for `workflow_dispatch`; neither is counted as a pass. |
| TS10/K706/iPhone physical acceptance | NOT_RUN. |

The command ledger and environment limitations are in [TEST_MATRIX.md](TEST_MATRIX.md). The detailed per-ID catalog is in [TEST_MATRIX_DETAILED.md](TEST_MATRIX_DETAILED.md).

## Independent reviews and remaining risks

- Independent reviews covered Native algorithms, Kotlin/JNI synchronization, UI/saved data, QA/integration, runtime flags, and evidence. The UI review found no P0–P2 issue. The audio review's HOT-stop-flag finding was fixed and covered by `disablingHotUpdatesAfterPreparationDefersWithoutEnqueueing`; its follow-up found no remaining P0–P2 issue. The evidence review's stale-SHA/readiness findings have been corrected; exact per-ID method/command/SHA/artifact mapping remains open.
- The Native generation-token mismatch and stale ACK after context rebind are fixed and covered. UI preference isolation and pre-Preview checks are covered by the standalone screen test. Five-tab navigation, playback-volume monitoring, runtime feature flags, and Macro drag transactions are implemented in the current source diff.
- Six versioned Macro factory presets already exist. Module-scoped A/B is implemented and tested; level matching remains unavailable without a same-source measurement window. Opt-in PEQ/Static Bass local warm-up is implemented; Cabin Correction and IR remain deferred. Evidence gaps include exact 170 per-ID test-method/command/SHA/artifact mapping. Hardware gaps include actual CarPlay 55:45 geometry/session/Surface/touch continuity, route calibration, acoustic/performance measurements, A/V, Siri/call/navigation, 20 Split cycles, and soak. No hardware result is inferred from emulator instrumentation.

## Human follow-up

Decision at source `fe083f4f` / hosted head `1e4a8962`: `IMPLEMENTED_REVIEW_READY=false`, `LOCAL_GATES_GREEN=true`, `CI_GREEN=true` for run #506, `HARDWARE_PASS=false`, `MERGE_READY=false`. `quick-baseline` was skipped and is not counted as a pass. PR #52 remains open and unmerged; PR #45 and `main` remain untouched. Review readiness still requires the open ID-level evidence gate and hardware acceptance; hardware remains `NOT_RUN` until the runbook is executed on TS10/K706 and iPhone.
