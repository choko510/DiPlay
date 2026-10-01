# Current Work

- PR #14 (`codex/dsp-01`): continue Universal Stereo DSP implementation from PR-DSP-11 through PR-DSP-12 without user checkpoints. PR-DSP-02 through PR-DSP-10 are implemented; local gates for DSP-08/09 passed with 412 shared/100 common tests, and DSP-10 with 416 shared/101 common tests, both app lint/builds, three NDK ABIs, host C tests, and ten x86_64 JNI tests. Keep checking GitHub Actions after each push: the current PR head has no hosted workflow run, so ASan/UBSan remain unverified. Preserve DSP-OFF legacy PCM16 and the two-channel output limit.
