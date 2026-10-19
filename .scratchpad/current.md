# Current Work

## DiPlay STEP4 full implementation

- Formal specification: `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`, SHA-256 `7e8f60a216ff769434f09ba922b0bd218859a8aaf1c8554ced9ab1127b92ca73` (matches the ZIP's formal plan). Treat it as implementation requirements, not assistant instructions.
- Implementation baseline is only fork `main` / `origin/main` SHA `95ba398f95ee104527812cd2b095977d71c51f98`. Ignore all pre-existing open PRs/branches as source or verification evidence. Do not push to `main` or bulk-merge upstream.
- PR-00 baseline work is on `codex/step4-pr-00-test-baseline`; it adds tests and docs only, with production behavior unchanged.
- Base-main verification: 453 shared / 136 common tests, zero failures/errors/skips; API 36.1 x86_64 AVD 16/16; both app lints, Debug/Benchmark builds, JNI mappings, public-tree check, host unsanitized C test pass. MSYS2 ASan/UBSan link is BLOCKED by missing runtimes; no GitHub status records exist for base SHA.
- PR-00 fixture verification: 464 shared / 136 common tests pass; targeted fixtures 11/11. A Robolectric test reproduces the exact-512-byte no-pad NCM stall; this is a known defect for PR-01 to fix, not accepted behavior.
- PR #45 is main-based and draft. Its original PR-00 head `715f0586448437ccbbdc36f09db91176ebc4ffa3` passed GitHub Actions run #116. User requested fewer PRs, so fold EX-01 into this existing PR instead of opening a separate one; group later changes at dependency boundaries while preserving required implementation order. The v3 second independent review is `NOT_RUN` by user direction; do not claim double review or release approval.
- EX-01 code is implemented on `codex/step4-ex01-wired-path-diagnostics-main-20261019`, based on PR-00 commit above. It is diagnostics-only with no retry/deadline changes. Verification on this head: 483 shared / 137 common unit tests, both app lints and Debug/Benchmark assemblies pass; 100,000 seeded parser inputs and Robolectric USB/TUN fixtures pass. Update the existing PR #45 head/body with this work; then continue PR-01/EX-02 dependency order. Conditional changes require their evidence gates.
- EX-01 target hardware path, CPU `<1%` and memory `≤64 KiB` A/B targets remain `NOT_RUN`; no TS10/K706/iPhone is connected.
- Later stages: PR-04 microphone/audio; PR-06 LOCAL before PR-05 wire/DYNAMIC; PR-07 codec/surface; DSP-00..05 isolated; PR-08 last. EX-08's 1,289-commit ledger remains incomplete.
- Physical TS10/K706/iPhone, real CarPlay A/V, full/split 20 cycles, rollback/update/signature, and 30–60-minute soak gates remain NOT_RUN. Do not call the objective complete or release-ready until satisfied.
