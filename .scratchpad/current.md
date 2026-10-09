# Current Work

## Task: Complete four DSP modules in a new PR

- Goal: implement the attached v1 specification; PR #14 was merged into main, so open a new PR after completion.
- Base: `95ba398f95ee104527812cd2b095977d71c51f98`; branch: `codex/diplay-4dsp`.
- Status: transplanting the Phase 0/1 implementation onto the merged PR #14 base; Phase 2 Seat Focus is in progress and stashed until the cherry-picks finish.
- Preserve DSP-master-off PCM16, MEDIA-only routing, existing two-channel output and M/S behavior, Convolver timing, playback-clock accounting, and CarPlay lifecycle.
- TS10 is not attached; real-device audio, CarPlay continuity, and long-run dropout remain unverified.
