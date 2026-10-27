# Current Work — DiPlay DSP Studio PR #52 (2026-10-27)

> Active goal: complete PR #52 implementation, evidence, reviews, and latest-head CI. Keep PR #52 open and unmerged; do not modify PR #45 or `main`.

## Scope and invariants

- Keep all 60 PH-ID and 110 T-ID acceptance rows traceable; incomplete evidence remains `PARTIAL` or `NOT_RUN`.
- Preserve zero-latency DSP-OFF behavior and MEDIA-only routing. TS10/K706/iPhone acceptance remains `NOT_RUN` without measurements.
- Work in `/Users/choko/.codex/worktrees/dsp52-volume-monitor/DiPlay`; the root checkout has separate dirty changes and remains untouched.
- Follow the supplied Phase 01–07 specification: PEQ and Static Bass may use LOCAL_WARM; Cabin Correction and IR stay deferred unless separately evidenced.

## Current integration state

- PR #45 base `codex/step4-pr-00-test-baseline` advanced to `a99066f6`. Its ordinary merge and the reviewed DSP remediation commits are being integrated into this PR #52 worktree; PR #45 and `main` remain untouched.
- Incoming source fixes cover symlink-safe DSP persistence and future-schema backup protection, workspace rollback after feature-flag persistence failure, renderer-owner fencing, a separate internal Bass Shelf filter slot with bounded local warm-up, and lock-free retry scheduling from the render callback.
- Before integration, the incoming branch passed Native ASan/UBSan, Shared/Common unit tests 1003/1003, and API 35 instrumentation 37/37. These results are not exact-head evidence for the combined branch; rerun affected checks after integration.
- Smart Loudness headroom now accounts for the high-pass residual's transient bound. Bass local warm-up remains opt-in and requires matching headroom, media role, format, owner, and sufficient history.

## Next

1. Finish the merge and review source/test conflicts; preserve monotonic global renderer-owner tokens and the A/B generation-aware wait.
2. Run tests, Native sanitizers, Android builds/lint, and connected instrumentation against the exact integrated head; diagnose any failures.
3. Refresh requirement/test/review evidence, update the PR #52 description, push the integration branch, and verify the current-head CI result.
4. Keep hardware, acoustic, performance, A/V, and per-ID evidence open where measurements are missing. Keep PR #52 unmerged.
