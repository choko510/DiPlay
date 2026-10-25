# DSP Studio final implementation report

**Disposition:** `IMPLEMENTED_REVIEW_READY / MERGE_NO_GO_HARDWARE_PENDING`

**Integration PR:** [#52](https://github.com/choko510/DiPlay/pull/52), base `codex/step4-pr-00-test-baseline`, stacked on open Draft PR #45. PR #52 is not merged. PR #45 and `main` remain unmerged.

**Source/test commit:** `dff1878b` — final renderer ownership regressions and isolated screen instrumentation source. The full local test command passed against this exact source tree. The final documentation/evidence changes are committed afterward on the same integration branch; verify the final branch head in PR #52 metadata.

## Implementation summary

- Added schema-v1 DSP Studio workspace and module-preset stores, independent of the v7 legacy profile store. Draft, Preview, Save, Cancel, A/B, and Undo/Redo retain separate states.
- Added four non-destructive Macro controls, route-bound calibrated Smart Loudness AUTO with MANUAL/FIXED alternatives, and bounded Dynamic Bass with a linked stereo envelope. `DspTuningResolver` allocates a shared 6 dB additional-bass budget.
- Added a 15-band graphical PEQ editor and advanced DSP panes for FULL and COMPACT_SPLIT presentations.
- Added renderer-owned HOT updates with generation, editor-owner, renderer-owner, PCM-format, and hot-context validation. Kotlin pending ACK/failure/close/rebind completion uses one-shot CAS. Unsupported stateful or latency-changing work follows WARM or DEFERRED paths.
- Preserved schema-v7 profile handling, the shared PEQ/Cabin capacity, MEDIA-only full-DSP policy, and the existing new-track DSP Master OFF PCM16 bypass contract.

## REQ status

The detailed specification named in the goal, `DiPlay_PR45_Codex_Final_DSP_Studio_Macro_Loudness_DynamicBass_2026-10-26.md`, was not present in the supplied attachment directory or repository. These statuses map the accessible goal and original test matrix; they are not a substitute for that unavailable specification.

| Requirement | Status | Evidence and remaining work |
|---|---|---|
| REQ-001 — Non-destructive sound macros | COMPLETE | 0–100 controls, 50 neutral defaults, resolver/native path, UI and persistence tests. |
| REQ-002 — DSP Studio, PEQ, module presets, A/B/history, preview/save separation | PARTIAL | Implementation and unit/UI-contract suites exist. Final standalone screen test compiles but connected interaction, real Split layout, and host Surface/session behavior are NOT_RUN. |
| REQ-003 — Smart Loudness | PARTIAL | AUTO requires a trustworthy reading matching the calibrated route; MANUAL/FIXED and fallback tests pass. Target route calibration is NOT_RUN. |
| REQ-004 — Dynamic Bass 2.0 | PARTIAL | Native detector, gate, smoothing, stereo link, and bounded gain have software tests. Target listening, THD, and performance are NOT_RUN. |
| REQ-005 — Shared gain budget and signal safety | COMPLETE | Resolver and Native tests cover bounded additional bass boost, validation, and finite output. Hardware listening/true-peak evidence remains NOT_RUN. |
| REQ-006 — Hot/Warm/Deferred and generation ACK | COMPLETE | Renderer/JNI/native tests cover ownership, latest-wins, status, stale ACK, failure, close, and context-rebind races. |
| REQ-007 — Live/CarPlay continuity | PARTIAL | Source and renderer compatibility tests pass. TS10/K706 session, format-change, A/V, and long-run acceptance are NOT_RUN. |
| REQ-008 — Full and compact UI accessibility/behavior | PARTIAL | FULL/COMPACT_SPLIT composable path and editor-contract tests exist. Connected UI and actual CarPlay 55:45 geometry/touch evidence are NOT_RUN. |
| REQ-009 — Master OFF, MEDIA-only, PCM formats, shared PEQ capacity | COMPLETE | Software stream-policy, PCM, bypass, capacity, Native, and JNI suites pass. Device proof is NOT_RUN. |
| REQ-010 — v0–v7 profile compatibility and persistence | COMPLETE | Migration, future-schema preservation, workspace restore, preset, and IR-reference unit coverage passes. |
| REQ-011 — Final host and hardware acceptance | BLOCKED | No connected device or TS10/K706/iPhone test setup was available; run the gates in [RUNBOOK.md](RUNBOOK.md). |
| REQ-012 — Reviewable evidence, rollback, and release decision | PARTIAL | Evidence matrices and a hardware runbook are present. The detailed source specification is unavailable, and declared rollout constants are not wired as production switches. |

## Package status

| Package | Status | Note |
|---|---|---|
| PKG-00 baseline | COMPLETE | Parent and stacked-PR boundaries, environment, and historical baseline captured. |
| PKG-01 workspace/Macro/resolver | COMPLETE | Source, persistence, and resolver tests pass. |
| PKG-02 Native stages | COMPLETE | Native tests, sanitizers, and three-ABI debug link pass. |
| PKG-03 live update | COMPLETE | Renderer ownership and generation regressions pass. |
| PKG-04 stores/session | COMPLETE | Unit coverage passes. |
| PKG-05 Macro UI | PARTIAL | Screen test compiles; connected instrumented execution is blocked. |
| PKG-06 Smart Loudness | PARTIAL | No target route calibration result. |
| PKG-07 Dynamic Bass | PARTIAL | No target acoustic/performance result. |
| PKG-08 live compatibility | PARTIAL | No target CarPlay/A/V/soak result. |
| PKG-09 PEQ/advanced UI | PARTIAL | Actual head-unit layout/touch not exercised. |
| PKG-10 integration | PARTIAL | Six child PRs integrated into the stack. Target acceptance and current hosted CI status remain unverified. |

## Luna child PRs

All six child PRs were dispatched to `gpt-6-luna`, targeted `codex/dsp-studio-final`, and are merged only into that integration branch. None was merged into PR #45 or `main`.

| Task | PR | Branch | Child head SHA | Integration result |
|---|---|---|---|---|
| smart-loudness | [#53](https://github.com/choko510/DiPlay/pull/53) | `codex/dsp-studio-smart-loudness` | `02d79ba2ccd56992b947fbab2e82b80bfe91769e` | merged to integration |
| macro-ui | [#54](https://github.com/choko510/DiPlay/pull/54) | `codex/dsp-studio-macro-ui` | `ac7aa0478d621f1e33b6a3acca663b952522e92f` | merged to integration |
| preset-workspace | [#55](https://github.com/choko510/DiPlay/pull/55) | `codex/dsp-studio-preset-workspace` | `48c90a09a5f0df95a12fb35e7a17058f1e04def9` | merged to integration |
| eq-advanced-ui | [#56](https://github.com/choko510/DiPlay/pull/56) | `codex/dsp-studio-eq-advanced-ui` | `9c278b8078b2055a61e57b8ebebf572e21c2cccc` | merged to integration |
| dynamic-bass | [#57](https://github.com/choko510/DiPlay/pull/57) | `codex/dsp-studio-dynamic-bass` | `92febd9ade3fc9d2d88de0d76689558a55a189e3` | merged to integration |
| live-compat | [#58](https://github.com/choko510/DiPlay/pull/58) | `codex/dsp-studio-live-compat` | `864eb36cc516720e6fe1f7bad3249550eddd1866` | merged to integration |

The [child PR ledger](SUBAGENT_PR_LEDGER.md) has the dispatch evidence, tests, file ownership, and integration commit SHAs.

## Verification

| Gate | Result |
|---|---|
| Shared + Common unit tests | PASS, 860 total: Shared 634, Common 226, 0 failures/errors/skips. |
| Shared Native debug link | PASS for arm64-v8a, armeabi-v7a, x86_64. |
| `bash scripts/test_dsp_native.sh` | PASS. |
| ASan/UBSan Native tests | PASS with `detect_leaks=0`; LeakSanitizer is unsupported on this host. |
| Android-test Kotlin compilation | PASS for Shared and Common, including isolated `DspSettingsScreenTest`. |
| Public-tree, EX-08 `--check`, Android CI classifier, `git diff --check` | PASS; EX-08 catalog has 1,289 rows. |
| Mobile/Automotive Debug, lint, Benchmark, and JNI mapping gates | PASS in the earlier integrated source gate; not rerun after the final instrumentation-test isolation helper due host disk constraints. |
| Connected Android instrumentation | BLOCKED_ENV: `adb devices -l` was empty; AVD startup also failed for insufficient host disk space. |
| TS10/K706/iPhone physical acceptance | NOT_RUN. |

The complete command ledger, exact limitations, and coverage mapping are in [TEST_MATRIX.md](TEST_MATRIX.md). No hosted CI result is claimed for the final head.

## Independent reviews and remaining risks

- Four independent tracks reviewed Native algorithms, Kotlin/JNI synchronization, UI/saved data, and QA/integration. All source-correctable P0/P1 findings were addressed; no open P0/P1 finding was reported.
- The Native review's generation-token mismatch was fixed and covered. The synchronization follow-up's stale ACK after context rebind is covered by a renderer test. UI review's persistence-side-effect and pre-Preview coverage findings are addressed by the isolated screen test. QA's test/matrix gaps are reflected in the final matrix.
- Remaining P2/evidence gaps are actual CarPlay 55:45 geometry/session/Surface/touch continuity, route-volume calibration, target acoustic/performance measurements, A/V, Siri/call/navigation, 20 Split cycles, and soak. No hardware result is inferred from a standalone composable test.
- `DspStudioFeatureFlags` constants are default false but have no production call sites. They are not effective runtime kill switches. Rollback for a review build is DSP Master OFF, new modules disabled/default-neutral, and unsupported stateful/latency changes DEFERRED. This limitation is explicit in [REVIEW_MATRIX.md](REVIEW_MATRIX.md).

## Human follow-up

Review PR #52 and run [RUNBOOK.md](RUNBOOK.md) on TS10/K706 with the supported iPhone before merging. PR #52, PR #45, and `main` remain unmerged.
