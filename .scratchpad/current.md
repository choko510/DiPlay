# Current Work

## DiPlay STEP4 v3.0

- The sole implementation base is `choko510/DiPlay:main` at `95ba398f95ee104527812cd2b095977d71c51f98`. Existing open PRs and their code/tests/reviews are excluded. No direct push to `main`.
- Active stage: `PR-00` test baseline on branch `codex/step4-main-only-pr00`. PR-00 production behavior changes must remain zero.
- Added test-only NCM/USB endpoint/requestWait shadows, synthetic packet/TUN/PCM/video/time fixtures, deterministic bridge tests, the full v3 test-ID matrix, baseline/ownership/decision/flag records, and run-scoped emulator benchmark log capture.
- Local verification: shared/common `464 + 136 = 600` tests pass with zero failures/errors/skips; connected tests `16/16` pass on API 36.1 emulator; native DSP C passes unsanitized; mobile/automotive Debug lint, Debug/Benchmark builds, JNI mapping, and public-tree check pass. ASan/UBSan local link is `BLOCKED_ENV` (MSYS2 lacks `libasan`/`libubsan`); main CI run #101 on the exact base SHA passed the hosted sanitizer step.
- Remaining before closing PR-00: commit/push the branch, open its PR, verify the new PR CI, and record code review. Independent review A/B and all physical TS10/K706/iPhone gates remain `NOT_RUN`; do not advance to EX-01 before the PR-00 review gate is resolved or explicitly recorded as unrun.
