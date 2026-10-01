# Current Work

- PR #14 (`codex/dsp-01`): continue Universal Stereo DSP implementation from PR-DSP-05 through PR-DSP-12 without user checkpoints. PR-DSP-02 through PR-DSP-04 are complete and gated, including native ASan/UBSan and x86_64 JNI smoke. For each remaining phase, implement, test, fix, run all gates, review, commit, then continue. Preserve DSP-OFF legacy PCM16 and the two-channel output limit.
