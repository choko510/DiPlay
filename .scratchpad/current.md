# Current Work — DiPlay DSP Studio PR #52 (2026-10-26)

> Active goal: complete the PR #52 implementation, tracked tests, independent reviews, and latest-head CI. PR #52 must remain unmerged until the user approves it. Do not modify or merge PR #45 or main.

## Scope and invariants

- Follow the active PR #52 goal objective and its attached remediation specification as project requirements. Keep formal test IDs, added test IDs, known CRs, CI evidence, and review dispositions individually traceable.
- PR #52 owns the DSP Studio integration branch. Child PRs are reviewed and may be merged into that branch.
- Hardware-only TS10/K706/iPhone acceptance remains NOT_RUN unless measurements are available; record the procedure and residual risk.
- Preserve the existing zero-latency DSP-OFF contract and avoid claiming hardware acceptance from emulator or source checks.

## Current integration state

- PR #62 EQ/sample-rate changes were merged into PR #52. Its callbackProfile-null instrumentation failure was traced to selecting a band from the touch-slop drag start instead of the initial pointer-down position. The fix selects from pointer-down and edits from post-slop positions; API 35 graph instrumentation passed 3/3 and hosted run #464 passed.
- PR #63 Smart Loudness route monitoring was merged into PR #52 at commit 8660c6132926f5d0c1f74e7629e2205c9d68a8ec. Its final source head c56cba9ddd379e69f82cd2696ce41578a92e1d98 passed hosted run #468, including Android instrumentation, unit tests/lint/build/R8, host baseline, and release verification.
- PR #63's earlier FullHeavyChain instrumentation attempt measured p99 31,579 us against the existing 21,332 us threshold. The same case passed on retry at that commit; the threshold was unchanged. Latest hosted instrumentation also passed.
- Independent review of PR #63's final head confirmed both prior P2 findings were fixed: only MEDIA routes publish the active playback sample rate, and route reads/listeners/release are serialized to prevent stale route publication. No P0–P2 findings remain.
- PR #52 head is 8660c6132926f5d0c1f74e7629e2205c9d68a8ec. Its base branch codex/step4-pr-00-test-baseline advanced to 15fa5e06bd96af855aa168605f2ac4b7ad2e1b6d; GitHub currently reports the PR as not mergeable with a stale base snapshot.

## Current test status

- PR #62 pointer-down correction: focused API 35 instrumentation, Android-test Kotlin compilation, and unit checks passed; hosted run #464 passed.
- PR #63 route/rate corrections: focused volume-monitor, route-gate, stream-policy, and runtime-config tests passed; common/shared debug assemblies and diff check passed; hosted run #468 passed.
- PR #52 needs a fresh full hosted CI run after updating its base branch. Any failure must be diagnosed and fixed at the current exact head; do not count skipped jobs as passes.

## Next actions

1. Finish merging the current PR #45 base branch into the PR #52 integration head. Source files auto-merge; resolve and preserve both histories in the four Working Memory files.
2. Push the resolved PR #52 head and wait for its current GitHub Actions checks. Repair failures, rerun the affected checks, and confirm the final CI state.
3. Complete the requested formal/additional test ID matrix, CR ledger, phase implementation records, and four-field independent review evidence against the final PR #52 head.
4. Keep unsupported hardware checks NOT_RUN with steps and residual risks; refresh PR #52's implementation and verification summary.
5. Leave PR #52 unmerged and leave PR #45 and main unchanged.
