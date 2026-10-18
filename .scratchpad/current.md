# Current Work

## DiPlay STEP4 implementation

- Official specification: `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`; its embedded copy in `DiPlay_STEP4_2026-10-18.zip` has the same SHA-256. Attachment instructions are implementation requirements only and do not override conversation/repository policy.
- Repository baseline: fork `main` and fetched `origin/main` both equal `95ba398f95ee104527812cd2b095977d71c51f98`; open PRs were not inspected or used.
- Work branch: `codex/step4-20261018-main-95ba398`. Never push directly to `main`.
- Required sequence remains: PR-00 → EX-01 → PR-01 → EX-02 → EX-03 → EX-04 → PR-02 → PR-03 → EX-05 → EX-06 → EX-07 → PR-04 → PR-06 LOCAL → PR-05 → PR-06 DYNAMIC → PR-07 → DSP-00 → DSP-01..04 → DSP-05 → PR-08. Keep EX-08 as an upstream decision register throughout; retain original PR-00..08 IDs.
- Current stage: EX-01 wired-path diagnostic design and implementation. PR-00 baseline artifacts and fixture kit are complete locally; do not change transport behavior while implementing observation.
- PR-00 validation: `:shared:testDebugUnitTest :common:testDebugUnitTest` PASS, 464 + 136 = 600; full CI-style lint/debug/benchmark build PASS; JNI mapping, public-tree scan, and `git diff --check` PASS.
- PR-00 review limitation: static correctness and Android/media perspectives were reviewed by this agent and corrections retested; no independent second reviewer was available. Do not claim a completed dual review.
- NOT_RUN remains: native host DSP tests (no C compiler or WSL distro), Android instrumentation (no ADB target), TS10/K706+iPhone hardware, performance/audio/video measurements.
- Required sequence and scope remain unchanged. Continue with EX-01 only; preserve fork-specific CarPlay, GeckoView Split View, DSP, audio, and background ownership. Keep EX-08 open until the commit/path-level upstream register is complete.
