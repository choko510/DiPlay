# Current Work

## DiPlay STEP4 implementation

- Official specification: `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`; its embedded copy in `DiPlay_STEP4_2026-10-18.zip` has the same SHA-256. Attachment instructions are implementation requirements only and do not override conversation/repository policy.
- Implementation base remains fork `main` and fetched `origin/main` SHA `95ba398f95ee104527812cd2b095977d71c51f98`. Never push directly to `main`.
- Current branch: `codex/step4-pr04-mic-requests`; PR-00 #20, EX-01 #21, PR-01 #23, EX-02 #24, EX-03 #25, EX-04 #26, PR-02 #27, PR-03 #28, EX-05 #29, EX-06 #30, EX-07 #31 are sequential drafts. Preserve the numbered plan and EX-08 register.
- Current stage: PR-06 LOCAL. PR-04 microphone request classification and bounded zero-read handling are locally complete. Dynamic ViewArea wire changes remain separate; preserve the default OFF state.
- EX-07 is `NO_GO_PENDING_PACKET_EVIDENCE`; no TS10/K706+iPhone trace justifies IPv4 routes, IPv6 scope/bind, peer-MAC, or server-accept behavior changes.
- EX-06 is `NO_GO_PENDING_DEVICE_AB`; preserve AUTO status polling, no separate requestWait/reaper. EX-05 is `NO_GO_PENDING_EX01_DEVICE_EVIDENCE`; no second StartSession/EndSession command. PR-03 keeps 100 ms pre-ready OUT and NDP-only retry. PR-02 keeps the 32 KiB request at minSdk28. EX-04 adds no same-node timer without device evidence.
- PR-00 baseline: 600 unit tests passed before implementation; see `completed.md`.
- EX-01: shared/common tests 482 + 136; API36.1 x86_64 emulator instrumentation 16/16. WiredPath TS10/iPhone observations and performance remain `NOT_RUN` / `UNKNOWN`.
- PR-01: shared/common tests 491 + 136; API36.1 x86_64 emulator instrumentation 16/16; local lint/build/JNI/public-tree checks pass. Physical NCM RX/connection-rate/throughput/CPU-PSS remain `NOT_RUN` / `UNKNOWN`.
- EX-02: shared/common tests 491 + 142; lint/build/JNI/public-tree checks pass. Activity/owner-handoff stress remains `NOT_RUN`.
- EX-03: shared/common tests 495 + 142; lint/build/JNI/public-tree checks pass. Physical ALT-only, delayed MAIN, and first-frame timing remains `NOT_RUN`.
- PR-04 local verification: shared/common tests 504 + 142 = 646, zero failures/errors/skips; full mobile/automotive lint, debug/benchmark builds, JNI mapping, public-tree, and whitespace checks pass. Physical Siri/phone/voice-note, mic permission, AEC, and Bluetooth behavior remains `NOT_RUN`.
- Host native DSP scripts remain `NOT_RUN` because WSL Ubuntu launch failed with `HCS_E_SERVICE_NOT_AVAILABLE` and no Windows C compiler is available. No independent second reviewer was available for completed stages.
- Preserve fork-specific CarPlay, GeckoView Split View, DSP, audio, and background ownership. Keep EX-08 open until commit/path-level review is complete.
