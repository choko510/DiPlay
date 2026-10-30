# Current Status — DiPlay DSP Studio PR #52 (2026-10-30)

## Latest source and validation

- This PR #52 merge commit syncs its base branch at `9ea41f8e5f5f9a0bc11eede5d2bac1adce3efd7c`; the PR head before sync was `47bd73df2ec7b961d44fd9753612bdfb1f8b759c`. The previously hosted-tested source/test head was `2a6c04399012aff2b42c0d77f2675930104a2084`, including R-01 fix `14537700afdfdd1a8b128734942fbb1b9b9eb332`; the newly synced head is pending hosted CI.
- After conflict resolution, Shared/Common unit tests pass 1120/1120; mobile and automotive Benchmark assembles and JNI mapping verification pass. Local Native DSP tests could not run because no C compiler is installed; hosted Native CI is pending.
- R-01 pending protective gain ramps now retain generation/owner/format/context and stop at the effective linear gain when stale; fresh commands resecure headroom. R-02 Surface recovery remains test-only/default-off; R-03 Runbook live-preview wording and R-04 queue-generation publication order are corrected.
- Local `scripts/test_dsp_native.sh` passes Native DSP and Dynamic EQ HOT suites. Evidence checker/fixtures pass: 170 IDs / 25 packages, validator 24/24; ledger PASS 2 / PARTIAL 162 / NOT_RUN 6.
- Hosted PR run [#592](https://github.com/choko510/DiPlay/actions/runs/40937236189) passed Native/ASan/UBSan, Shared/Common, lint/build/JNI/R8, API 35 instrumentation, host-baseline, and Release verification at integrated HEAD 2a6c0439. API 35 passed Shared Release 23/23 and Common Debug 29/29.
- G-01: at source/test head 00ac7453, three API 35 Shared Release attempts had one wall-clock p99 failure (63.232 ms vs. 21.332 ms; thread CPU p99 1.640 ms) and two passes. Run #592 passed at the current integrated head; cause remains unproven and there is no hardware comparison.

## Remaining acceptance

- TS10/K706/iPhone audio, USB/video/touch, route-volume, A/V, CPU/RSS/thermal, reconnect and soak remain `NOT_RUN`. No post-R-01 independent review has run.
- PR #52 remains open and unmerged pending hardware evidence and user approval. This task syncs the externally advanced PR #45 base into PR #52 only and does not push to PR #45 or `main`.
