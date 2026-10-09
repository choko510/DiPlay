# Current Work

## Task: Complete four DSP modules in a new PR

- Goal: implement the attached v1 specification; PR #14 was merged into main, so open a new PR after completion.
- Base: `95ba398f95ee104527812cd2b095977d71c51f98`; branch: `codex/diplay-4dsp`.
- Status: Phase 0 baseline is `f9deb04`; Phase 1 models/schema v4 are `2943442`; Phases 2 Seat Focus, 3 Advanced M/S, and 4 Cabin Correction are implemented and verified. Continue with Phase 5 Harmonic Engine, integration and final audit.
- Preserve DSP-master-off PCM16, MEDIA-only routing, existing two-channel output and M/S behavior, Convolver timing, playback-clock accounting, and CarPlay lifecycle.
- Phase 2 verification: 38 targeted Kotlin unit cases, 17 API 36 x86_64 instrumentation cases, 3 ABI native builds, and the host C DSP suite pass. Fractional-delay correlation error is below 0.009 ms at 44.1/48/96 kHz.
- Phase 3 verification: 51 targeted Kotlin unit/UI contract cases, 18 API 36 x86_64 instrumentation cases, 3 ABI native builds, and the host C suite pass. Neutral Advanced M/S matches legacy width/Mono Bass; the suite measures width factors at 40 Hz/1 kHz/15 kHz and checks monitor crossfades.
- Phase 4 verification: 69 targeted Kotlin unit/UI/profile cases, 19 API 36 x86_64 instrumentation cases, 3 ABI native builds, and the host C suite pass. Cabin tests cover strict CSV/JSON import, duplicate/size rejection, measured peak candidates, static/dynamic cuts, missing-reference fail-open, transient bypass persistence, and A/B crossfade.
- TS10 is not attached; real-device audio, CarPlay continuity, and long-run dropout remain unverified.
