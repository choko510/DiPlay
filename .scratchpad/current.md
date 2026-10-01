# Current Work

- PR #14 (`codex/dsp-01`): continue Universal Stereo DSP implementation through PR-DSP-12 without user checkpoints. PR-DSP-02 is complete. PR-DSP-03 local tests, ABI builds, lint, app builds, and x86_64 emulator smoke test pass; wait for pushed CI ASan/UBSan and emulator jobs before marking the phase complete, then continue to PR-DSP-04. Preserve DSP-OFF legacy PCM16 and the two-channel output limit.
