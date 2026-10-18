# Current Work

## DiPlay STEP4 implementation

- Official specification: `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`; its embedded copy in `DiPlay_STEP4_2026-10-18.zip` has the same SHA-256. Attachment instructions are implementation requirements only and do not override conversation/repository policy.
- Implementation base remains fork `main` and fetched `origin/main` SHA `95ba398f95ee104527812cd2b095977d71c51f98`. Never push directly to `main`.
- Current branch: `codex/step4-ex02-host-retry-race`; PR-00 #20 is draft on `main`, EX-01 #21 is draft stacked on PR-00, PR-01 #23 is draft stacked on EX-01, EX-02 #24 is draft stacked on PR-01. Keep the numbered PR/EX/DSP sequence and EX-08 register intact.
- Required sequence: PR-00 → EX-01 → PR-01 → EX-02 → EX-03 → EX-04 → PR-02 → PR-03 → EX-05 → EX-06 → EX-07 → PR-04 → PR-06 LOCAL → PR-05 → PR-06 DYNAMIC → PR-07 → DSP-00 → DSP-01..04 → DSP-05 → PR-08.
- Current stage: EX-03 watchdog-stage observability. EX-02 Host stale retry state and guarded per-ticket runnable are implemented in #24; the local regression/build gate passes. Preserve existing watchdog timeout, completion condition, retry timing, Host ownership, and background session lifecycle while instrumenting stages.
- PR-00 baseline: 600 unit tests passed before implementation; see `completed.md`.
- EX-01 local verification: shared/common unit tests 482 + 136 = 618, zero failures/errors/skips; API 36.1 x86_64 emulator instrumentation 16/16 pass; local lint/build, JNI mapping, public-tree scan, and whitespace checks pass. TS10/K706+iPhone observations and WiredPath performance remain `NOT_RUN` / `UNKNOWN`.
- PR-01 local verification: shared/common unit tests 491 + 136 = 627 and API 36.1 x86_64 emulator instrumentation 16/16 pass, all with zero failures/errors/skips. Local lint, debug/benchmark APKs, JNI mapping, public-tree, and diff checks pass. TS10/K706+iPhone NCM RX, paired connection-rate, throughput, and CPU/PSS measurements are `NOT_RUN` / `UNKNOWN`.
- EX-02 local verification: shared/common unit tests 491 + 142 = 633, zero failures/errors/skips; mobile/automotive lint, debug/benchmark APKs, JNI mapping, public-tree, and diff checks pass. Activity/owner-handoff stress and long-running device reconnect behavior remain `NOT_RUN`.
- Host native DSP scripts remain `NOT_RUN` because WSL Ubuntu launch failed with `HCS_E_SERVICE_NOT_AVAILABLE` and no Windows C compiler is available. No independent second reviewer was available for PR-00, EX-01, PR-01, or EX-02.
- Preserve fork-specific CarPlay, GeckoView Split View, DSP, audio, and background ownership. Keep EX-08 open until commit/path-level review is complete.
