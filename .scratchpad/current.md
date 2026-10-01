# Current Work

- PR #14 (`codex/dsp-01`): continue Universal Stereo DSP implementation through PR-DSP-12 without user checkpoints. PR-DSP-02 is complete. PR-DSP-03 local tests, ABI builds, lint, app builds, and x86_64 emulator smoke test pass; pushed CI ASan/UBSan and Android build/lint are green. GitHub's SDK manifest pointed at a 404 emulator archive; direct installation now overwrites the runner's partial emulator files, and AVD creation selects `pixel_7_pro`. Verify that rerun before PR-DSP-04. Preserve DSP-OFF legacy PCM16 and the two-channel output limit.
