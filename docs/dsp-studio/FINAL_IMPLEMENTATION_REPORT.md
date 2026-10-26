# DSP Studio final implementation report

**Disposition:** `PARTIAL / MERGE_NO_GO_SOURCE_GAPS_AND_HARDWARE_PENDING`

**Integration PR:** [#52](https://github.com/choko510/DiPlay/pull/52), base `codex/step4-pr-00-test-baseline`, stacked on PR #45. PR #52 is open and unmerged. PR #45 and `main` remain unmerged.

**Current source/test head:** `9e026e55d75f0c4f6d89cdaf66727bfbd023f40a` — PR #60 merged into the PR #52 integration branch. CI [#438](https://github.com/choko510/DiPlay/actions/runs/40280273076) passed at this head. Local Shared/Common unit tests and Android-test Kotlin compilation also passed at this SHA.

## Implementation summary

- Added schema-v1 DSP Studio workspace and module-preset stores, independent of the v7 legacy profile store. Draft, Preview, Save, Cancel, A/B, and Undo/Redo retain separate states.
- Added four non-destructive Macro controls, route-bound calibrated Smart Loudness AUTO with MANUAL/FIXED alternatives, and bounded Dynamic Bass with a linked stereo envelope. `DspTuningResolver` allocates a shared 6 dB additional-bass budget.
- Added a 15-band graphical PEQ editor and advanced DSP panes for FULL and COMPACT_SPLIT presentations.
- Added renderer-owned HOT updates with generation, editor-owner, renderer-owner, PCM-format, and hot-context validation. Kotlin pending ACK/failure/close/rebind completion uses one-shot CAS. Unsupported stateful or latency-changing work follows WARM or DEFERRED paths.
- Preserved schema-v7 profile handling, the shared PEQ/Cabin capacity, MEDIA-only full-DSP policy, and the existing new-track DSP Master OFF PCM16 bypass contract.

## REQ status

The task-supplied PR #52 implementation specification is preserved in [REMEDIATION_SPEC.md](REMEDIATION_SPEC.md). It is treated as the project's acceptance criteria and remains subordinate to repository and agent instructions.

| Requirement | Status | Evidence and remaining work |
|---|---|---|
| REQ-001 — Non-destructive sound macros | COMPLETE | 0–100 controls, 50 neutral defaults, resolver/native path, UI and persistence tests. |
| REQ-002 — DSP Studio, PEQ, module presets, A/B/history, preview/save separation | PARTIAL | Workspace and preset paths exist, but navigation remains four sections rather than the required five product tabs. API 35 standalone screen instrumentation passed in CI #438; CarPlay host and physical geometry/session behavior are NOT_RUN. |
| REQ-003 — Smart Loudness | PARTIAL | Resolver and calibration behavior have unit coverage, but the production runtime still defaults to `ManualVolumeSource`; Android playback-owned trusted volume monitoring is not wired. Target route calibration is NOT_RUN. |
| REQ-004 — Dynamic Bass 2.0 | PARTIAL | Native detector now uses a sanitized raw-input tap, with gate, smoothing, stereo link, and bounded gain coverage. Target listening, THD, and performance are NOT_RUN. |
| REQ-005 — Shared gain budget and signal safety | COMPLETE | Resolver and Native tests cover bounded additional bass boost, validation, and finite output. Hardware listening/true-peak evidence remains NOT_RUN. |
| REQ-006 — Hot/Warm/Deferred and generation ACK | PARTIAL | Renderer/JNI/native tests cover ownership, latest-wins, status, stale ACK, failure, close, and context-rebind races. PR #60 adds field-aware classification and truthful reasons; PEQ/Bass stage-local warm-up/history transfer remains unimplemented and safely defers during a committed track. |
| REQ-007 — Live/CarPlay continuity | PARTIAL | Source and renderer compatibility tests pass. TS10/K706 session, format-change, A/V, and long-run acceptance are NOT_RUN. |
| REQ-008 — Full and compact UI accessibility/behavior | PARTIAL | FULL/COMPACT_SPLIT standalone screen test passed in CI #438. Navigation remains four sections, and actual CarPlay 55:45 geometry/touch evidence is NOT_RUN. |
| REQ-009 — Master OFF, MEDIA-only, PCM formats, shared PEQ capacity | COMPLETE | Software stream-policy, PCM, bypass, capacity, Native, and JNI suites pass. Device proof is NOT_RUN. |
| REQ-010 — v0–v7 profile compatibility and persistence | COMPLETE | Migration, future-schema preservation, workspace restore, preset, and IR-reference unit coverage passes. |
| REQ-011 — Final host and hardware acceptance | BLOCKED | No connected device or TS10/K706/iPhone test setup was available; run the gates in [RUNBOOK.md](RUNBOOK.md). |
| REQ-012 — Reviewable evidence, rollback, and release decision | PARTIAL | The source specification, CI investigation, runbook, and review matrix are present. Feature flag constants are still unconnected, and the 170 T-ID/PH-ID evidence catalog remains incomplete. |

## Package status

| Package | Status | Note |
|---|---|---|
| PKG-00 baseline | COMPLETE | Parent and stacked-PR boundaries, environment, and historical baseline captured. |
| PKG-01 workspace/Macro/resolver | COMPLETE | Source, persistence, and resolver tests pass. |
| PKG-02 Native stages | COMPLETE | Native tests, sanitizers, and three-ABI debug link pass. |
| PKG-03 live update | COMPLETE | Renderer ownership and generation regressions pass. |
| PKG-04 stores/session | COMPLETE | Unit coverage passes. |
| PKG-05 Macro UI | PARTIAL | API 35 screen instrumentation passed in CI #438; the required five-tab product navigation and physical host acceptance remain open. |
| PKG-06 Smart Loudness | PARTIAL | No target route calibration result. |
| PKG-07 Dynamic Bass | PARTIAL | No target acoustic/performance result. |
| PKG-08 live compatibility | PARTIAL | No target CarPlay/A/V/soak result. |
| PKG-09 PEQ/advanced UI | PARTIAL | Actual head-unit layout/touch not exercised. |
| PKG-10 integration | PARTIAL | Eight child PRs (#53–#60) are integrated into the stack. CI #438 is green; target acceptance and operational rollback remain open. |

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
| Shared + Common unit tests | PASS at `9e026e55d75f0c4f6d89cdaf66727bfbd023f40a`: 866 total, Shared 639 and Common 227, 0 failures/errors/skips. |
| Shared Native debug link | PASS for arm64-v8a, armeabi-v7a, x86_64. |
| `bash scripts/test_dsp_native.sh` | PASS. |
| ASan/UBSan Native tests | PASS with `detect_leaks=0`; LeakSanitizer is unsupported on this host. |
| Android-test Kotlin compilation | PASS for Shared and Common, including isolated `DspSettingsScreenTest`. |
| Public-tree, EX-08 `--check`, Android CI classifier, `git diff --check` | PASS; EX-08 catalog has 1,289 rows. |
| Mobile/Automotive Debug, lint, Benchmark, and JNI mapping gates | PASS in the earlier integrated source gate; not rerun after the final instrumentation-test isolation helper due host disk constraints. |
| Connected Android instrumentation | PASS in API 35 CI #438; local connected execution NOT_RUN because no device was available and the AVD could not start with available host disk space. |
| PR #52 hosted CI | PASS, run [#438](https://github.com/choko510/DiPlay/actions/runs/40280273076) at `9e026e55d75f0c4f6d89cdaf66727bfbd023f40a`; Release verification passed. |
| TS10/K706/iPhone physical acceptance | NOT_RUN. |

The command ledger and environment limitations are in [TEST_MATRIX.md](TEST_MATRIX.md). The detailed per-ID catalog is in [TEST_MATRIX_DETAILED.md](TEST_MATRIX_DETAILED.md).

## Independent reviews and remaining risks

- Four independent tracks reviewed Native algorithms, Kotlin/JNI synchronization, UI/saved data, and QA/integration. Their reviews reported no new actionable P0/P1 finding. Known P1 implementation gaps remain explicit: production loudness monitoring, five-tab navigation, functional rollback flags, and stage-local PEQ/Bass warm-up.
- The Native review's generation-token mismatch was fixed and covered. The synchronization follow-up's stale ACK after context rebind is covered by a renderer test. UI review's persistence-side-effect and pre-Preview coverage findings are addressed by the isolated screen test. QA's test/matrix gaps are reflected in the final matrix.
- Remaining source and evidence gaps include the required five-tab navigation, operational feature flags, playback-owned volume monitoring, transaction-level drag undo/automatic preview, stage-local PEQ/Bass warm-up, and the 170 per-ID test evidence mapping. Hardware gaps include actual CarPlay 55:45 geometry/session/Surface/touch continuity, route-volume calibration, target acoustic/performance measurements, A/V, Siri/call/navigation, 20 Split cycles, and soak. No hardware result is inferred from a standalone composable test.
- `DspStudioFeatureFlags` constants have no production call sites and are not effective runtime kill switches. Rollback for this build is not proven; do not treat DSP Master OFF as satisfying the specification's per-feature rollback gates. This limitation is explicit in [REVIEW_MATRIX.md](REVIEW_MATRIX.md).

## Human follow-up

Review PR #52 and run [RUNBOOK.md](RUNBOOK.md) on TS10/K706 with the supported iPhone before merging. PR #52, PR #45, and `main` remain unmerged.
