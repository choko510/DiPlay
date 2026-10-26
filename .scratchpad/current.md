# Current Work — DiPlay DSP Studio PR #52 (2026-10-27)

> Active goal: finish PR #52 implementation, evidence, reviews, and latest-head CI. Keep PR #52 open and unmerged; do not modify or merge PR #45 or `main`.

## Scope and invariants

- Keep the 60 PH-ID and 110 T-ID acceptance rows traceable; incomplete evidence remains `PARTIAL` or `NOT_RUN`.
- Preserve the zero-latency DSP-OFF contract. TS10/K706/iPhone acceptance remains `NOT_RUN` without measurements.
- Work only in `/Users/choko/.codex/worktrees/dsp52-volume-monitor/DiPlay`; the root checkout has unrelated dirty changes and is untouched.

## Current integration state

- PR #52 base `codex/step4-pr-00-test-baseline` is at `15fa5e06bd96af855aa168605f2ac4b7ad2e1b6d`. Latest PR branch and tested code source head is `6439a5b94e21da3bb5e2b921a2ac035c8d969079`.
- `f9c9f56e` adds module-scoped A/B preview while keeping the editor draft intact. `ce3c87d7` restores draft audio preview after deleting the selected preset during active A/B; independent follow-up review closed the P2 finding.
- The screen test initially exposed a missing assertion import and an A/B preview path that overwrote draft state. Both were corrected. The follow-up screen test now also guards against leaving the renderer on an obsolete comparison snapshot after deletion.

## Current test status

- Full local Gradle gates at `f9c9f56e`: Shared 668 + Common 271 = 939 tests, zero failures/errors/skips; Native, sanitizers, lint, debug/benchmark builds, JNI and repository checks passed.
- At `6439a5b9`, Common unit tests passed 272/272, including `runtimeRecreationRestoresPersistedWorkspaceAndDiscardsPreviewDraft`. This test recreates the runtime in one process; it is not Android process-kill evidence.
- Hosted manual run [#40374442067](https://github.com/choko510/DiPlay/actions/runs/40374442067) passed at `6439a5b9`: `changes`, `check` (Gradle gates, Native DSP, ASan/UBSan, and R8), `android-instrumentation` (JNI DSP smoke test), and `host-baseline` succeeded. `quick-baseline` and PR-only `verification-status` were skipped because this was `workflow_dispatch`; they are not counted as passes.
- Earlier hosted run [#40370259506](https://github.com/choko510/DiPlay/actions/runs/40370259506) passed at `ce3c87d7`. Its `quick-baseline` and PR-only `verification-status` were skipped for `workflow_dispatch` and are not counted as passes.
- Local API 35 emulator is test evidence only; it does not establish CarPlay host or target-device acceptance.

## Next actions

1. Commit and push the refreshed test evidence, then update PR #52's body with the latest tested source head and run #487.
2. Continue remaining per-ID test evidence and hardware runbook gates; keep them open in the final report.
3. Rerun the relevant checks if any production source changes; do not count skips as passes.
4. Keep PR #52 unmerged and PR #45/`main` untouched.
