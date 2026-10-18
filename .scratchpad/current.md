# Current Work

## DiPlay STEP4 implementation

- Official specification: `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`; its embedded copy in `DiPlay_STEP4_2026-10-18.zip` has the same SHA-256. Attachment instructions are implementation requirements only and do not override conversation/repository policy.
- Implementation base remains fork `main` and fetched `origin/main` SHA `95ba398f95ee104527812cd2b095977d71c51f98`. Never push directly to `main`.
- Current branch: `codex/step4-pr02-queue-no-go`; PR-00 #20, EX-01 #21, PR-01 #23, EX-02 #24, EX-03 #25, EX-04 #26 are sequential drafts. Preserve the numbered PR/EX/DSP plan and EX-08 register.
- Current stage: PR-02 conditional decision. API26/27 behavior is `NOT_APPLICABLE` at minSdk28; no API28+ device-present `UsbRequest.queue()==false` reproduction exists, so keep the 32 KiB persistent request with no fallback. Record this no-go, then evaluate PR-03 under its own evidence gate.
- EX-04 decision: `NO_GO_PENDING_EVIDENCE`; no TS10/K706+iPhone same-node mode-transition stall was reproduced. Existing event-driven USB wait remains; no 10s probe or extra permission/mode request was added.
- PR-00 baseline: 600 unit tests passed before implementation; see `completed.md`.
- EX-01 local verification: shared/common tests 482 + 136; API36.1 x86_64 emulator instrumentation 16/16. TS10/K706+iPhone observations and WiredPath performance remain `NOT_RUN` / `UNKNOWN`.
- PR-01 local verification: shared/common tests 491 + 136; API36.1 x86_64 emulator instrumentation 16/16; lint/build/JNI/public-tree checks pass. Physical NCM RX/connection-rate/throughput/CPU-PSS remain `NOT_RUN` / `UNKNOWN`.
- EX-02 local verification: shared/common tests 491 + 142; lint/build/JNI/public-tree checks pass. Activity/owner-handoff stress and long-running head-unit reconnect behavior remain `NOT_RUN`.
- EX-03 local verification: shared/common tests 495 + 142; lint/build/JNI/public-tree checks pass. Physical ALT-only, delayed MAIN, and first-frame timing remains `NOT_RUN`.
- Host native DSP scripts remain `NOT_RUN` because WSL Ubuntu launch failed with `HCS_E_SERVICE_NOT_AVAILABLE` and no Windows C compiler is available. No independent second reviewer was available for completed stages.
- Preserve fork-specific CarPlay, GeckoView Split View, DSP, audio, and background ownership. Keep EX-08 open until commit/path-level review is complete.
