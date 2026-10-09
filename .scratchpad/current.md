# Current Work

## Task: Complete four DSP modules in a new PR

- Goal: implement the attached v1 specification; PR #14 was merged into main, so open a new PR after completion.
- Base: `95ba398f95ee104527812cd2b095977d71c51f98`; branch: `codex/diplay-4dsp`.
- Status: Phase 0 baseline is `f9deb04`; Phase 1 models/schema v4 are `2943442`; Phase 2 Seat Focus is implemented and verified on the new PR branch. Continue with Phase 3 Advanced M/S, then Cabin Correction, Harmonic Engine, integration and final audit.
- Preserve DSP-master-off PCM16, MEDIA-only routing, existing two-channel output and M/S behavior, Convolver timing, playback-clock accounting, and CarPlay lifecycle.
- Phase 2 verification: 38 targeted Kotlin unit cases, 17 API 36 x86_64 instrumentation cases, 3 ABI native builds, and the host C DSP suite pass. Fractional-delay correlation error is below 0.009 ms at 44.1/48/96 kHz.
- TS10 is not attached; real-device audio, CarPlay continuity, and long-run dropout remain unverified.
