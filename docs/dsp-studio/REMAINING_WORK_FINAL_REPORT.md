# PR #52 remaining-work completion report — 2026-10-28

## Scope and repository state

- Repository: [choko510/DiPlay](https://github.com/choko510/DiPlay).
- PR: [#52](https://github.com/choko510/DiPlay/pull/52), branch `codex/dsp-studio-final`, base `codex/step4-pr-00-test-baseline` at `a99066f6133c2fe6f5258a2515a89986b75e042e`.
- Current product source: `fbfc3b6e02c8590d80352d88b5c3c7e9edfe0145`; evidence-test source: `685d4e02577fc74f23af1d7b1b5c3ceb23fd6d47`. Final documentation commit and hosted verification are pending.
- PR #52 is open and unmerged. PR #45 and `main` were not modified. Do not merge before hardware acceptance and the user's approval.
- Previously integrated feature child PRs remain in `SUBAGENT_PR_LEDGER.md`; the final follow-up changes were implemented directly by Codex after the user's instruction. Read-only review contexts did not write code.
- Notable integrated PRs for this remaining-work scope: [#68 Preamp HOT](https://github.com/choko510/DiPlay/pull/68), [#76 evidence map](https://github.com/choko510/DiPlay/pull/76), [#77 Dynamics/Dynamic EQ HOT](https://github.com/choko510/DiPlay/pull/77), and [#78 Save/Cancel race](https://github.com/choko510/DiPlay/pull/78). All target the PR #52 branch and are integrated there.

## Final disposition

```text
SOURCE_READY=FALSE_G4_INCOMPLETE
FULL_CI_GREEN=TRUE_AT_RUN_40704199235
HARDWARE_PASS=FALSE
USER_APPROVAL=NOT_GIVEN
MERGE_READY=FALSE
OPEN_SOURCE_P0_P1=0
OPEN_ACCEPTANCE_EVIDENCE=162_PARTIAL
```

The product source tree at `fbfc3b6e02c8590d80352d88b5c3c7e9edfe0145` and evidence-test source `685d4e02577fc74f23af1d7b1b5c3ceb23fd6d47` passed the final full hosted workflow #40704199235 at PR head `d0abecc59f835d07bfdb3e20a43ef9f2f3e6d881`. Subsequent completion-report and working-memory edits touch only docs/scratchpad and do not change the tested source tree. No TS10/K706 or supported iPhone was connected, so physical acceptance remains `NOT_RUN` and PR #52 is not merge-ready.

## REM-01..12 status

| ID | Status | Result and remaining evidence |
|---|---|---|
| REM-01 | `PARTIAL` | Safe Preamp HOT uses the shared bounded mailbox, 20 ms sample ramp, headroom reserve, owner/format/generation fence and final ACK. Added Native assertion that active Compressor/Limiter state, Convolver history and latency remain unchanged when the ramp starts. Target acoustic/p99 acceptance remains open. |
| REM-02 | `PARTIAL` | Dynamics and Dynamic EQ safe parameters are field-planned and atomically preflighted. Threshold/ratio/attack/release/cap changes preserve envelope/filter state; topology and unrepresentable cap increases defer without partial mutation. Physical audio acceptance remains open. |
| REM-03 | `PARTIAL` | Compressor safe parameters and Limiter release/threshold reductions can HOT update; unsafe topology changes defer. Standalone Width and symmetric Seat trim can WARM at unchanged latency; asymmetric trim, distance, polarity, near-speaker and topology changes defer. Physical phase/AV acceptance remains open. |
| REM-04 | `PARTIAL` | Save/Cancel commits are generation-fenced at `AtomicFile.finishWrite`; Cancel-before-commit aborts, Save-before-Cancel becomes the restore target. Both orderings, nonblocking Cancel, AtomicFile rollback and Save failure status separation are tested. Full retry, later Native failure and process-death matrix remains incomplete. |
| REM-05 | `PARTIAL` | Five tabs have independent saveable scroll state. API 35 test performs 100 tab transitions and checks per-tab scroll, EQ selection/value and unsaved draft. TalkBack, external keyboard, rotation and physical CarPlay geometry remain open. |
| REM-06 | `SAFELY_DEFERRED` | Module A/B remains scoped and does not persist comparison gain. Level Match stays unavailable without the same measured source window; no inaccurate gain estimate is applied. |
| REM-07 | `PARTIAL` | All 170 canonical IDs and 25 work packages are present. Matrix/tracker status parity is enforced; every PASS requires complete evidence. Current counts: PASS 2 / PARTIAL 162 / NOT_RUN 6. |
| REM-08 | `PARTIAL` | API 35 emulator FullHeavyChain baseline/current medians and the three-run benchmark setup are recorded in [PERFORMANCE_BASELINE_2026-10-28.md](PERFORMANCE_BASELINE_2026-10-28.md). Emulator tail variance is visible; target CPU/RSS, callback overload and soak remain unmeasured. LOCAL_WARM remains opt-in and disabled by default. |
| REM-09 | `NOT_RUN` | No target head unit/iPhone. Physical Split, Surface/session/touch, route, voice-role, A/V, acoustic, thermal, CPU/RSS and soak checks remain unrun. See [RUNBOOK.md](RUNBOOK.md). |
| REM-10 | `PARTIAL` | Same Android `STREAM_MUSIC` index remains untrusted, AUTO fails closed with zero gain, and 20 AUTO/OFF updates preserve profile, pipeline and latency. Physical MCU knob-to-route correlation/calibration remains unrun. |
| REM-11 | `PARTIAL` | Existing graphical PEQ, numeric editing, selected-overlap semantics, localized descriptions and final gesture flush remain in place. Full filter/Native golden response and final audio ACK mapping across formats remain partial. |
| REM-12 | `PARTIAL` | Four read-only reviews at source `fbfc3b6e` found no P0/P1. Local Native, Shared/Common and evidence gates pass; the final full hosted run and final docs head still need verification. |

## PKG-R00..R12 status

All 13 work packages are tracked with dependencies, files, implementation evidence, tests, risk and review in [REMAINING_FIX_TRACKER.md](REMAINING_FIX_TRACKER.md). None is hidden behind an aggregate acceptance claim. `PKG-R04` includes the new Preamp state-preservation Native test; `PKG-R10` includes the matrix/tracker parity validator and overwrite protection; `PKG-R12` remains open until final hosted verification and hardware disposition.

## Phase 01–05 and Phase 07 state

- Phase 01: legacy schema-v7 profile data remains separate from schema-v1 Studio workspace and module preset stores. Preset scoping, validation, AtomicFile rollback, future-schema protection, IR references and selected-module draft behavior are implemented. Remaining per-ID save/restart/race evidence is partial.
- Phase 02: Studio/EQ/Spatial/Dynamics/Advanced tabs and FULL/COMPACT_SPLIT entry points are present. Independent scroll state and the 100-switch draft regression pass. Real host geometry/touch/session proof and physical accessibility remain open.
- Phase 03: the existing 15-band graphical PEQ and numeric controls remain the production editor. Pointer mapping/overlap selection and the 100-switch selected-band state are tested. Full Native response goldens and final audio ACK mapping remain partial.
- Phase 04: Preview, Save, Cancel, A/B and history remain distinct. Save/Cancel ordering now uses a generation decision immediately before AtomicFile commit. Renderer status is stored as one atomic owner/status pair, without the prior monitor lock on status reports. Screen-level Cancel retry and later Native failure coverage remain partial.
- Phase 05: module A/B preview preserves unrelated modules and current draft. Level Match is deliberately unavailable until both sides can be measured from the same source window; comparison gain is never persisted.
- Phase 07: Preamp HOT ramp, Compressor/Limiter safe parameter HOT, Dynamic EQ parameter HOT, safe Spatial WARM, and explicit topology DEFER are implemented. PEQ/Static Bass LOCAL_WARM remains opt-in; Cabin Correction and IR topology changes remain safely deferred. Master OFF's new-track PCM16 bypass and MEDIA-only policy remain unchanged in source tests.

## 170 acceptance evidence

The canonical [TEST_MATRIX_DETAILED.md](TEST_MATRIX_DETAILED.md) and [REMAINING_FIX_TRACKER.md](REMAINING_FIX_TRACKER.md) contain all 60 PH IDs plus 110 T IDs.

| Result | Count | Meaning |
|---|---:|---|
| PASS | 2 | Complete per-ID assertion, exact source/run/artifact, environment, reviewer and timestamp fields are present. |
| PARTIAL | 162 | Software evidence exists, but one or more acceptance subpredicates, source-specific evidence fields, or hardware clauses remain open. |
| NOT_RUN | 6 | PH02-T05, T-PERF-09/10/11 and T-UI-10/11 require a real host-unit/iPhone session, route, or long-run device measurement. |

The checker rejects ID drift, duplicate IDs, matrix/tracker status drift, incomplete PASS evidence, invalid run URLs, missing runbook references, and overwriting an existing ledger via `--write-tracker`. Fixture suite: 24/24 locally.

## Verification

### Current product/test source `685d4e02` (product source tree `fbfc3b6e02c8590d80352d88b5c3c7e9edfe0145`)

| Command/check | Result |
|---|---|
| `:shared:testDebugUnitTest :common:testDebugUnitTest` | PASS: Shared 704/704, Common 319/319, 0 failures/errors/skips from XML. |
| `bash scripts/test_dsp_native.sh` | PASS, including active Compressor/Limiter/Convolver Preamp state-preservation test. |
| `bash scripts/test_dsp_native.sh --sanitize` | Local `BLOCKED_ENV`: MSYS2 UCRT toolchain lacks linkable `libasan` and `libubsan`; hosted final source run is required. |
| `:mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark :mobile:lintDebug :automotive:lintDebug`; `python scripts/check_jni_mapping.py` | PASS: local Gradle ran 359 actionable tasks and verified both Benchmark mapping files at `fbfc3b6e`. The installed NDK emits non-fatal missing `source.properties`/unstripped-library warnings. |
| `python scripts/check_dsp_studio_evidence.py` | PASS: 170 rows and 25 work packages; 2/162/6 statuses. |
| `python -m unittest scripts.test_dsp_studio_evidence -v` | PASS: 24/24. |

### Hosted parent-source run

Run [#575](https://github.com/choko510/DiPlay/actions/runs/40686737568) passed at source `7aa7ba41a1c4f3b97c44d0f305a72276219e7847`: Native, ASan/UBSan, Shared/Common unit tests, lint, Mobile/Automotive Debug and Benchmark builds, JNI mapping, and API 35 Shared Release 23/23 + Common Debug 29/29 instrumentation. Artifacts: [host checks](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12814733794) and [instrumentation](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12815317901). `quick-baseline` and Release verification were skipped for `workflow_dispatch`; this is not a final-source run.

An interim dispatch, [run #406973](https://github.com/choko510/DiPlay/actions/runs/40697339783), used source `fbfc3b6e` before the corrected matrix/tracker docs were pushed. Its `changes` and API 35 `android-instrumentation` jobs passed; instrumentation artifact [12820865994](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12820865994) is tied to the exact source SHA. The `check` job failed at the new evidence status-parity validator because that checkout still had the previous docs snapshot (PH01-T03/T04, PH05-T01 and PH07-T07 status drift); it stopped before Native/unit/build/lint/JNI steps. This failed overall run is not counted as full CI evidence.

**Final hosted workflow:** [#40704199235](https://github.com/choko510/DiPlay/actions/runs/40704199235) completed successfully at exact PR head `d0abecc59f835d07bfdb3e20a43ef9f2f3e6d881`, with product source tree `fbfc3b6e02c8590d80352d88b5c3c7e9edfe0145` and evidence-test source `685d4e02577fc74f23af1d7b1b5c3ceb23fd6d47`. `changes`, `check`, Native, ASan/UBSan, Shared/Common, lint/Debug/Benchmark builds, JNI/R8 mapping, API 35 instrumentation, and `host-baseline` succeeded. API 35 Shared Release 23/23 and Common Debug 29/29 passed. Artifacts: [check](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12823880834) and [instrumentation](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12822704992). `quick-baseline` and Release verification were skipped for `workflow_dispatch` and are not counted as passes.

## Independent review findings

At source `fbfc3b6e`, independent Native, Runtime/JNI, UI/Storage, and QA/evidence reviewers found no open P0/P1. The Native review's requested Compressor/Limiter/Convolver state assertion was added and the Native regular suite passed. The Runtime review's monitor-lock finding was replaced by an atomic owner/status pair and a concurrent stale-owner regression; re-review found the owner/generation fence intact. The QA review's status drift was fixed and the checker now enforces parity. The UI review's remaining P2 evidence gaps are screen-level Cancel retry, Save retry after I/O failure, TalkBack/external keyboard, and physical host flow; no false ACTIVE/saved state was observed.

## Rollback, hardware, and approval

- Keep `LOCAL_WARM` disabled by default unless target p99, click/pop, latency and AV acceptance justify enabling it. Cabin and IR remain deferred when topology or latency cannot be preserved.
- If HOT/status behavior regresses, disable the Studio HOT rollout flag and keep the previous active graph; Master OFF continues to use the legacy bypass contract. Existing runtime feature-flag persistence rollback remains in place.
- No TS10/K706/iPhone hardware was present. Hardware-only results stay `NOT_RUN`; do not infer vehicle acceptance from emulator results.
- PR #52 remains open/unmerged. User merge approval is not given. PR #45 and `main` remain untouched.
