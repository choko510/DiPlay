# Current Work — DiPlay DSP Studio PR #52 (2026-10-27)

> Active goal: complete PR #52 implementation, evidence, reviews, and latest-head CI. Keep PR #52 open and unmerged; do not modify PR #45 or `main`.

## Scope and invariants

- Keep all 60 PH-ID and 110 T-ID acceptance rows traceable; incomplete evidence remains `PARTIAL` or `NOT_RUN`.
- Preserve zero-latency DSP-OFF behavior and MEDIA-only routing. TS10/K706/iPhone acceptance remains `NOT_RUN` without measurements.
- Work in `/Users/choko/.codex/worktrees/dsp52-volume-monitor/DiPlay`; the root checkout has separate dirty changes and remains untouched.
- Follow the supplied Phase 01–07 specification: PEQ and Static Bass may use LOCAL_WARM; Cabin Correction and IR stay deferred unless separately evidenced.

## Current integration state

- PR #45 base `codex/step4-pr-00-test-baseline` at `a99066f6` and reviewed DSP remediation are integrated into PR #52. Remote PR head is `1e4a8962`; source code tested locally is `fe083f4f` (the `1e4a8962` commit contains documentation evidence only).
- At `fe083f4f`, Shared/Common unit tests passed 1002/1002; Native and ASan/UBSan passed; API 35 passed Shared Debug 20/20, Common Debug 17/17, and optimized Shared Release 20/20. Mobile/Automotive Debug/Benchmark builds, lint, JNI mapping, public-tree and instrumentation-evidence fixtures passed.
- Hosted run [#506](https://github.com/choko510/DiPlay/actions/runs/40497504756) passed at `1e4a8962`: `changes`, `check`, `android-instrumentation`, `host-baseline`, and `Release verification` succeeded. `quick-baseline` and two Draft-only steps were skipped and are not counted as passes.
- The Static Bass JNI expectation, warm-history block-size fixture, and A/B runtime-snapshot race were repaired. Six follow-up P2 findings are recorded as closed in source; no open source-correctable P0/P1 is identified.
- Runtime fixes include symlink-safe persistence/future-schema backup protection, feature-flag workspace rollback, monotonic process-wide renderer tokens, asynchronous retry scheduling, transient-safe Smart Loudness headroom, and opt-in PEQ/Static Bass LOCAL_WARM. Cabin Correction and IR remain deferred per specification.
- Updated implementation, test, review, architecture, CI-failure, legacy-field, and field-update docs. Per-ID evidence remains partial; target-device gates are `NOT_RUN`.
- REM-10 T-PRE/T-UI evidence mapping added 13 directly supported rows at the source SHAs for runs #40625689851 and #40631709640; the ledger remains 0 PASS / 153 PARTIAL / 17 NOT_RUN.

## Next

1. REM-07 PH evidence mapping was expanded on `codex/pr52-rem-10-evidence-ph`: 29 previously unmapped PH rows now point to source-backed methods/assertions and successful run #40625689851 at source SHA `bd381429f6c8db3707c28928a705de5890a253d6`. Ledger counts are 0 PASS / 153 PARTIAL / 17 NOT_RUN; this historical run is not evidence for a later final HEAD.
2. Commit and push the final run #506 evidence/review updates; verify the workflow triggered for that documentation head and keep the PR body linked to its result.
3. Keep PR #52 open and unmerged. Do not modify PR #45 or `main`.
4. Hardware, acoustic, performance, A/V, route calibration, and per-ID evidence remain open where measurements or one-to-one assertions are missing.
