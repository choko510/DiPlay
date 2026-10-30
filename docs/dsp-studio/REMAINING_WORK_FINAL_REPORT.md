# PR #52 remaining-work completion report — 2026-10-30

## Scope and repository state

- Repository: [choko510/DiPlay](https://github.com/choko510/DiPlay).
- PR: [#52](https://github.com/choko510/DiPlay/pull/52), branch `codex/dsp-studio-final`, base `codex/step4-pr-00-test-baseline` at `9ea41f8e5f5f9a0bc11eede5d2bac1adce3efd7c`.
- Prior integrated source/test head: `2a6c04399012aff2b42c0d77f2675930104a2084`, verified by hosted run #40937236189 (#592). The base advanced again externally and was merged into PR #52 at `471d025aa501b2d37f3e6cfd8cb8f9e44c80af82`.
- GitHub reports PR #52 open, unmerged, and mergeable. Locally, the synced head passes Shared/Common unit tests 1120/1120, mobile/automotive Benchmark builds, and JNI mapping verification. Hosted run [#41007508604](https://github.com/choko510/DiPlay/actions/runs/41007508604) (#596) passed Native/ASan/UBSan, Shared/Common, lint/build, JNI/R8, Android instrumentation, host-baseline, and Release verification at exact head `471d025aa501b2d37f3e6cfd8cb8f9e44c80af82`. PR #45 and `main` were not pushed by this task. Do not merge before hardware acceptance and the user's approval.
- Previously integrated feature child PRs remain in `SUBAGENT_PR_LEDGER.md`; the final follow-up changes were implemented directly by Codex after the user's instruction. Read-only review contexts did not write code.
- Notable integrated PRs for this remaining-work scope: [#68 Preamp HOT](https://github.com/choko510/DiPlay/pull/68), [#76 evidence map](https://github.com/choko510/DiPlay/pull/76), [#77 Dynamics/Dynamic EQ HOT](https://github.com/choko510/DiPlay/pull/77), and [#78 Save/Cancel race](https://github.com/choko510/DiPlay/pull/78). All target the PR #52 branch and are integrated there.

## Final disposition

```text
SOURCE_READY=FALSE_G4_INCOMPLETE
FULL_CI_GREEN=TRUE_AT_RUN_40937236189
HARDWARE_PASS=FALSE
USER_APPROVAL=NOT_GIVEN
MERGE_READY=FALSE
OPEN_SOURCE_P0_P1=0
POST_R01_INDEPENDENT_REVIEW=NOT_RUN
OPEN_ACCEPTANCE_EVIDENCE=162_PARTIAL
```

Integrated source/test head `2a6c04399012aff2b42c0d77f2675930104a2084` passed pull-request workflow #40937236189 (#592), including Native/ASan/UBSan, Shared/Common, lint/build/JNI/R8, API 35 instrumentation, host-baseline and Release verification. API 35 passed 23/23 Shared Release and 29/29 Common Debug tests. At prior head `00ac7453`, run #589 produced three API 35 Shared Release attempts: the first failed FullHeavyChain wall-clock p99 at 63.232 ms against the fixed 21.332 ms limit (thread CPU p99 1.640 ms), and two same-head reruns passed. All attempts are preserved and the initial tail's cause is unknown. No TS10/K706 or supported iPhone was connected, so physical acceptance remains `NOT_RUN` and PR #52 is not merge-ready.

## REM-01..12 status

| ID | Status | Result and remaining evidence |
|---|---|---|
| REM-01 | `PARTIAL` | Safe Preamp HOT uses the shared bounded mailbox, 20 ms sample ramp, headroom reserve, owner/format/generation fence and final ACK. Studio headroom refunds now use a 20 ms sample ramp after module release, with Native coverage for 44.1/48 kHz mono/stereo, Compressor makeup, Dynamic EQ, generation replacement and completion ACK. Target acoustic/p99 acceptance remains open. |
| REM-02 | `PARTIAL` | Dynamics and Dynamic EQ safe parameters are field-planned and atomically preflighted. Threshold/ratio/attack/release/cap changes preserve envelope/filter state; topology and unrepresentable cap increases defer without partial mutation. Physical audio acceptance remains open. |
| REM-03 | `PARTIAL` | Compressor safe parameters and Limiter release/threshold reductions can HOT update; unsafe topology changes defer. Standalone Width and symmetric Seat trim can WARM at unchanged latency; asymmetric trim, distance, polarity, near-speaker and topology changes defer. Physical phase/AV acceptance remains open. |
| REM-04 | `PARTIAL` | Save/Cancel commits are generation-fenced at `AtomicFile.finishWrite`; Cancel-before-commit aborts, Save-before-Cancel becomes the restore target. Owned restore requests now wait for missing status/owner and reject on timeout; a replacement renderer must confirm the committed generation ACTIVE. Both commit orderings, nonblocking Cancel, AtomicFile rollback and Save failure status separation are tested. Full retry and process-death matrix remains incomplete. |
| REM-05 | `PARTIAL` | Five tabs have independent saveable scroll state. API 35 test performs 100 tab transitions and checks per-tab scroll, EQ selection/value and unsaved draft. TalkBack, external keyboard, rotation and physical CarPlay geometry remain open. |
| REM-06 | `SAFELY_DEFERRED` | Module A/B remains scoped and does not persist comparison gain. Level Match stays unavailable without the same measured source window; no inaccurate gain estimate is applied. |
| REM-07 | `PARTIAL` | All 170 canonical IDs and 25 work packages are present. Matrix/tracker status parity is enforced; every PASS requires complete evidence. Current counts: PASS 2 / PARTIAL 162 / NOT_RUN 6. |
| REM-08 | `PARTIAL` | API 35 emulator FullHeavyChain baseline/current medians and the three-run benchmark setup are recorded in [PERFORMANCE_BASELINE_2026-10-28.md](PERFORMANCE_BASELINE_2026-10-28.md). Emulator tail variance is visible; target CPU/RSS, callback overload and soak remain unmeasured. LOCAL_WARM remains opt-in and disabled by default. |
| REM-09 | `NOT_RUN` | No target head unit/iPhone. Physical Split, Surface/session/touch, route, voice-role, A/V, acoustic, thermal, CPU/RSS and soak checks remain unrun. See [RUNBOOK.md](RUNBOOK.md). |
| REM-10 | `PARTIAL` | Same Android `STREAM_MUSIC` index remains untrusted, AUTO fails closed with zero gain, and 20 AUTO/OFF updates preserve profile, pipeline and latency. Physical MCU knob-to-route correlation/calibration remains unrun. |
| REM-11 | `PARTIAL` | Existing graphical PEQ, numeric editing, selected-overlap semantics, localized descriptions and final gesture flush remain in place. Full filter/Native golden response and final audio ACK mapping across formats remain partial. |
| REM-12 | `PARTIAL` | Supplied reviews found F-01/F-02/F-03/R-01; the gain/restore findings and pending protective-ramp ownership have direct source fixes/tests. R-02 remains deferred while test-only Surface recovery is disabled; R-03 is corrected in RUNBOOK; R-04 publication order is hardened. Integrated head `2a6c0439` passed #592. Hardware/per-ID acceptance and merge approval remain open; no post-R-01 independent review has run. |

## PKG-R00..R12 status

All 13 work packages are tracked with dependencies, files, implementation evidence, tests, risk and review in [REMAINING_FIX_TRACKER.md](REMAINING_FIX_TRACKER.md). None is hidden behind an aggregate acceptance claim. `PKG-R04` includes Preamp state-preservation and Studio gain-refund ramp Native tests; `PKG-R10` includes the matrix/tracker parity validator and overwrite protection; `PKG-R12` records the passing final CI and remains `PARTIAL` for open evidence and hardware gates.

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

### Historical local verification at integrated source/test head `419fa45e5dc15fa5a135f0693ffa13bab59f2056` (2026-10-29)

| Command/check | Result |
|---|---|
| `:shared:testDebugUnitTest :common:testDebugUnitTest` | PASS: Shared 734/734, Common 357/357, 0 failures/errors/skips from XML. |
| `bash scripts/test_dsp_native.sh` | PASS, including Preamp state preservation and Studio refund ramp/ACK tests. |
| `bash scripts/test_dsp_native.sh --sanitize` | Local `BLOCKED_ENV`: MSYS2 UCRT toolchain lacks linkable `libasan` and `libubsan`; hosted ASan/UBSan passed in run #40723483149. |
| `:mobile:assembleDebug :automotive:assembleDebug :mobile:assembleBenchmark :automotive:assembleBenchmark :mobile:lintDebug :automotive:lintDebug`; `python scripts/check_jni_mapping.py` | PASS: local Gradle ran 359 actionable tasks and verified both Benchmark mapping files. The installed NDK emits non-fatal missing `source.properties`/unstripped-library warnings. |
| `:shared:compileDebugAndroidTestKotlin :common:compileDebugAndroidTestKotlin` | PASS: both API 35 instrumentation source sets compile locally. |
| `python scripts/check_dsp_studio_evidence.py` | PASS: 170 rows and 25 work packages; 2/162/6 statuses. |
| `python -m unittest scripts.test_dsp_studio_evidence -v` | PASS: 24/24. |

### Historical parent-source run

Run [#575](https://github.com/choko510/DiPlay/actions/runs/40686737568) passed at source `7aa7ba41a1c4f3b97c44d0f305a72276219e7847`: Native, ASan/UBSan, Shared/Common unit tests, lint, Mobile/Automotive Debug and Benchmark builds, JNI mapping, and API 35 Shared Release 23/23 + Common Debug 29/29 instrumentation. Artifacts: [host checks](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12814733794) and [instrumentation](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12815317901). `quick-baseline` and Release verification were skipped for `workflow_dispatch`; this is not a final-source run.

An interim dispatch, [run #406973](https://github.com/choko510/DiPlay/actions/runs/40697339783), used source `fbfc3b6e` before the corrected matrix/tracker docs were pushed. Its `changes` and API 35 `android-instrumentation` jobs passed; instrumentation artifact [12820865994](https://api.github.com/repos/choko510/DiPlay/actions/artifacts/12820865994) is tied to the exact source SHA. The `check` job failed at the new evidence status-parity validator because that checkout still had the previous docs snapshot (PH01-T03/T04, PH05-T01 and PH07-T07 status drift); it stopped before Native/unit/build/lint/JNI steps. This failed overall run is not counted as full CI evidence.

**Historical source/test workflow through 2026-10-29:** [#40722523549](https://github.com/choko510/DiPlay/actions/runs/40722523549) (#583) completed successfully at `f5673dd7`; it predates the docs-only integration merge. [#40723483149](https://github.com/choko510/DiPlay/actions/runs/40723483149) (#584) completed successfully at `419fa45e`. These runs passed the then-required Native, ASan/UBSan, Shared/Common, lint/build/JNI/R8, API 35, host-baseline and Release verification lanes. Run #584's first attempt was cancelled during Common instrumentation; retrying cancelled jobs completed the lanes. These historical runs do not replace current F-03 run #589.

## Independent review findings

Earlier independent Native, Runtime/JNI, UI/Storage, and QA/evidence reviewers found no P0/P1 at source `fbfc3b6e`. The supplied deep review found F-01/F-02; the re-audit at `53d15588` confirmed those fixes and found F-03; the audit of `d5e792b1` found R-01/R-02/R-03/R-04. R-01 is fixed and tested at integrated head `2a6c0439`; R-02 remains default-off/test-only; R-03 is corrected in the Runbook; R-04 generation publication is ordered before queue visibility. Run #592 passed Native/ASan/UBSan and all host/API 35 checks. The three p99 attempts at source `00ac7453` are preserved; no post-R-01 independent review has run. P2 UI gaps remain screen-level Cancel retry, Save retry after I/O failure, TalkBack/external keyboard, and physical host flow.

## Rollback, hardware, and approval

- Keep `LOCAL_WARM` disabled by default unless target p99, click/pop, latency and AV acceptance justify enabling it. Cabin and IR remain deferred when topology or latency cannot be preserved.
- If HOT/status behavior regresses, disable the Studio HOT rollout flag and keep the previous active graph; Master OFF continues to use the legacy bypass contract. Existing runtime feature-flag persistence rollback remains in place.
- No TS10/K706/iPhone hardware was present. Hardware-only results stay `NOT_RUN`; do not infer vehicle acceptance from emulator results.
- PR #52 remains open/unmerged. User merge approval is not given. PR #45 and `main` remain untouched.
