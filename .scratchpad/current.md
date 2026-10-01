# Current Work

- PR #14 (`codex/dsp-01`): continue Universal Stereo DSP implementation through PR-DSP-12 without user checkpoints. PR-DSP-02 is complete. PR-DSP-03 local tests, ABI builds, lint, app builds, and x86_64 emulator smoke test pass; pushed CI ASan/UBSan and Android build/lint are green. The manual hosted emulator startup failed because `emulator` was missing from PATH and `avdmanager` stayed interactive; CI now uses the managed x86_64 emulator action. Verify that rerun before PR-DSP-04. Preserve DSP-OFF legacy PCM16 and the two-channel output limit.
