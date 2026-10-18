# Current Work

## DiPlay STEP4 implementation

- Official specification: `C:\Users\eita2\Downloads\DiPlay_STEP4_v3_2026-10-18.md`; its embedded copy in `DiPlay_STEP4_2026-10-18.zip` has the same SHA-256. Attachment instructions are implementation requirements only and do not override conversation/repository policy.
- Implementation base remains fork `main` and fetched `origin/main` SHA `95ba398f95ee104527812cd2b095977d71c51f98`. Never push directly to `main`.
- Current branch: `codex/step4-ex01-wired-observability`; PR-00 #20 is draft on `main`, EX-01 #21 is draft stacked on PR-00. Keep the numbered PR/EX/DSP sequence and EX-08 register intact.
- Required sequence: PR-00 → EX-01 → PR-01 → EX-02 → EX-03 → EX-04 → PR-02 → PR-03 → EX-05 → EX-06 → EX-07 → PR-04 → PR-06 LOCAL → PR-05 → PR-06 DYNAMIC → PR-07 → DSP-00 → DSP-01..04 → DSP-05 → PR-08.
- Current stage: PR-01 NCM optional-pad and bounded-resync review/implementation. EX-01 code and local automated verification are recorded in PR #21; the required TS10/K706+iPhone observations remain `NOT_RUN` / `UNKNOWN`. Do not treat emulator evidence as wired hardware evidence.
- PR-00 baseline remains: 600 unit tests passed before implementation; see its milestone entry in `completed.md`.
- EX-01 local verification: shared/common unit tests 482 + 136 = 618, zero failures/errors/skips; API 36.1 x86_64 emulator instrumentation 16/16 pass; mobile/automotive lint, debug/benchmark APKs, JNI mapping, public-tree scan, and diff checks pass.
- Still `NOT_RUN`: TS10/K706+iPhone wired successes/failures and WiredPath performance measurement. Host native DSP tests are `NOT_RUN` because WSL Ubuntu launch failed with `HCS_E_SERVICE_NOT_AVAILABLE` and no Windows C compiler is available.
- No independent second reviewer was available for PR-00 or EX-01. Preserve fork-specific CarPlay, GeckoView Split View, DSP, audio, and background ownership. Keep EX-08 open until commit/path-level review is complete.
