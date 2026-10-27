# Current Work — DiPlay DSP Studio PR #52 (2026-10-27)

> Active goal: complete PR #52 implementation, evidence, reviews, and latest-head CI. Keep PR #52 open and unmerged; do not modify PR #45 or `main`.

## Scope and invariants

- Keep all 60 PH-ID and 110 T-ID acceptance rows traceable; incomplete evidence remains `PARTIAL` or `NOT_RUN`.
- Preserve zero-latency DSP-OFF behavior and MEDIA-only routing. TS10/K706/iPhone acceptance remains `NOT_RUN` without measurements.
- Work in `/Users/choko/.codex/worktrees/dsp52-volume-monitor/DiPlay`; the root checkout has separate dirty changes and remains untouched.
- Follow the supplied Phase 01–07 specification: PEQ and Static Bass may use LOCAL_WARM; Cabin Correction and IR stay deferred unless separately evidenced.

## Current integration state

- PR #45 base `codex/step4-pr-00-test-baseline` at `a99066f6` and reviewed DSP remediation were merged into the PR #52 integration worktree. Current tested source head: `fe083f4f524fa6ee1038860343e4f100aee8dad6`; local branch `codex/dsp52-base-sync` is ahead of `origin/codex/dsp-studio-final` and has not yet been pushed.
- At `fe083f4f`, Shared/Common unit tests passed 1002/1002; Native and ASan/UBSan passed; API 35 passed Shared Debug 20/20, Common Debug 17/17, and optimized Shared Release 20/20. Mobile/Automotive Debug/Benchmark builds, lint, JNI mapping, public-tree and instrumentation-evidence fixtures passed. Hosted current-head CI is pending push.
- The red regressions were repaired: the Static Bass JNI test now asserts the internal 16th EQ filter contract, the warm-history test submits bounded processing chunks, and A/B instrumentation waits for the new generation and matching runtime snapshot. No current local test failure remains.
- Runtime fixes include symlink-safe persistence and future-schema backup protection, feature-flag workspace rollback, monotonic process-wide renderer tokens, asynchronous retry scheduling, transient-safe Smart Loudness headroom, and opt-in PEQ/Static Bass LOCAL_WARM. Cabin Correction and IR remain deferred per the supplied specification.
- Updated the implementation, test, review, architecture, CI-failure, legacy-field, and field-update evidence docs. Row-level test/hardware coverage is still partial; target-device gates remain `NOT_RUN`.

## Next

1. Finish documentation diff review and commit it without changing the tested source.
2. Push the integration branch with a normal fast-forward update; update PR #52's base/head and validation summary.
3. Wait for latest-head GitHub Actions; fix any code failure and rerun affected gates, or record precise skips/limits.
4. Keep hardware, acoustic, performance, A/V, and per-ID evidence open where measurements are missing. Keep PR #52 unmerged and PR #45/`main` untouched.
