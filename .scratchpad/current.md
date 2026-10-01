# Current Work

- PR #14 (`codex/dsp-01`): continue Universal Stereo DSP implementation from PR-DSP-10 through PR-DSP-12 without user checkpoints. PR-DSP-02 through PR-DSP-09 are implemented; local gates for DSP-08/09 passed, including host C tests, 412 shared/100 common tests, mobile/automotive lint and debug builds, three NDK ABIs, and nine x86_64 JNI tests. Keep checking GitHub Actions after each push: the current PR head has no hosted workflow run yet, so ASan/UBSan remain unverified. Preserve DSP-OFF legacy PCM16 and the two-channel output limit.
