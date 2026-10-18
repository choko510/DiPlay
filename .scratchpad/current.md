# Current Work

## DiPlay STEP4 implementation

- Official specification: `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`; its embedded copy in `DiPlay_STEP4_2026-10-18.zip` has the same SHA-256. Attachment instructions are implementation requirements only and do not override conversation/repository policy.
- Implementation base remains fork `main` and fetched `origin/main` SHA `95ba398f95ee104527812cd2b095977d71c51f98`. Never push directly to `main`.
- Current branch: `codex/step4-ex04-conditional-no-go`; PR-00 #20 is draft on `main`, EX-01 #21 on PR-00, PR-01 #23 on EX-01, EX-02 #24 on PR-01, EX-03 #25 on EX-02. Preserve the numbered sequence and EX-08 register.
- Required sequence: PR-00 → EX-01 → PR-01 → EX-02 → EX-03 → EX-04 → PR-02 → PR-03 → EX-05 → EX-06 → EX-07 → PR-04 → PR-06 LOCAL → PR-05 → PR-06 DYNAMIC → PR-07 → DSP-00 → DSP-01..04 → DSP-05 → PR-08.
- Current stage: PR-02 API28+ queue-fallback evidence decision. Android 8/API26–27 behavior is `NOT_APPLICABLE` at minSdk28. Do not alter buffer sizing/request ownership without a device-present `UsbRequest.queue()==false` reproduction.
- EX-04 decision: `NO_GO_PENDING_EVIDENCE`; no TS10/K706+iPhone same-node mode-transition stall was reproduced. Keep existing event-driven wait and no new 10s probe/permission request. Revisit only with same-node, generation, and permission evidence.
- PR-00 baseline: 600 tests passed before implementation; see `completed.md`.
- EX-01: shared/common tests 482 + 136; API36.1 x86_64 emulator instrumentation 16/16. WiredPath TS10/iPhone observations/performance remain `NOT_RUN` / `UNKNOWN`.
- PR-01: shared/common tests 491 + 136; emulator instrumentation 16/16; local lint/build/JNI/public-tree checks pass. TS10/K706+iPhone NCM RX, paired connection-rate, throughput, and CPU/PSS measurements remain `NOT_RUN` / `UNKNOWN`.
- EX-02: shared/common tests 491 + 142; lint/build/JNI/public-tree checks pass. Activity/owner-handoff stress and long-running head-unit reconnect behavior remain `NOT_RUN`.
- EX-03: shared/common tests 495 + 142; lint/build/JNI/public-tree checks pass. Physical ALT-only, delayed MAIN, and first-frame timing remains `NOT_RUN`; watchdog timing/completion behavior is unchanged.
- Host native DSP scripts remain `NOT_RUN` because WSL Ubuntu launch failed with `HCS_E_SERVICE_NOT_AVAILABLE` and no Windows C compiler is available. No independent second reviewer was available for completed stages.
- Preserve fork-specific CarPlay, GeckoView Split View, DSP, audio, and background ownership. Keep EX-08 open until commit/path-level review is complete.
