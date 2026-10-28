# Current Work — DiPlay DSP Studio PR #52 (2026-10-28)

> Active goal: commit the final run/evidence record, verify the full hosted workflow at the updated PR head, update the PR description, and leave PR #52 open/unmerged. Do not modify PR #45 or `main`.

## Scope and invariants

- Keep all 60 PH IDs and 110 T IDs in the canonical matrix and tracker. Current evidence is PASS 2 / PARTIAL 162 / NOT_RUN 6; the six NOT_RUN rows require a physical CarPlay/head-unit session or device soak.
- Preserve MEDIA-only routing, new-track DSP-OFF PCM16 behavior, schema-v7 compatibility, safe headroom, and truthful HOT/WARM/DEFER status.
- Preamp/Dynamics/Dynamic EQ updates use owner, generation, format and Native ACK fences. LOCAL_WARM stays opt-in/off by default. Cabin Correction and IR topology changes stay deferred where continuity cannot be proven.
- No TS10/K706 or supported iPhone is attached. Hardware-specific acceptance remains NOT_RUN.

## Current integration state

- Worktree: `C:\Users\eita2\.codex\worktrees\ab84\DiPlay`; branch `codex/dsp-studio-final`.
- Current product/source head: `fbfc3b6e02c8590d80352d88b5c3c7e9edfe0145`; final documentation head: `9e4dd30281142fc5b2f1eb1162e7382bc2ab3b25`.
- Direct follow-up commits: `9a8fc30b` atomic renderer status owner/state; `e10e7887` Preamp HOT state-preservation Native test; `03e53033` acceptance status parity; `fbfc3b6e` fail-closed evidence-ledger regeneration.
- Local Native regular tests pass. Shared/Common XML totals are 704/704 and 319/319 with zero failures/errors/skips. Mobile/Automotive Debug and Benchmark builds, both Debug lint tasks and JNI mapping pass locally (359 actionable Gradle tasks). The evidence checker passes 170 IDs and 25 packages; its 24 fixtures pass.
- Local MSYS2 ASan/UBSan cannot link because `libasan`/`libubsan` are absent. Hosted run #575 passed full source gates at parent source `7aa7ba41`; API 35 passed Shared Release 23/23 and Common Debug 29/29.
- Interim run #406973 at `fbfc3b6e` passed the classifier and API 35 instrumentation but failed the evidence check because the older tracked docs had four status mismatches. The detailed matrix and tracker now agree; run #406973 is not counted as full CI.
- Four read-only re-reviews at `fbfc3b6e` found no P0/P1. Remaining screen accessibility/retry and physical hardware clauses are explicitly PARTIAL/NOT_RUN.
- PR #52 is open and unmerged, base `codex/step4-pr-00-test-baseline`; PR #45 and `main` are untouched.
- Workflow-dispatch run #40700507509 completed successfully at documentation head `9e4dd302`, with product source tree `fbfc3b6e`. `changes`, evidence check, Native, ASan/UBSan, Shared/Common, build/lint/JNI, API 35 instrumentation, and host-baseline passed; quick-baseline and Release verification were skipped.
- The working tree now registers run #407005 in the two PASS UI evidence rows and checker fixture. Local checker and 24/24 fixtures pass; one final hosted confirmation for this evidence-fixture/report update is pending.

## Next

1. Commit/push the final run registry, mapped PASS rows, report update, and test fixture.
2. Dispatch and verify the final full workflow at that exact PR head, including evidence checker, Native/ASan, Unit/Lint/Build/JNI, and API 35 jobs.
3. Update the PR #52 body with the final report/run.
4. Keep the six physical/CarPlay host-session gates NOT_RUN and leave PR #52 open/unmerged.
