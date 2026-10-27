# Current Work — DiPlay DSP Studio PR #52 (2026-10-27)

> Active goal: complete the PR #52 implementation, evidence, reviews, and latest-head CI. Keep PR #52 open and unmerged; do not modify or merge PR #45 or `main`.

## Scope and invariants

- Keep all 60 PH-ID and 110 T-ID acceptance rows traceable; incomplete evidence remains `PARTIAL` or `NOT_RUN`.
- Preserve zero-latency DSP-OFF behavior and MEDIA-only routing. TS10/K706/iPhone acceptance remains `NOT_RUN` without measurements.
- Work in `/Users/choko/.codex/worktrees/dsp52-volume-monitor/DiPlay`; the root checkout has separate dirty changes and remains untouched.
- The PR #52 base branch has advanced to `28a89d687fe804fa7bd6731a24b2c4f4529a3d7f`. Its regular merge into this integration branch is in progress; only Working Memory files conflicted, while code files auto-merged.

## Current integration state

- PR #65 (PEQ stage-local warm-up) was independently reviewed, passed its full hosted run [#40474134044](https://github.com/choko510/DiPlay/actions/runs/40474134044), and merged into `codex/dsp-studio-final` at `e3d7f076ba3cae6bebe626a331792a090f8ce913`. PR #52 remains open and unmerged.
- Local checks at `e3d7f076`: Native and ASan/UBSan PASS; Shared/Common unit tests 947/947 PASS; three-ABI Native link, Mobile/Automotive Debug and Benchmark builds PASS; isolated API 35 DSP benchmark instrumentation 5/5 PASS.
- A separate API 35 run from another worktree reported 3 p99 misses while an R8 benchmark build was running concurrently. It is recorded as a contaminated local sample, not a proven product regression; the p99 threshold was not changed and the isolated integration-head rerun passed.
- PEQ local warm is opt-in and limited to PEQ. Bass Shelf, Cabin Correction, and IR remain deferred. Target-device evidence is still required.

## Next

1. Resolve the four Working Memory conflicts while preserving both PR #45 and PR #52 entries.
2. Finish the merge of base `28a89d68`, run full tests/builds/JNI/API 35 instrumentation on the resulting integration head, and record failures, skips, and limitations precisely.
3. Update traceability, review, test, and final-report documents; commit and push the PR #52 branch and verify current-head Actions.
4. Keep per-ID evidence and hardware acceptance open where measurements are missing. Keep PR #52 unmerged and PR #45/`main` untouched.
