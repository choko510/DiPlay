# DSP Studio final implementation report

**Disposition:** `PARTIAL / FINAL_HEAD_CI_AND_EVIDENCE_PENDING`

**Integration PR:** [#52](https://github.com/choko510/DiPlay/pull/52), base `codex/step4-pr-00-test-baseline`, stacked on PR #45. PR #52 is open and unmerged. PR #45 and `main` remain unmerged.

**Implementation/test snapshot:** `da7f89ac1adf2ac94aa82a9549ca007ea7f65933` on `codex/dsp52-base-sync`, six commits ahead of `origin/codex/dsp-studio-final`. This includes PR #64's accessibility merge and the current remediation commits; it has not yet been pushed. Production-code gates passed at parent `8513275490d6c299084337cffdf58859a9cdeb3c`; the latest test commits add and pass selected-module Factory Reset and delete-fallback screen assertions. The report and matrix changes are docs-only updates for this snapshot. PR #52 CI #472 is stale. Do not use PR #60 CI #438 as final-head evidence; a hosted run is required after the branch update.

## Implementation summary

- Added schema-v1 DSP Studio workspace and module-preset stores, independent of the v7 legacy profile store. Draft, Preview, Save, Cancel, A/B, and Undo/Redo retain separate states.
- Added four non-destructive Macro controls, route-bound calibrated Smart Loudness AUTO with MANUAL/FIXED alternatives, and bounded Dynamic Bass with a linked stereo envelope. `DspTuningResolver` allocates a shared 6 dB additional-bass budget.
- Added five Studio navigation tabs, a 15-band pointer-editable graphical PEQ editor, and advanced DSP panes for FULL and COMPACT_SPLIT presentations.
- Added persisted Workspace/Macro/Loudness/Dynamic Bass/HOT rollout flags, apply-failure rollback, and route-owned Android playback-volume monitoring.
- Added renderer-owned HOT updates with generation, editor-owner, renderer-owner, PCM-format, and hot-context validation. Kotlin pending ACK/failure/close/rebind completion uses one-shot CAS. Unsupported stateful or latency-changing work follows WARM or DEFERRED paths.
- Preserved schema-v7 profile handling, the shared PEQ/Cabin capacity, MEDIA-only full-DSP policy, and the existing new-track DSP Master OFF PCM16 bypass contract.

## REQ status

The task-supplied PR #52 implementation specification is preserved in [REMEDIATION_SPEC.md](REMEDIATION_SPEC.md). It is treated as the project's acceptance criteria and remains subordinate to repository and agent instructions.

| Requirement | Status | Evidence and remaining work |
|---|---|---|
| REQ-001 — Non-destructive sound macros | COMPLETE | 0–100 controls, 50 neutral defaults, resolver/native path, UI and persistence tests. |
| REQ-002 — DSP Studio, PEQ, module presets, A/B/history, preview/save separation | PARTIAL | Five-tab navigation, graph editing, module preset deletion, and module Factory Reset are implemented. Module-scoped A/B/level-match UI and CarPlay host geometry/session behavior remain open or NOT_RUN. |
| REQ-003 — Smart Loudness | PARTIAL | Android playback-volume monitoring is wired into the runtime with route identity checks and MANUAL/FIXED alternatives. Target route calibration and OEM trust validation are NOT_RUN. |
| REQ-004 — Dynamic Bass 2.0 | PARTIAL | Native detector now uses a sanitized raw-input tap, with gate, smoothing, stereo link, and bounded gain coverage. Target listening, THD, and performance are NOT_RUN. |
| REQ-005 — Shared gain budget and signal safety | COMPLETE | Resolver and Native tests cover bounded additional bass boost, validation, and finite output. Hardware listening/true-peak evidence remains NOT_RUN. |
| REQ-006 — Hot/Warm/Deferred and generation ACK | PARTIAL | Renderer/JNI/native tests cover ownership, latest-wins, status, stale ACK, failure, close, and context-rebind races. PR #60 adds field-aware classification and truthful reasons; PEQ/Bass stage-local warm-up/history transfer remains unimplemented and safely defers during a committed track. |
| REQ-007 — Live/CarPlay continuity | PARTIAL | Source and renderer compatibility tests pass. TS10/K706 session, format-change, A/V, and long-run acceptance are NOT_RUN. |
| REQ-008 — Full and compact UI accessibility/behavior | PARTIAL | FULL/COMPACT_SPLIT and five-tab navigation are implemented; API 35 screen instrumentation passed locally 1/1. Actual CarPlay 55:45 geometry/touch evidence is NOT_RUN. |
| REQ-009 — Master OFF, MEDIA-only, PCM formats, shared PEQ capacity | COMPLETE | Software stream-policy, PCM, bypass, capacity, Native, and JNI suites pass. Device proof is NOT_RUN. |
| REQ-010 — v0–v7 profile compatibility and persistence | COMPLETE | Migration, future-schema preservation, workspace restore, preset, and IR-reference unit coverage passes. |
| REQ-011 — Final host and hardware acceptance | PARTIAL | API 35 emulator UI tests pass. TS10/K706/iPhone physical route, geometry, listening, latency, and continuity acceptance remain NOT_RUN; use [RUNBOOK.md](RUNBOOK.md). |
| REQ-012 — Reviewable evidence, rollback, and release decision | PARTIAL | Feature flags are now persisted and connected to runtime gates, with apply-failure rollback tests. The 170 T-ID/PH-ID catalog still lacks complete method/command/SHA/artifact mapping. |

## Phase and known-CR disposition

These dispositions are provisional until the current changes have an immutable commit SHA and final-head CI. `PARTIAL` means implementation exists but at least one acceptance assertion or evidence item remains open. Hardware-only work is `NOT_RUN`.

| ID | Disposition | Current evidence | Remaining gate |
|---|---|---|---|
| Phase 01 — Presets and persistence | PARTIAL | Workspace and module preset repositories, validation, scoped apply, migration, user-preset delete with Factory fallback, and selected-module Factory Reset are implemented and tested. | One-to-one evidence for all Phase 01 IDs and remaining save/restart/race assertions. |
| Phase 02 — Five-tab UI | PARTIAL | `DspStudioTab` defines Studio/EQ/Spatial/Dynamics/Advanced; standalone screen instrumentation. | Legacy-feature reachability, host geometry and interaction evidence. |
| Phase 03 — Graphical EQ | PARTIAL | Pointer-down selection and drag editing; API 35 EQ graph instrumentation previously passed 3/3. | Q/type/numeric and overlap-specific mapping, exact frequency tolerance, final-head run. |
| Phase 04 — Preview and persistence separation | PARTIAL | Macro drag updates runtime preview; commit/restore path and isolated screen tests. | Activity recreation/process-death and final-value ACK mapping. |
| Phase 05 — A/B, undo and status | PARTIAL | Whole-workspace A/B, edit transactions, renderer apply status. | Module-scoped A/B UI, level-match comparison, 100-drag evidence. |
| Phase 07 — Live DSP | PARTIAL | HOT for supported overlay fields; stop-flag check before enqueue; unsupported PREAMP/stateful/latency changes report DEFERRED. | Stage-local PEQ/Bass warm-up and complete field-by-field ID/test mapping. |
| CR-001 — Screen instrumentation failure | PARTIAL | API 35 `DspSettingsScreenTest` passed 3/3 locally with isolated preferences/files, draft restore, Factory Reset scope, and selected-delete fallback assertions. | Hosted CI on the pushed PR #52 head remains pending. |
| CR-002 — Macro drag audio preview | PARTIAL | Macro changes trigger runtime preview; pointer-up commits one edit transaction and flushes latest workspace. | Final-value audio ACK assertion on final head. |
| CR-003 — Production loudness source | PARTIAL | `AndroidPlaybackVolumeMonitor` is installed as the runtime volume source; untrusted route readings remain unavailable. | TS10/K706 calibration and OEM behavior. |
| CR-004 — Volume changes after UI close | PARTIAL | Runtime monitor subscribes to Android volume broadcasts and route changes independently of the screen. | Hardware route behavior. |
| CR-005 — Session recreation | PARTIAL | Runtime owns the Studio coordinator and restore path; isolated runtime tests exist. | Activity recreation/process-death E2E. |
| CR-006 — Saved workspace/profile identity | PARTIAL | Runtime reconciles selected profile identity from a loaded workspace and validates source identity. | Exact migration/reselection method mapping. |
| CR-007 — Dynamic Bass detector tap | PARTIAL | PR #59 added a sanitized raw-input detector tap; native signal tests cover it. | Target listening/THD/performance. |
| CR-008 — Five-tab navigation | PARTIAL | Five navigation tabs are implemented. | Direct reachability/accessibility assertions and host interaction. |
| CR-009 — Interactive EQ editor | PARTIAL | Graph supports touch selection/edit and numeric controls; PR #62 fixed pointer-down selection. | Frequency, overlap, Q/type and final-head assertions. |
| CR-010 — Scoped A/B and level match | PARTIAL | Session has a scoped comparison snapshot API. | UI does not yet expose complete module A/B and comparison level-match behavior. |
| CR-011 — Preset delete/reset and IR references | PARTIAL | Persistence tests verify user deletion, future-schema preservation, and factory-ID protection. API 35 `DspModulePresetPaneTest` passes 4/4; screen tests directly verify selected-module Factory Reset and delete-to-Factory fallback without changing other draft fields. | Full IR-reference race/missing-file coverage and exact per-ID evidence. |
| CR-012 — Safe live update fields | PARTIAL | Planner classifies overlay HOT fields and defers unsupported PREAMP/state changes. | Existing DSP HOT/local-warm requirements and per-field tests. |
| CR-013 — Stage-local warm-up | PARTIAL | Unsupported committed-track changes defer with reasons. | PEQ/Bass history transfer and stage-local warm path remain unimplemented. |
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
| PKG-04 stores/session | COMPLETE | Unit coverage passes. |
| PKG-05 Macro UI | PARTIAL | Five-tab navigation is implemented; API 35 screen instrumentation passed locally 1/1. Final-head hosted and physical host acceptance remain open. |
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
| Shared + Common unit tests | PASS at local source head `8513275490d6c299084337cffdf58859a9cdeb3c`: 935 total (Shared 668, Common 267), 0 failures/errors/skips. Shared results were up-to-date from `6b294ff29693fb60fd7364be4e3b0302a619bca7`; no Shared inputs changed afterward. |
| Shared Native debug link | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` for arm64-v8a, armeabi-v7a, x86_64 via the mobile/automotive builds. |
| `bash scripts/test_dsp_native.sh` | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c`. |
| ASan/UBSan Native tests | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` with `detect_leaks=0`; the default `detect_leaks=1` aborts because Apple Clang's LeakSanitizer is unsupported on this host. CI Linux sanitizer result is still required. |
| Android-test Kotlin compilation | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` for `:common:compileDebugAndroidTestKotlin`. |
| Public-tree, EX-08 `--check`, Android CI classifier/evidence, JNI mapping, and inventory fixtures | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c`; EX-08 inventory is 1,400 unique rows. |
| Mobile/Automotive Debug, lint, and Benchmark gates | PASS at `8513275490d6c299084337cffdf58859a9cdeb3c` in the CI-equivalent Gradle command. |
| Connected Android instrumentation | API 35 PASS at test head `da7f89ac1adf2ac94aa82a9549ca007ea7f65933`: `DspModulePresetPaneTest` 4/4 and `DspSettingsScreenTest` 3/3. Console logs: `common/build/reports/dsp-studio/DspModulePresetPaneTest-api35.txt`, `DspSettingsScreenTest-api35.txt`, and focused Factory Reset/delete-fallback logs. |
| PR #52 hosted CI | PENDING for local head `da7f89ac1adf2ac94aa82a9549ca007ea7f65933`; latest known remote run [#472](https://github.com/choko510/DiPlay/actions) was on stale base-sync head `6a1ec65e189838febef2607a6f77335f362eac9b`. |
| TS10/K706/iPhone physical acceptance | NOT_RUN. |

The command ledger and environment limitations are in [TEST_MATRIX.md](TEST_MATRIX.md). The detailed per-ID catalog is in [TEST_MATRIX_DETAILED.md](TEST_MATRIX_DETAILED.md).

## Independent reviews and remaining risks

- Independent reviews covered Native algorithms, Kotlin/JNI synchronization, UI/saved data, QA/integration, runtime flags, and evidence. The UI review found no P0–P2 issue. The audio review's HOT-stop-flag finding was fixed and covered by `disablingHotUpdatesAfterPreparationDefersWithoutEnqueueing`; its follow-up found no remaining P0–P2 issue. The evidence review's stale-SHA/readiness findings have been corrected; exact per-ID method/command/SHA/artifact mapping remains open.
- The Native generation-token mismatch and stale ACK after context rebind are fixed and covered. UI preference isolation and pre-Preview checks are covered by the standalone screen test. Five-tab navigation, playback-volume monitoring, runtime feature flags, and Macro drag transactions are implemented in the current source diff.
- Six versioned Macro factory presets already exist. Remaining source gaps include module-scoped A/B and level-match comparison and stage-local PEQ/Bass warm-up/history transfer. Evidence gaps include exact 170 per-ID test-method/command/SHA/artifact mapping and final-head hosted CI. Hardware gaps include actual CarPlay 55:45 geometry/session/Surface/touch continuity, route calibration, acoustic/performance measurements, A/V, Siri/call/navigation, 20 Split cycles, and soak. No hardware result is inferred from emulator instrumentation.

## Human follow-up

Decision: `IMPLEMENTED_REVIEW_READY=false`, `CI_GREEN=false`, `HARDWARE_PASS=false`, `MERGE_READY=false`. PR #52 remains open and unmerged; PR #45 and `main` remain untouched. Final review readiness requires the open ID-level evidence gate and final-head checks; hardware stays NOT_RUN until the runbook is executed on TS10/K706 and iPhone.
